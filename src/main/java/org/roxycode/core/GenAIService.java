package org.roxycode.core;

import com.google.genai.Client;
import com.google.genai.types.*;
import jakarta.inject.Singleton;
import org.roxycode.core.tools.ToolDefinition;
import org.roxycode.core.tools.ToolExecutionService;
import org.roxycode.core.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Singleton
public class GenAIService {
    private static final Logger LOG = LoggerFactory.getLogger(GenAIService.class);

    private final SettingsService settingsService;
    private final ToolRegistry toolRegistry;
    private final ToolExecutionService executionService;
    private Client client;

    public GenAIService(SettingsService settingsService,
                        ToolRegistry toolRegistry,
                        ToolExecutionService executionService) {
        this.settingsService = settingsService;
        this.toolRegistry = toolRegistry;
        this.executionService = executionService;
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

    public String chat(String prompt, String projectContext) {
        LOG.info("Starting chat with prompt: {}", prompt);

        // 1. Prepare Tools
        if (toolRegistry.getTool("read_file").isEmpty()) {
            // Load default tools if missing
            toolRegistry.loadTools("src/main/resources/tools");
        }

        List<FunctionDeclaration> functionDeclarations = new ArrayList<>();

        // Scan standard tools
        for (String toolName : List.of("read_file", "write_file", "run_tests", "launch_preview")) {
            toolRegistry.getTool(toolName).ifPresent(td -> {

                Map<String, Schema> properties = new HashMap<>();
                if (td.getParameters() != null) {
                    td.getParameters().forEach(p -> {
                        properties.put(p.getName(), Schema.builder()
                                .type("STRING")
                                .description(p.getDescription())
                                .build());
                    });
                }

                Schema schema = Schema.builder()
                        .type("OBJECT")
                        .properties(properties)
                        .build();

                functionDeclarations.add(FunctionDeclaration.builder()
                        .name(toolName)
                        .description(td.getDescription())
                        .parameters(schema)
                        .build());
            });
        }

        Tool toolConfig = Tool.builder()
                .functionDeclarations(functionDeclarations)
                .build();

        // 2. Initial History
        List<Content> history = new ArrayList<>();
        history.add(Content.builder()
                .role("user")
                .parts(List.of(Part.builder().text("Project Context:\n" + projectContext + "\n\nTask: " + prompt).build()))
                .build());

        // 3. The Conversation Loop
        int turns = 0;
        int maxTurns = 15;

        while (turns++ < maxTurns) {
            GenerateContentConfig config = GenerateContentConfig.builder()
                    .tools(List.of(toolConfig))
                    .build();

            // Call Model
            GenerateContentResponse response = getClient().models.generateContent(
                    "gemini-2.0-flash-exp",
                    history,
                    config
            );

            Optional<List<Candidate>> candidatesOpt = response.candidates();
            if (candidatesOpt.isEmpty() || candidatesOpt.get().isEmpty()) {
                return "Error: No response candidates from AI.";
            }

            // Get Content
            Content modelMessage = candidatesOpt.get().get(0).content().orElseThrow(() ->
                    new RuntimeException("Received candidate with no content")
            );

            history.add(modelMessage);

            // Process Parts
            List<Part> parts = modelMessage.parts().orElse(Collections.emptyList());
            boolean hasFunctionCall = false;

            for (Part part : parts) {
                Optional<FunctionCall> callOpt = part.functionCall();

                if (callOpt.isPresent()) {
                    hasFunctionCall = true;
                    FunctionCall call = callOpt.get();

                    String fnName = call.name().orElse("unknown");
                    Map<String, Object> args = call.args().orElse(Collections.emptyMap());

                    LOG.info("AI calling tool: {} with args {}", fnName, args);

                    // Execute
                    String toolOutput;
                    try {
                        ToolDefinition toolDef = toolRegistry.getTool(fnName).orElseThrow();
                        toolOutput = executionService.execute(toolDef, args).get();
                    } catch (Exception e) {
                        toolOutput = "Error executing tool: " + e.getMessage();
                    }
                    LOG.info("Tool output: {}", toolOutput);

                    // --- Handle Multimodal Output (Screenshots) ---
                    if (toolOutput.endsWith(".png") && Files.exists(Paths.get(toolOutput))) {
                        try {
                            byte[] imageBytes = Files.readAllBytes(Paths.get(toolOutput));

                            // 1. Respond to function call with text summary
                            Part functionResponsePart = Part.builder()
                                    .functionResponse(FunctionResponse.builder()
                                            .name(fnName)
                                            .response(Map.of("result", "Screenshot captured. See next message."))
                                            .build())
                                    .build();

                            history.add(Content.builder()
                                    .role("function")
                                    .parts(List.of(functionResponsePart))
                                    .build());

                            // 2. Inject Image as User Observation
                            Blob imageBlob = Blob.builder()
                                    .mimeType("image/png")
                                    .data(imageBytes)
                                    .build();

                            history.add(Content.builder()
                                    .role("user")
                                    .parts(List.of(Part.builder()
                                            .inlineData(imageBlob)
                                            .text("I have captured this screenshot of the application. Please analyze it.")
                                            .build()))
                                    .build());

                            // Continue loop immediately so we don't double-add
                            continue;

                        } catch (Exception e) {
                            LOG.error("Failed to attach image", e);
                            toolOutput += " (Failed to load image bytes)";
                        }
                    }

                    // --- Standard Text Response ---
                    Part responsePart = Part.builder()
                            .functionResponse(FunctionResponse.builder()
                                    .name(fnName)
                                    .response(Map.of("result", toolOutput))
                                    .build())
                            .build();

                    history.add(Content.builder()
                            .role("function")
                            .parts(List.of(responsePart))
                            .build());
                }
            }

            if (!hasFunctionCall) {
                // Return final text
                return parts.stream()
                        .map(p -> p.text().orElse(""))
                        .collect(Collectors.joining(""));
            }
        }

        return "Max turns reached.";
    }
}