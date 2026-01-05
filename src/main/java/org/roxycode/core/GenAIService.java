package org.roxycode.core;

import com.google.genai.Client;
import com.google.genai.types.*;
import jakarta.inject.Singleton;
import org.roxycode.core.context.ContextRegistry;
import org.roxycode.core.tools.ToolDefinition;
import org.roxycode.core.tools.ToolExecutionService;
import org.roxycode.core.tools.ToolRegistry;
import org.roxycode.core.tools.service.BuildToolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Singleton
public class GenAIService {

    private static final Logger LOG = LoggerFactory.getLogger(GenAIService.class);

    private final SettingsService settingsService;

    private final ToolRegistry toolRegistry;

    private final ToolExecutionService executionService;

    private final Sandbox sandbox;

    private final ContextRegistry contextRegistry;

    private final UsageService usageService;

    private final HistoryService historyService;

    private final BuildToolService buildToolService;

    private final org.roxycode.cache.GeminiCacheService geminiCacheService;

    private Client client;

    private String lastUsedApiKey;

    private Path cachedRoxyHome;

    public GenAIService(SettingsService settingsService, ToolRegistry toolRegistry, ToolExecutionService executionService, Sandbox sandbox, ContextRegistry contextRegistry, UsageService usageService, HistoryService historyService, BuildToolService buildToolService, org.roxycode.cache.GeminiCacheService geminiCacheService) {
        this.settingsService = settingsService;
        this.toolRegistry = toolRegistry;
        this.executionService = executionService;
        this.sandbox = sandbox;
        this.contextRegistry = contextRegistry;
        this.usageService = usageService;
        this.historyService = historyService;
        this.buildToolService = buildToolService;
        this.geminiCacheService = geminiCacheService;
    }

    private synchronized Client getClient() {
        String key = settingsService.getGeminiApiKey();
        if (key == null || key.isEmpty()) {
            throw new IllegalStateException("Gemini API Key not found in Settings.");
        }
        if (client == null || !key.equals(lastUsedApiKey)) {
            LOG.info("Initializing/Refreshing Gemini Client...");
            client = Client.builder().apiKey(key).httpOptions(HttpOptions.builder().retryOptions(HttpRetryOptions.builder().attempts(5).httpStatusCodes(429, 503).build()).timeout(60_000).build()).build();
            lastUsedApiKey = key;
        }
        return client;
    }

    public void refreshKnowledge(String projectRoot) {
        LOG.info("🔄 Refreshing Knowledge Base. Root: {}", projectRoot);
        Path roxyHome = settingsService.getRoxyHome();
        this.cachedRoxyHome = roxyHome;
        LOG.info("🏠 Roxy Home detected at: {}", roxyHome);
        LOG.info("Loading Contexts ...");
        contextRegistry.loadContexts(roxyHome.resolve("context"));
        LOG.info("Loading Tools ...");
        toolRegistry.loadTools(roxyHome.resolve("tools"));

        LOG.info("✅ Knowledge Refresh Complete. Loaded {} tools.", toolRegistry.getAllToolNames().size());
    }

    private final List<Content> history = new ArrayList<>();

    private int inTokens = 0;

    private int outTokens = 0;

    private RoxyMode roxyMode = RoxyMode.DISCOVERY;

    public RoxyMode getRoxyMode() {
        return roxyMode;
    }

    public void setRoxyMode(RoxyMode roxyMode) {
        this.roxyMode = roxyMode;
    }

    public int getInTokens() {
        return inTokens;
    }

    public int getOutTokens() {
        return outTokens;
    }

    public void clearHistory() {
        history.clear();
    }

    public List<Content> getHistory() {
        return Collections.unmodifiableList(history);
    }

    private final AtomicBoolean isChatting = new AtomicBoolean(false);

    private volatile boolean stopRequested = false;

    public void stopChat() {
        this.stopRequested = true;
    }

    protected GenerateContentResponse doGenerateContent(String model, List<Content> history, GenerateContentConfig config) {
        return getClient().models.generateContent(model, history, config);
    }

    public String buildSystemContext(String projectRoot, List<File> attachedFiles) {
        Path projectPath = Paths.get(projectRoot);
        java.util.Optional<org.roxycode.cache.CodebaseCacheMeta> cacheMeta = geminiCacheService.getProjectCacheMeta(projectPath);
        return buildSystemContext(projectRoot, attachedFiles, cacheMeta);
    }

    public String buildSystemContext(String projectRoot, List<File> attachedFiles, java.util.Optional<org.roxycode.cache.CodebaseCacheMeta> cacheMeta) {
        String contextMenu = contextRegistry.getContextMenu();
        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("Project Root: ").append(projectRoot).append("\n");
        contextBuilder.append("Roxy Home: ").append(cachedRoxyHome != null ? cachedRoxyHome : "Not loaded").append("\n");
        if (cacheMeta.isPresent()) {
            contextBuilder.append("### Project Info (CACHED)\n");
            contextBuilder.append("- Build Tool: ").append(buildToolService.detect()).append("\n");
            contextBuilder.append("- OS: ").append(buildToolService.getOperatingSystem()).append("\n");
            contextBuilder.append("[System: Codebase is currently cached in Gemini. You should have access to the file contents.]\n\n");
        } else {
            contextBuilder.append(buildToolService.getProjectSummary()).append("\n\n");
        }
        contextBuilder.append(contextMenu).append("\n");
        if (attachedFiles != null && !attachedFiles.isEmpty()) {
            contextBuilder.append("\n--- ATTACHED FILES ---\n");
            for (File file : attachedFiles) {
                contextBuilder.append("File: ").append(file.getName()).append("\n");
                contextBuilder.append("Content:\n");
                try {
                    contextBuilder.append(Files.readString(file.toPath())).append("\n");
                } catch (IOException e) {
                    contextBuilder.append("[Error reading file: ").append(e.getMessage()).append("]\n");
                }
                contextBuilder.append("----------------------\n");
            }
        }
        return contextBuilder.toString();
    }

    public String chat(String prompt, String projectRoot, List<File> attachedFiles, Consumer<String> onStatusUpdate) {
        if (!isChatting.compareAndSet(false, true)) {
            return "A chat is already in progress.";
        }
        try {
            this.stopRequested = false;
            sandbox.setRoot(projectRoot);
            Path projectPath = Paths.get(projectRoot);
            Optional<org.roxycode.cache.CodebaseCacheMeta> cacheMeta = geminiCacheService.getProjectCacheMeta(projectPath);
            String systemContext = buildSystemContext(projectRoot, attachedFiles, cacheMeta);
            LOG.info("systemContext: {}", systemContext);
            String taskMessage = "Task: " + prompt;
            LOG.info("taskMessage: {}", taskMessage);
            // --- History Management (Index 0 is always System) ---
            if (history.isEmpty()) {
                history.add(Content.builder().role("user").parts(List.of(Part.builder().text(systemContext + "\n" + taskMessage).build())).build());
            } else {
                // Update System Prompt (Index 0) with latest known state
                history.set(0, Content.builder().role("user").parts(List.of(Part.builder().text(systemContext).build())).build());
                history.add(Content.builder().role("user").parts(List.of(Part.builder().text(taskMessage).build())).build());
            }
            int turns = 0;
            int maxTurns = settingsService.getMaxTurns();
            while (turns++ < maxTurns) {
                if (stopRequested)
                    return "Chat stopped by user.";
                if (onStatusUpdate != null) {
                    onStatusUpdate.accept(String.format("Thinking (%d/%d)...", turns, maxTurns));
                }

                historyService.applySlidingWindow(history);

                GenerateContentConfig.Builder configBuilder = GenerateContentConfig.builder();

                // --- FIX: Handle Tools vs Cache Conflict ---
                if (cacheMeta.isPresent()) {
                    LOG.info("Using cache!");
                    // API Rule: Cannot pass tools if cachedContent is used.
                    // (Tools must be baked into the cache creation)
                    configBuilder.cachedContent(cacheMeta.get().geminiCacheId());
                } else {
                    LOG.info("NOT using cache");
                    List<Tool> tools = toolRegistry.getAllGeminiTools();
                    // Only pass tools dynamically if NOT using a cache
                    if (!tools.isEmpty()) {
                        configBuilder.tools(tools);
                    }
                }
                // -------------------------------------------

                GenerateContentResponse response = doGenerateContent(settingsService.getGeminiModel(), history, configBuilder.build());
                if (response.usageMetadata().isPresent()) {
                    GenerateContentResponseUsageMetadata usage = response.usageMetadata().get();
                    inTokens = usage.promptTokenCount().orElse(0);
                    outTokens = usage.candidatesTokenCount().orElse(0);
                    usageService.recordUsage(inTokens, outTokens);
                }
                Optional<List<Candidate>> candidatesOpt = response.candidates();
                if (candidatesOpt.isEmpty() || candidatesOpt.get().isEmpty()) {
                    return "Error: No response candidates.";
                }
                Candidate firstCandidate = candidatesOpt.get().getFirst();
                Content modelMessage = firstCandidate.content().orElse(Content.builder().build());
                history.add(modelMessage);
                // Check for Tool Calls
                List<Part> parts = modelMessage.parts().orElse(Collections.emptyList());
                List<Part> functionResponseParts = new ArrayList<>();
                List<Content> subsequentUserMessages = new ArrayList<>();
                boolean hasFunctionCall = false;
                for (Part part : parts) {
                    if (stopRequested)
                        return "Chat stopped by user.";
                    Optional<FunctionCall> callOpt = part.functionCall();
                    if (callOpt.isPresent()) {
                        hasFunctionCall = true;
                        FunctionCall call = callOpt.get();
                        String fnName = call.name().orElse("unknown");
                        Map<String, Object> fixedArgs = new HashMap<>(call.args().orElse(Collections.emptyMap()));
                        // Resolve relative paths in arguments
                        if (fixedArgs.containsKey("path")) {
                            String originalPath = (String) fixedArgs.get("path");
                            if (!Paths.get(originalPath).isAbsolute()) {
                                Path resolvedPath = Paths.get(projectRoot, originalPath);
                                fixedArgs.put("path", resolvedPath.toString());
                            }
                        }
                        if (onStatusUpdate != null)
                            onStatusUpdate.accept("Executing Tool: " + fnName);
                        LOG.info("AI calling tool: {} with args {}", fnName, fixedArgs);
                        String toolOutput;
                        try {
                            ToolDefinition toolDef = toolRegistry.getTool(fnName).orElseThrow(() -> new IllegalStateException("Tool not found: " + fnName));
                            toolOutput = executionService.execute(toolDef, fixedArgs).get();
                        } catch (Exception e) {
                            toolOutput = "Error executing tool [" + fnName + "]: " + e.getMessage();
                        }
                        // Image Handling
                        if (toolOutput.endsWith(".png") && Files.exists(Paths.get(toolOutput))) {
                            try {
                                byte[] imageBytes = Files.readAllBytes(Paths.get(toolOutput));
                                Blob imageBlob = Blob.builder().mimeType("image/png").data(imageBytes).build();
                                subsequentUserMessages.add(Content.builder().role("user").parts(List.of(Part.builder().inlineData(imageBlob).build(), Part.builder().text("Screenshot captured.").build())).build());
                                toolOutput = "Screenshot captured.";
                            } catch (Exception e) {
                                LOG.error("Image load fail", e);
                            }
                        }
                        functionResponseParts.add(Part.builder().functionResponse(FunctionResponse.builder().name(fnName).response(Map.of("result", toolOutput)).build()).build());
                    }
                }
                if (hasFunctionCall) {
                    if (!functionResponseParts.isEmpty()) {
                        history.add(Content.builder().role("function").parts(functionResponseParts).build());
                    }
                    history.addAll(subsequentUserMessages);
                    // Loop back for model to process tool output
                    continue;
                }
                String finalResponse = parts.stream().map(p -> p.text().orElse("")).collect(Collectors.joining(""));
                if (finalResponse.isBlank()) {
                    return "[Model returned an empty response. Finish reason: " + firstCandidate.finishReason().orElse(null) + "]";
                }
                return finalResponse;
            }
            return "Max turns reached.";
        } finally {
            isChatting.set(false);
        }
    }
}