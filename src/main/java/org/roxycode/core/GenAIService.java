package org.roxycode.core;

import com.google.genai.Client;
import com.google.genai.types.*;
import jakarta.inject.Singleton;
import org.roxycode.core.tools.ToolDefinition;
import org.roxycode.core.tools.ToolExecutionService;
import org.roxycode.core.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

        // 1. Prepare Tools for Gemini
        if (toolRegistry.getTool("read_file").isEmpty()) {
            toolRegistry.loadTools("src/main/resources/tools");
        }

        List<FunctionDeclaration> functionDeclarations = new ArrayList<>();

        for (String toolName : List.of("read_file", "write_file", "run_tests")) {
            toolRegistry.getTool(toolName).ifPresent(td -> {

                // Fix 1: Build properties Map separately
                Map<String, Schema> properties = new HashMap<>();
                td.getParameters().forEach(p -> {
                    properties.put(p.getName(), Schema.builder()
                            .type("STRING")
                            .description(p.getDescription())
                            .build());
                });

                // Fix: Pass map to .properties()
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

        // 2. Initial Message
        List<Content> history = new ArrayList<>();
        history.add(Content.builder()
                .role("user")
                .parts(List.of(Part.builder().text("Project Context:\n" + projectContext + "\n\nTask: " + prompt).build()))
                .build());

        // 3. The Loop
        int turns = 0;
        int maxTurns = 10;

        while (turns++ < maxTurns) {
            GenerateContentConfig config = GenerateContentConfig.builder()
                    .tools(List.of(toolConfig))
                    .build();

            GenerateContentResponse response = getClient().models.generateContent(
                    "gemini-2.0-flash-exp",
                    history,
                    config
            );

            Optional<List<Candidate>> candidatesOpt = response.candidates();
            if (candidatesOpt.isEmpty() || candidatesOpt.get().isEmpty()) {
                return "Error: No response candidates from AI.";
            }

            // Fix 2: Unwrap Optional content
            Content modelMessage = candidatesOpt.get().get(0).content().orElseThrow(() ->
                    new RuntimeException("Received candidate with no content")
            );

            history.add(modelMessage);

            List<Part> parts = modelMessage.parts().orElse(Collections.emptyList());
            boolean hasFunctionCall = false;

            for (Part part : parts) {
                Optional<FunctionCall> callOpt = part.functionCall();

                if (callOpt.isPresent()) {
                    hasFunctionCall = true;
                    FunctionCall call = callOpt.get();

                    // Fix 3: Unwrap Optional name
                    String fnName = call.name().orElse("unknown_tool");
                    Map<String, Object> args = call.args().orElse(Collections.emptyMap());

                    LOG.info("AI calling tool: {} with args {}", fnName, args);

                    String toolOutput;
                    try {
                        ToolDefinition toolDef = toolRegistry.getTool(fnName).orElseThrow();
                        toolOutput = executionService.execute(toolDef, args).get();
                    } catch (Exception e) {
                        toolOutput = "Error executing tool: " + e.getMessage();
                    }

                    LOG.info("Tool output: {}", toolOutput);

                    history.add(Content.builder()
                            .role("function")
                            .parts(List.of(Part.builder()
                                    .functionResponse(FunctionResponse.builder()
                                            .name(fnName)
                                            .response(Map.of("result", toolOutput))
                                            .build())
                                    .build()))
                            .build());
                }
            }

            if (!hasFunctionCall) {
                return parts.stream()
                        .map(p -> p.text().orElse(""))
                        .collect(Collectors.joining(""));
            }
        }

        return "Max turns reached.";
    }
}