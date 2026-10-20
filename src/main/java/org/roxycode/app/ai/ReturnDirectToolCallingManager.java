package org.roxycode.app.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.events.ToolExecutionEvent;
import org.roxycode.app.service.SettingsService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Decorator {@link ToolCallingManager} that enforces maximum tool turns, publishes turn and tool
 * execution events, handles tool execution errors, and post-processes {@code returnDirect}
 * transition results.
 */
public class ReturnDirectToolCallingManager implements ToolCallingManager {

    public static final String TOOL_CONTEXT_CONVERSATION_ID = JexlTool.TOOL_CONTEXT_CONVERSATION_ID;
    public static final String TOOL_CONTEXT_PHASE = JexlTool.TOOL_CONTEXT_PHASE;
    public static final String TOOL_CONTEXT_TURN_COUNTER = "turnCounter";

    private static final String DEFAULT_CONVERSATION_ID = "default";
    private static final String AGENT_NAME = "Roxy";
    private static final String JEXL_TOOL_NAME = "executeJexl";
    private static final String ERROR_PREFIX = RoxyToolExecutionExceptionProcessor.ERROR_PREFIX;
    private static final String MAX_TURNS_EXCEEDED_PREFIX =
            "Autonomous execution stopped: Maximum tool turns (";

    private final ToolCallingManager delegate;
    private final SettingsService settingsService;
    private final PlanService planService;
    private final ApplicationEventPublisher eventPublisher;

    public ReturnDirectToolCallingManager(
            SettingsService settingsService,
            PlanService planService,
            ApplicationEventPublisher eventPublisher) {
        this(
                ToolCallingManager.builder()
                        .toolExecutionExceptionProcessor(new RoxyToolExecutionExceptionProcessor())
                        .build(),
                settingsService,
                planService,
                eventPublisher);
    }

    public ReturnDirectToolCallingManager(
            ToolCallingManager delegate,
            SettingsService settingsService,
            PlanService planService,
            ApplicationEventPublisher eventPublisher) {
        this.delegate = delegate;
        this.settingsService = settingsService;
        this.planService = planService;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        Map<String, Object> toolContext = extractToolContext(prompt);
        String conversationId = resolveConversationId(toolContext);
        WorkflowPhase phase = resolvePhase(toolContext);
        AtomicInteger turnCounter = resolveTurnCounter(toolContext);

        int maxTurns =
                settingsService.getSettings() != null
                        ? settingsService.getSettings().getMaxAgentToolTurns()
                        : 0;
        if (maxTurns > 0 && turnCounter.get() >= maxTurns) {
            String stopMsg = formatMaxTurnsExceededMessage(maxTurns);
            return buildSyntheticToolResult(prompt, chatResponse, stopMsg, true);
        }

        int currentTurn = turnCounter.incrementAndGet();
        eventPublisher.publishEvent(
                new AgentTurnEvent(conversationId, AGENT_NAME, currentTurn, phase.name()));

        ToolExecutionResult result;
        try {
            result = delegate.executeToolCalls(prompt, chatResponse);
        } catch (Exception e) {
            String formattedErr = RoxyToolExecutionExceptionProcessor.formatException(e);
            result = buildSyntheticToolResult(prompt, chatResponse, formattedErr, false);
        }

        return postProcessToolExecutionResult(conversationId, chatResponse, result);
    }

    private ToolExecutionResult postProcessToolExecutionResult(
            String conversationId, ChatResponse chatResponse, ToolExecutionResult result) {
        List<Message> history = new ArrayList<>(result.conversationHistory());
        boolean hasValidationError = false;
        boolean historyModified = false;

        if (!history.isEmpty()
                && history.get(history.size() - 1) instanceof ToolResponseMessage toolMsg) {
            List<ToolResponseMessage.ToolResponse> updatedResponses = new ArrayList<>();
            for (ToolResponseMessage.ToolResponse tr : toolMsg.getResponses()) {
                String rawData = tr.responseData();
                String unquoted = unquoteIfJsonString(rawData);
                boolean isError = isErrorResponse(unquoted);
                if (isError) {
                    hasValidationError = true;
                }

                if (!JEXL_TOOL_NAME.equals(tr.name())) {
                    String args = findToolCallArguments(chatResponse, tr);
                    String scriptDesc = "planService." + tr.name() + "(" + args + ")";
                    eventPublisher.publishEvent(
                            new ToolExecutionEvent(
                                    conversationId,
                                    scriptDesc,
                                    isError ? null : unquoted,
                                    !isError,
                                    isError ? stripErrorPrefix(unquoted) : null));
                }

                if (result.returnDirect() && !isError && !unquoted.equals(rawData)) {
                    updatedResponses.add(
                            new ToolResponseMessage.ToolResponse(tr.id(), tr.name(), unquoted));
                    historyModified = true;
                } else {
                    updatedResponses.add(tr);
                }
            }
            if (historyModified) {
                history.set(
                        history.size() - 1,
                        ToolResponseMessage.builder().responses(updatedResponses).build());
            }
        }

        boolean effectiveReturnDirect = result.returnDirect() && !hasValidationError;
        if (effectiveReturnDirect != result.returnDirect() || historyModified) {
            return ToolExecutionResult.builder()
                    .conversationHistory(history)
                    .returnDirect(effectiveReturnDirect)
                    .build();
        }
        return result;
    }

    private static boolean isErrorResponse(String responseText) {
        if (responseText == null) {
            return false;
        }
        String trimmed = responseText.trim();
        return trimmed.startsWith(ERROR_PREFIX) || trimmed.startsWith("Method '");
    }

    private static String stripErrorPrefix(String responseText) {
        if (responseText == null) {
            return "Unknown error";
        }
        String trimmed = responseText.trim();
        if (trimmed.startsWith(ERROR_PREFIX)) {
            return trimmed.substring(ERROR_PREFIX.length()).trim();
        }
        return trimmed;
    }

    private static String unquoteIfJsonString(String raw) {
        if (raw != null
                && raw.length() >= 2
                && raw.charAt(0) == '"'
                && raw.charAt(raw.length() - 1) == '"') {
            try {
                return AiConfig.SHARED_OBJECT_MAPPER.readValue(raw, String.class);
            } catch (Exception ignored) {
                // Keep original if not valid JSON string literal
            }
        }
        return raw;
    }

    private static String findToolCallArguments(
            ChatResponse chatResponse, ToolResponseMessage.ToolResponse tr) {
        if (chatResponse != null
                && chatResponse.getResult() != null
                && chatResponse.getResult().getOutput() != null
                && chatResponse.getResult().getOutput().getToolCalls() != null) {
            for (AssistantMessage.ToolCall tc :
                    chatResponse.getResult().getOutput().getToolCalls()) {
                if ((tr.id() != null && tr.id().equals(tc.id()))
                        || (tr.name() != null && tr.name().equals(tc.name()))) {
                    return tc.arguments() != null ? tc.arguments() : "";
                }
            }
        }
        return "";
    }

    private static ToolExecutionResult buildSyntheticToolResult(
            Prompt prompt, ChatResponse chatResponse, String responseData, boolean returnDirect) {
        List<Message> history =
                prompt != null && prompt.getInstructions() != null
                        ? new ArrayList<>(prompt.getInstructions())
                        : new ArrayList<>();
        AssistantMessage assistantMsg =
                chatResponse != null && chatResponse.getResult() != null
                        ? chatResponse.getResult().getOutput()
                        : null;
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        if (assistantMsg != null) {
            history.add(assistantMsg);
            if (assistantMsg.getToolCalls() != null && !assistantMsg.getToolCalls().isEmpty()) {
                for (AssistantMessage.ToolCall tc : assistantMsg.getToolCalls()) {
                    responses.add(
                            new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), responseData));
                }
            }
        }
        if (responses.isEmpty()) {
            responses.add(
                    new ToolResponseMessage.ToolResponse("call-1", JEXL_TOOL_NAME, responseData));
        }
        history.add(ToolResponseMessage.builder().responses(responses).build());
        return ToolExecutionResult.builder()
                .conversationHistory(history)
                .returnDirect(returnDirect)
                .build();
    }

    private static Map<String, Object> extractToolContext(Prompt prompt) {
        if (prompt != null
                && prompt.getOptions() instanceof ToolCallingChatOptions options
                && options.getToolContext() != null) {
            return options.getToolContext();
        }
        return Map.of();
    }

    private static String resolveConversationId(Map<String, Object> toolContext) {
        if (toolContext.get(TOOL_CONTEXT_CONVERSATION_ID) instanceof String s && !s.isBlank()) {
            return s;
        }
        return DEFAULT_CONVERSATION_ID;
    }

    private WorkflowPhase resolvePhase(Map<String, Object> toolContext) {
        if (toolContext.get(TOOL_CONTEXT_PHASE) instanceof WorkflowPhase wp) {
            return wp;
        }
        WorkflowPhase current = planService.getCurrentPhase();
        return current != null ? current : WorkflowPhase.EXPLORE;
    }

    private static AtomicInteger resolveTurnCounter(Map<String, Object> toolContext) {
        if (toolContext.get(TOOL_CONTEXT_TURN_COUNTER) instanceof AtomicInteger counter) {
            return counter;
        }
        return new AtomicInteger(0);
    }

    private static String formatMaxTurnsExceededMessage(int maxTurns) {
        return MAX_TURNS_EXCEEDED_PREFIX + maxTurns + ") exceeded.";
    }
}
