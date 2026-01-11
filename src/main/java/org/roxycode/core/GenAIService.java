package org.roxycode.core;

import com.google.genai.Client;
import com.google.genai.types.*;
import jakarta.inject.Singleton;
import org.apache.commons.collections4.MapUtils;
import org.roxycode.core.beans.ProjectCacheMeta;
import org.roxycode.core.cache.ProjectCacheMetaService;
import org.roxycode.core.tools.ToolDefinition;
import org.roxycode.core.tools.service.plans.Plan;
import org.roxycode.core.tools.service.plans.PlanService;
import org.roxycode.core.tools.ToolExecutionService;
import java.util.Base64;
import org.roxycode.core.tools.ToolRegistry;
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

    private final UsageService usageService;

    private final HistoryService historyService;

    private final ProjectCacheMetaService projectCacheMetaService;
    private final PlanService planService;
    private final RoxyProjectService roxyProjectService;
    private final GeminiClientFactory geminiClientFactory;

    private Client client;

    private String lastUsedApiKey;

    public GenAIService(SettingsService settingsService, ToolRegistry toolRegistry, ToolExecutionService executionService,
                        UsageService usageService, HistoryService historyService,
                        RoxyProjectService roxyProjectService,
                        ProjectCacheMetaService projectCacheMetaService, GeminiClientFactory geminiClientFactory, PlanService planService) {
        this.settingsService = settingsService;
        this.toolRegistry = toolRegistry;
        this.executionService = executionService;
        this.usageService = usageService;
        this.historyService = historyService;
        this.roxyProjectService = roxyProjectService;
        this.projectCacheMetaService = projectCacheMetaService;
        this.geminiClientFactory = geminiClientFactory;
        this.planService = planService;
    }

    private synchronized Client getClient() {
        String key = settingsService.getGeminiApiKey();
        if (key == null || key.isEmpty()) {
            throw new IllegalStateException("Gemini API Key not found in Settings.");
        }
        if (client == null || !key.equals(lastUsedApiKey)) {
            LOG.info("Initializing/Refreshing Gemini Client...");
            client = geminiClientFactory.createClientWithRetry(key);
            lastUsedApiKey = key;
        }
        return client;
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
    private final List<Consumer<Boolean>> busyListeners = new ArrayList<>();


    private volatile boolean stopRequested = false;


    public void addBusyListener(Consumer<Boolean> listener) {
        busyListeners.add(listener);
    }

    private void notifyBusy(boolean busy) {
        for (Consumer<Boolean> listener : busyListeners) {
            listener.accept(busy);
        }
    }

    public void stopChat() {
        this.stopRequested = true;
    }

    protected GenerateContentResponse doGenerateContent(String model, List<Content> history, GenerateContentConfig config) {
        return getClient().models.generateContent(model, history, config);
    }

    public String buildSystemMessage(String projectRoot, List<File> attachedFiles, java.util.Optional<ProjectCacheMeta> cacheMeta) {
        StringBuilder contextBuilder = new StringBuilder();
        if (cacheMeta.isEmpty()) {

        // Add Plan Context
        try {
            String planMarkdown = planService.getCurrentPlanMarkdown();
            if (planMarkdown != null) {
                contextBuilder.append("\n### CURRENT PLAN: ").append(roxyProjectService.getCurrentPlan()).append("\n");
                contextBuilder.append(planMarkdown);
                contextBuilder.append("\n----------------------\n");
            }
        } catch (IOException e) {
            LOG.error("Failed to load current plan", e);
        }
            contextBuilder.append(roxyProjectService.getStaticSystemPrompt());
        }
        contextBuilder.append("Project Root: ").append(projectRoot).append("\n");
        if (cacheMeta.isPresent()) {
            contextBuilder.append("### Project Info (CACHED)\n");
            contextBuilder.append("[System: Codebase is currently cached in Gemini. You should have access to the file contents.]\n\n");
        } else {
            contextBuilder.append("Codebase is not cached, please use provided tools").append("\n\n");
        }
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
        notifyBusy(true);
        try {
            this.stopRequested = false;
            Optional<ProjectCacheMeta> cacheMeta = projectCacheMetaService.getProjectCacheMeta();
            String systemMessage = buildSystemMessage(projectRoot, attachedFiles, cacheMeta);
            LOG.info("systemContext: {}", systemMessage);

            initializeHistory(prompt, systemMessage);

            int turns = 0;
            int maxTurns = settingsService.getMaxTurns();
            int maxTurnsPerMinute = settingsService.getMaxTurnsPerMinute();
            long minTurnDurationMillis = 60000L / Math.max(1, maxTurnsPerMinute);
            while (turns++ < maxTurns) {
                long turnStart = System.currentTimeMillis();
                if (stopRequested)
                    return "Chat stopped by user.";
                if (onStatusUpdate != null) {
                    onStatusUpdate.accept(String.format("Thinking (%d/%d)...", turns, maxTurns));
                }

                historyService.applySlidingWindow(history);

                GenerateContentConfig config = prepareConfig(cacheMeta);

                GenerateContentResponse response = doGenerateContent(settingsService.getGeminiModel(), history, config);
                Candidate firstCandidate;
                try {
                    firstCandidate = processResponse(response);
                } catch (IllegalStateException e) {
                    return e.getMessage();
                }

                Content modelMessage = firstCandidate.content().orElse(Content.builder().build());
                history.add(modelMessage);

                // Check for Tool Calls
                List<Content> subsequentUserMessages = new ArrayList<>();
                List<Part> functionResponseParts = new ArrayList<>();

                boolean hasFunctionCall = executeToolCalls(modelMessage, projectRoot, onStatusUpdate, subsequentUserMessages, functionResponseParts);

                if (hasFunctionCall) {
                    if (stopRequested) {
                        return "Chat stopped by user.";
                    }
                    if (!functionResponseParts.isEmpty()) {
                        history.add(Content.builder().role("function").parts(functionResponseParts).build());
                    }
                    history.addAll(subsequentUserMessages);
                    // Loop back for model to process tool output
                    Optional<String> error = waitForRateLimit(turnStart, minTurnDurationMillis);
                    if (error.isPresent()) return error.get();
                    continue;
                }

                List<Part> parts = modelMessage.parts().orElse(Collections.emptyList());
                String finalResponse = parts.stream().map(p -> p.text().orElse("")).collect(Collectors.joining(""));
                
                // Rate Limit Delay
                Optional<String> error = waitForRateLimit(turnStart, minTurnDurationMillis);
                if (error.isPresent()) return error.get();
                if (finalResponse.isBlank()) {
                    return "[Model returned an empty response. Finish reason: " + firstCandidate.finishReason().orElse(null) + "]";
                }
                return finalResponse;
            }
            return "Max turns reached.";
        } finally {
            isChatting.set(false);
            notifyBusy(false);
        }
    }


    private Optional<String> waitForRateLimit(long turnStart, long minTurnDurationMillis) {
        long elapsed = System.currentTimeMillis() - turnStart;
        if (elapsed < minTurnDurationMillis) {
            try {
                long sleepTime = minTurnDurationMillis - elapsed;
                LOG.debug("Rate limiting: sleeping for {} ms", sleepTime);
                Thread.sleep(minTurnDurationMillis - elapsed);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.of("Chat interrupted.");
            }
        }
        return Optional.empty();
    }
    private void initializeHistory(String prompt, String systemContext) {
        // user task and mode we are currently operating in
        String taskMessage = "Task: " + prompt + "\n";
        taskMessage += roxyProjectService.getModeMessage();
        LOG.info("taskMessage: {}", taskMessage);
        // --- History Management (Index 0 is always System) ---
        if (history.isEmpty()) {
            history.add(Content.builder().role("user").parts(List.of(Part.builder().text(systemContext + "\n" + taskMessage).build())).build());
        } else {
            // Update System Prompt (Index 0) with latest known state
            history.set(0, Content.builder().role("user").parts(List.of(Part.builder().text(systemContext).build())).build());
            history.add(Content.builder().role("user").parts(List.of(Part.builder().text(taskMessage).build())).build());
        }
    }

    private GenerateContentConfig prepareConfig(Optional<ProjectCacheMeta> cacheMeta) {
        GenerateContentConfig.Builder configBuilder = GenerateContentConfig.builder();

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
        return configBuilder.build();
    }

    private Candidate processResponse(GenerateContentResponse response) {
        handleUsageUpdate(response);

        Optional<List<Candidate>> candidatesOpt = response.candidates();
        if (candidatesOpt.isEmpty() || candidatesOpt.get().isEmpty()) {
            throw new IllegalStateException("Error: No response candidates.");
        }
        return candidatesOpt.get().getFirst();
    }

    private boolean executeToolCalls(Content modelMessage, String projectRoot, Consumer<String> onStatusUpdate,
                                     List<Content> subsequentUserMessages, List<Part> functionResponseParts) {
        List<Part> parts = modelMessage.parts().orElse(Collections.emptyList());
        boolean hasFunctionCall = false;

        // handle 0-N function calls
        for (Part part : parts) {
            if (stopRequested) {
                LOG.info("Stopped by user.");
                return false;
            }
            Optional<FunctionCall> callOpt = part.functionCall();
            if (callOpt.isPresent()) {
                hasFunctionCall = true;
                FunctionCall call = callOpt.get();
                String fnName = call.name().orElse("unknown");
                Map<String, Object> fixedArgs = new HashMap<>(call.args().orElse(Collections.emptyMap()));
                Part responsePart = handleFunctionCall(projectRoot, fnName, fixedArgs, onStatusUpdate, subsequentUserMessages);
                functionResponseParts.add(responsePart);
            }
        }
        return hasFunctionCall;
    }

    protected void handleUsageUpdate(GenerateContentResponse response) {
        if (response.usageMetadata().isPresent()) {
            GenerateContentResponseUsageMetadata usage = response.usageMetadata().get();
            inTokens = usage.promptTokenCount().orElse(0);
            outTokens = usage.candidatesTokenCount().orElse(0);
            usageService.recordUsage(inTokens, outTokens);
        }
    }

    protected Part handleFunctionCall(String projectRoot, String fnName, Map<String, Object> fixedArgs, Consumer<String> onStatusUpdate, List<Content> subsequentUserMessages) {
        // Resolve relative paths in arguments
        if (fixedArgs.containsKey("path")) {
            String originalPath = (String) fixedArgs.get("path");
            if (!Paths.get(originalPath).isAbsolute()) {
                Path resolvedPath = Paths.get(projectRoot, originalPath);
                fixedArgs.put("path", resolvedPath.toString());
            }
        }
        if (onStatusUpdate != null) {
            String script = MapUtils.getString(fixedArgs, "script", "missing");
            // since we have moved to a script based model we just display the script
            onStatusUpdate.accept(script);
        }
        LOG.info("AI calling tool: {} with args {}", fnName, fixedArgs);
        String toolOutput;
        try {
            ToolDefinition toolDef = toolRegistry.getTool(fnName).orElseThrow(() -> new IllegalStateException("Tool not found: " + fnName));
            toolOutput = executionService.execute(toolDef, fixedArgs).get();
            LOG.info("Tool results: {} {}", fnName, toolOutput);
        } catch (Exception e) {
            toolOutput = "Error executing tool [" + fnName + "]: " + e.getMessage();
        }

        // Handle Blobs (e.g. screenshots as Data URIs)
        if (toolOutput != null && toolOutput.startsWith("data:")) {
            try {
                int commaIndex = toolOutput.indexOf(",");
                if (commaIndex > 0) {
                    String header = toolOutput.substring(0, commaIndex);
                    String base64Data = toolOutput.substring(commaIndex + 1);
                    String mimeType = header.substring(header.indexOf(":") + 1, header.indexOf(";"));

                    byte[] data = Base64.getDecoder().decode(base64Data);
                    Blob blob = Blob.builder().mimeType(mimeType).data(data).build();
                    subsequentUserMessages.add(Content.builder().role("user").parts(List.of(Part.builder().inlineData(blob).build(), Part.builder().text("Attachment captured.").build())).build());
                    toolOutput = "Attachment captured.";
                }
            } catch (Exception e) {
                LOG.error("Failed to parse Data URI", e);
                toolOutput = "Error parsing attachment: " + e.getMessage();
            }
        }

        if (toolOutput != null && toolOutput.length() > 20000) {
            LOG.warn("Truncating tool output for {}: {} characters", fnName, toolOutput.length());
            toolOutput = toolOutput.substring(0, 20000) + "\n\n[System: Output truncated due to length limits.]";
        }
        return Part.builder().functionResponse(FunctionResponse.builder().name(fnName).response(Map.of("result", toolOutput)).build()).build();
    }
}
