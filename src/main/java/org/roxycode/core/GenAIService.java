package org.roxycode.core;

import com.google.genai.Client;
import com.google.genai.types.*;
import jakarta.inject.Singleton;
import org.roxycode.core.context.ContextRegistry;
import org.roxycode.core.tools.ToolDefinition;
import org.roxycode.core.tools.ToolExecutionService;
import org.roxycode.core.tools.ToolRegistry;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    private Client client;

    private String lastUsedApiKey;

    // Cache function declarations to avoid re-scanning every chat turn
    private final List<FunctionDeclaration> cachedFunctions = new ArrayList<>();

    // Track where we loaded from
    private Path cachedRoxyHome;

    public GenAIService(SettingsService settingsService, ToolRegistry toolRegistry, ToolExecutionService executionService, Sandbox sandbox, ContextRegistry contextRegistry, UsageService usageService, HistoryService historyService) {
        this.settingsService = settingsService;
        this.toolRegistry = toolRegistry;
        this.executionService = executionService;
        this.sandbox = sandbox;
        this.contextRegistry = contextRegistry;
        this.usageService = usageService;
        this.historyService = historyService;
    }

    private synchronized Client getClient() {
        String key = settingsService.getGeminiApiKey();
        if (key == null || key.isEmpty()) {
            throw new IllegalStateException("Gemini API Key not found in Settings.");
        }
        // Recreate client if API key has changed or if it's not yet initialized
        if (client == null || !key.equals(lastUsedApiKey)) {
            LOG.info("Initializing/Refreshing Gemini Client with automatic retry (5 attempts, 2min timeout)...");
            client = Client.builder().apiKey(key).
            httpOptions(HttpOptions.builder().retryOptions(HttpRetryOptions.builder().attempts(5).httpStatusCodes(429, 503).build()).timeout(60_000).build()).build();
            lastUsedApiKey = key;
        }
        return client;
    }

    /**
     * Scans directories, loads tools/contexts, and rebuilds function definitions.
     * Should be called on startup or when the user clicks "Rescan".
     */
    public void refreshKnowledge(String projectRoot) {
        LOG.info("🔄 Refreshing Knowledge Base. Root: {}", projectRoot);
        // 1. LOCATE ROXY_HOME
        Path roxyHome = settingsService.getRoxyHome();
        this.cachedRoxyHome = roxyHome;
        LOG.info("🏠 Roxy Home detected at: {}", roxyHome);
        // 2. LOAD CONTEXT KNOWLEDGE
        contextRegistry.loadContexts(roxyHome.resolve("context"));
        // 3. DISCOVER AND LOAD TOOLS
        cachedFunctions.clear();
        Path toolsPath = roxyHome.resolve("tools");
        List<String> availableToolNames = new ArrayList<>();
        if (Files.exists(toolsPath)) {
            LOG.info("🔧 Loading tools from: {}", toolsPath.toAbsolutePath());
            toolRegistry.loadTools(toolsPath.toString());
            try (Stream<Path> stream = Files.list(toolsPath)) {
                availableToolNames = stream.filter(p -> p.toString().endsWith(".toml")).map(p -> p.getFileName().toString().replace(".toml", "")).toList();
            } catch (IOException e) {
                LOG.error("Failed to list tool files", e);
            }
        } else {
            LOG.warn("⚠️ Tools directory not found at: {}", toolsPath.toAbsolutePath());
        }
        // 4. BUILD GEMINI FUNCTION DECLARATIONS
        for (String toolName : availableToolNames) {
            toolRegistry.getTool(toolName).ifPresent(td -> {
                Map<String, Schema> properties = new HashMap<>();
                if (td.getParameters() != null) {
                    td.getParameters().forEach(p -> properties.put(p.getName(), Schema.builder().type("STRING").description(p.getDescription()).build()));
                }
                Schema schema = Schema.builder().type("OBJECT").properties(properties).build();
                cachedFunctions.add(FunctionDeclaration.builder().name(toolName).description(td.getDescription()).parameters(schema).build());
            });
        }
        LOG.info("✅ Knowledge Refresh Complete. Loaded {} tools.", cachedFunctions.size());
    }

    private final List<Content> history = new ArrayList<>();

    private int inTokens = 0;

    private int outTokens = 0;

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
        String contextMenu = contextRegistry.getContextMenu();
        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("Project Root: ").append(projectRoot).append("n");
        contextBuilder.append("Roxy Home: ").append(cachedRoxyHome != null ? cachedRoxyHome : "Not loaded").append("n");
        contextBuilder.append(contextMenu).append("n");
        if (attachedFiles != null && !attachedFiles.isEmpty()) {
            contextBuilder.append("n--- ATTACHED FILES ---n");
            for (File file : attachedFiles) {
                contextBuilder.append("File: ").append(file.getName()).append("n");
                contextBuilder.append("Content:n");
                try {
                    contextBuilder.append(Files.readString(file.toPath())).append("n");
                } catch (IOException e) {
                    contextBuilder.append("[Error reading file: ").append(e.getMessage()).append("]n");
                }
                contextBuilder.append("----------------------n");
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
            // 1. CONFIGURE SANDBOX
            sandbox.setRoot(projectRoot);
            // 2. Build Context (System info + Knowledge base)
            String systemContext = buildSystemContext(projectRoot, attachedFiles);

            String taskMessage = "Task: " + prompt;
            if (history.isEmpty()) {
                history.add(Content.builder().role("user").parts(List.of(Part.builder().text(systemContext + "\n" + taskMessage).build())).build());
            } else {
                // Update the context at index 0. Note: HistoryService might have added summaries here,
                // but we want the LATEST knowledge base info. HistoryService.compactHistory will re-add summaries.
                history.set(0, Content.builder().role("user").parts(List.of(Part.builder().text(systemContext).build())).build());
                history.add(Content.builder().role("user").parts(List.of(Part.builder().text(taskMessage).build())).build());
            }
            LOG.info("History size before loop: {}", history.size());
            // 3. Conversation Loop
            int turns = 0;
            int maxTurns = settingsService.getMaxTurns();
            while (turns++ < maxTurns) {
                if (stopRequested) {
                    return "Chat stopped by user.";
                }
                LOG.info("Turn {}: Sending message to model...", turns);
                if (onStatusUpdate != null) {
                    onStatusUpdate.accept(String.format("Thinking (%d/%d)...", turns, maxTurns));
                }
                // historyService will update history.get(0) with summaries if needed, using systemContext as base
                historyService.compactHistory(getClient(), "gemini-2.0-flash", history, systemContext);
                GenerateContentConfig.Builder configBuilder = GenerateContentConfig.builder();
                if (!cachedFunctions.isEmpty()) {
                    Tool toolConfig = Tool.builder().functionDeclarations(cachedFunctions).build();
                    configBuilder.tools(List.of(toolConfig));
                }
                GenerateContentResponse response = doGenerateContent(settingsService.getGeminiModel(), history, configBuilder.build());
                if (response.usageMetadata().isPresent()) {
                    GenerateContentResponseUsageMetadata usage = response.usageMetadata().get();
                    inTokens = usage.promptTokenCount().orElse(0);
                    outTokens = usage.candidatesTokenCount().orElse(0);
                    usageService.recordUsage(inTokens, outTokens);
                }
                Optional<List<Candidate>> candidatesOpt = response.candidates();
                if (candidatesOpt.isEmpty() || candidatesOpt.get().isEmpty()) {
                    LOG.error("No candidates in response.");
                    return "Error: No response candidates.";
                }
                Candidate firstCandidate = candidatesOpt.get().getFirst();
                LOG.info("Response received. Finish reason: {}. Candidate count: {}", firstCandidate.finishReason().orElse(null), candidatesOpt.get().size());
                Content modelMessage = firstCandidate.content().orElse(Content.builder().build());
                history.add(modelMessage);
                List<Part> parts = modelMessage.parts().orElse(Collections.emptyList());
                // --- PARALLEL FUNCTION HANDLING ---
                List<Part> functionResponseParts = new ArrayList<>();
                List<Content> subsequentUserMessages = new ArrayList<>();
                boolean hasFunctionCall = false;
                for (Part part : parts) {
                    if (stopRequested) {
                        return "Chat stopped by user.";
                    }
                    Optional<FunctionCall> callOpt = part.functionCall();
                    if (callOpt.isPresent()) {
                        hasFunctionCall = true;
                        FunctionCall call = callOpt.get();
                        String fnName = call.name().orElse("unknown");
                        Map<String, Object> originalArgs = call.args().orElse(Collections.emptyMap());
                        Map<String, Object> fixedArgs = new HashMap<>(originalArgs);
                        if (fixedArgs.containsKey("path")) {
                            String originalPath = (String) fixedArgs.get("path");
                            if (!Paths.get(originalPath).isAbsolute()) {
                                Path resolvedPath = Paths.get(projectRoot, originalPath);
                                fixedArgs.put("path", resolvedPath.toString());
                            }
                        }
                        LOG.info("AI calling tool: {} with args {}", fnName, fixedArgs);
                        if (onStatusUpdate != null) {
                            StringBuilder sb = new StringBuilder();
                            sb.append("Tool: ").append(fnName).append(" | ");
                            fixedArgs.forEach((k, v) -> {
                                String val = String.valueOf(v);
                                String truncated = StringUtils.abbreviate(val, 100);
                                sb.append(k).append("=").append(truncated).append(", ");
                            });
                            onStatusUpdate.accept(sb.toString().trim());
                        }
                        String toolOutput;
                        try {
                            ToolDefinition toolDef = toolRegistry.getTool(fnName).orElseThrow(() -> new IllegalStateException("Tool not found: " + fnName));
                            toolOutput = executionService.execute(toolDef, fixedArgs).get();
                        } catch (Exception e) {
                            toolOutput = "Error executing tool [" + fnName + "]: " + e.getMessage();
                        }
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
                    continue;
                }
                String finalResponse = parts.stream().map(p -> p.text().orElse("")).collect(Collectors.joining(""));
                if (finalResponse.isBlank()) {
                    LOG.warn("Model returned an empty text response. Finish reason: {}", firstCandidate.finishReason().orElse(null));
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
