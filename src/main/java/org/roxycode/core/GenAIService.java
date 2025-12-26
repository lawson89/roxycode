package org.roxycode.core;

import com.google.genai.Client;
import com.google.genai.types.*;
import jakarta.inject.Singleton;
import org.roxycode.core.context.ContextRegistry;
import org.roxycode.core.tools.ToolDefinition;
import org.roxycode.core.tools.ToolExecutionService;
import org.roxycode.core.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private Client client;

    // Cache function declarations to avoid re-scanning every chat turn
    private final List<FunctionDeclaration> cachedFunctions = new ArrayList<>();
    private Path cachedRoxyHome; // Track where we loaded from

    public GenAIService(SettingsService settingsService,
                        ToolRegistry toolRegistry,
                        ToolExecutionService executionService,
                        Sandbox sandbox,
                        ContextRegistry contextRegistry) {
        this.settingsService = settingsService;
        this.toolRegistry = toolRegistry;
        this.executionService = executionService;
        this.sandbox = sandbox;
        this.contextRegistry = contextRegistry;
    }

    private Client getClient() {
        if (client == null) {
            String key = settingsService.getGeminiApiKey();
            if (key == null || key.isEmpty()) {
                throw new IllegalStateException("Gemini API Key not found in Settings.");
            }
            client = Client.builder().apiKey(key).build();
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

        // Ensure structure exists
        try {
            Files.createDirectories(roxyHome.resolve("tools"));
            Files.createDirectories(roxyHome.resolve("context"));
        } catch (IOException e) {
            LOG.warn("Failed to create roxy_home directories", e);
        }

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
                availableToolNames = stream
                        .filter(p -> p.toString().endsWith(".toml"))
                        .map(p -> p.getFileName().toString().replace(".toml", ""))
                        .collect(Collectors.toList());
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
                    td.getParameters().forEach(p -> {
                        properties.put(p.getName(), Schema.builder()
                                .type("STRING") // Simplifying types for MVP
                                .description(p.getDescription())
                                .build());
                    });
                }
                Schema schema = Schema.builder().type("OBJECT").properties(properties).build();
                cachedFunctions.add(FunctionDeclaration.builder()
                        .name(toolName)
                        .description(td.getDescription())
                        .parameters(schema)
                        .build());
            });
        }
        LOG.info("✅ Knowledge Refresh Complete. Loaded {} tools.", cachedFunctions.size());
    }

    public String chat(String prompt, String projectRoot) {
        // 1. CONFIGURE SANDBOX (Always do this per chat to ensure safety)
        sandbox.setRoot(projectRoot);

        // 2. Build Initial Prompt
        String contextMenu = contextRegistry.getContextMenu();
        String systemPrompt = "Project Root: " + projectRoot + "\n" +
                              "Roxy Home: " + (cachedRoxyHome != null ? cachedRoxyHome : "Not loaded") + "\n" +
                              contextMenu + "\n" +
                              "Task: " + prompt;

        List<Content> history = new ArrayList<>();
        history.add(Content.builder()
                .role("user")
                .parts(List.of(Part.builder().text(systemPrompt).build()))
                .build());

        // 3. Conversation Loop
        int turns = 0;
        int maxTurns = 15;

        while (turns++ < maxTurns) {
            LOG.info("Turn {}: Sending message to model...", turns);

            // USE CACHED FUNCTIONS
            GenerateContentConfig.Builder configBuilder = GenerateContentConfig.builder();
            if (!cachedFunctions.isEmpty()) {
                Tool toolConfig = Tool.builder()
                        .functionDeclarations(cachedFunctions)
                        .build();
                configBuilder.tools(List.of(toolConfig));
            }

            GenerateContentResponse response = getClient().models.generateContent(
                    "gemini-2.0-flash-exp",
                    history,
                    configBuilder.build()
            );

            Optional<List<Candidate>> candidatesOpt = response.candidates();
            if (candidatesOpt.isEmpty() || candidatesOpt.get().isEmpty()) {
                return "Error: No response candidates.";
            }

            Content modelMessage = candidatesOpt.get().get(0).content().orElseThrow();
            history.add(modelMessage);

            List<Part> parts = modelMessage.parts().orElse(Collections.emptyList());

            // --- PARALLEL FUNCTION HANDLING ---
            List<Part> functionResponseParts = new ArrayList<>();
            List<Content> subsequentUserMessages = new ArrayList<>();
            boolean hasFunctionCall = false;

            for (Part part : parts) {
                Optional<FunctionCall> callOpt = part.functionCall();

                if (callOpt.isPresent()) {
                    hasFunctionCall = true;
                    FunctionCall call = callOpt.get();
                    String fnName = call.name().orElse("unknown");
                    Map<String, Object> originalArgs = call.args().orElse(Collections.emptyMap());

                    // Path Resolution Logic
                    Map<String, Object> fixedArgs = new HashMap<>(originalArgs);
                    if (fixedArgs.containsKey("path")) {
                        String originalPath = (String) fixedArgs.get("path");
                        if (!Paths.get(originalPath).isAbsolute()) {
                            Path resolvedPath = Paths.get(projectRoot, originalPath);
                            fixedArgs.put("path", resolvedPath.toString());
                        }
                    }

                    LOG.info("AI calling tool: {} with args {}", fnName, fixedArgs);
                    String toolOutput;
                    try {
                        ToolDefinition toolDef = toolRegistry.getTool(fnName).orElseThrow();
                        toolOutput = executionService.execute(toolDef, fixedArgs).get();
                    } catch (Exception e) {
                        toolOutput = "Error: " + e.getMessage();
                    }
                    LOG.info("Tool output: {}", toolOutput);

                    if (toolOutput.endsWith(".png") && Files.exists(Paths.get(toolOutput))) {
                        try {
                            byte[] imageBytes = Files.readAllBytes(Paths.get(toolOutput));
                            Blob imageBlob = Blob.builder().mimeType("image/png").data(imageBytes).build();
                            subsequentUserMessages.add(Content.builder().role("user").parts(List.of(Part.builder().inlineData(imageBlob).text("Screenshot captured.").build())).build());
                            toolOutput = "Screenshot captured.";
                        } catch (Exception e) { LOG.error("Image load fail", e); }
                    }

                    functionResponseParts.add(Part.builder()
                            .functionResponse(FunctionResponse.builder()
                                    .name(fnName)
                                    .response(Map.of("result", toolOutput))
                                    .build())
                            .build());
                }
            }

            if (hasFunctionCall) {
                if (!functionResponseParts.isEmpty()) {
                    history.add(Content.builder().role("function").parts(functionResponseParts).build());
                }
                history.addAll(subsequentUserMessages);
                continue;
            }

            return parts.stream().map(p -> p.text().orElse("")).collect(Collectors.joining(""));
        }

        return "Max turns reached.";
    }
}