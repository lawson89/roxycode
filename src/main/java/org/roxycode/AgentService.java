package org.roxycode;

import com.google.genai.Client;
import com.google.genai.types.*;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Singleton
public class AgentService {
    private static final Logger LOG = LoggerFactory.getLogger(AgentService.class);
    private final ConfigService configService;
    private final JsExecutionService jsExecutionService;
    private Client client;

    public AgentService(ConfigService configService, JsExecutionService jsExecutionService) {
        this.configService = configService;
        this.jsExecutionService = jsExecutionService;
    }

    private synchronized Client getClient() {
        if (client == null) {
            client = Client.builder()
                    .apiKey(configService.getApiKey())
                    .build();
        }
        return client;
    }

    public ChatResult chat(String prompt) {
        try {
            List<Content> history = new ArrayList<>();
            List<ToolExecution> executions = new ArrayList<>();
            
            history.add(Content.builder()
                    .role("user")
                    .parts(List.of(Part.builder().text(prompt).build()))
                    .build());

            Tool tool = Tool.builder()
                    .functionDeclarations(List.of(FunctionDeclaration.builder()
                            .name("execute_javascript")
                            .description("Executes a string of JavaScript in a secure sandbox. Use console.log for output.")
                            .parameters(Schema.builder()
                                    .type("OBJECT")
                                    .properties(Map.of("code", Schema.builder()
                                            .type("STRING")
                                            .description("The JavaScript code to execute")
                                            .build()))
                                    .required(List.of("code"))
                                    .build())
                            .build()))
                    .build();

            GenerateContentConfig config = GenerateContentConfig.builder()
                    .tools(List.of(tool))
                    .build();

            for (int i = 0; i < 5; i++) {
                GenerateContentResponse response = getClient().models.generateContent(
                        configService.getModelName(),
                        history,
                        config
                );

                Optional<List<Candidate>> candidates = response.candidates();
                if (candidates.isEmpty() || candidates.get().isEmpty()) {
                    return new ChatResult("Error: No candidates returned from model.", executions);
                }

                Content responseContent = candidates.get().get(0).content().orElseThrow();
                history.add(responseContent);

                List<FunctionCall> functionCalls = response.functionCalls();
                if (functionCalls == null || functionCalls.isEmpty()) {
                    return new ChatResult(response.text(), executions);
                }

                List<Part> toolResponseParts = new ArrayList<>();
                for (FunctionCall call : functionCalls) {
                    String functionName = call.name().orElse("");
                    if ("execute_javascript".equals(functionName)) {
                        Map<String, Object> args = call.args().orElse(Map.of());
                        String code = (String) args.get("code");
                        
                        LOG.info("Agent requesting JS execution: {}", code);
                        
                        JsExecutionResult result = jsExecutionService.execute(code);
                        
                        // Track execution for UI
                        String combinedOutput = (result.logs() + "\n" + (result.result() != null ? result.result() : "")).trim();
                        if (!result.success() && result.error() != null) {
                            combinedOutput += "\nError: " + result.error();
                        }
                        executions.add(new ToolExecution(code, combinedOutput));
                        
                        toolResponseParts.add(Part.builder()
                                .functionResponse(FunctionResponse.builder()
                                        .name(functionName)
                                        .response(Map.of(
                                                "success", result.success(),
                                                "result", result.result() != null ? result.result() : "",
                                                "error", result.error() != null ? result.error() : "",
                                                "logs", result.logs() != null ? result.logs() : ""
                                        ))
                                        .build())
                                .build());
                    }
                }
                history.add(Content.builder().role("tool").parts(toolResponseParts).build());
            }

            return new ChatResult("Error: Maximum tool call iterations reached.", executions);

        } catch (Exception e) {
            LOG.error("Failed to chat with Gemini: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to chat with Gemini: " + e.getMessage(), e);
        }
    }
}
