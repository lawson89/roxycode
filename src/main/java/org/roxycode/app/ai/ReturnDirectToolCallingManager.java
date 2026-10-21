package org.roxycode.app.ai;

import java.util.ArrayList;
import java.util.List;
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
 * execution events, and post-processes {@code returnDirect} transition results.
 */
public class ReturnDirectToolCallingManager implements ToolCallingManager {

    private static final String AGENT_NAME = "Roxy";
    private static final String JEXL_TOOL_NAME = "executeJexl";
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
        TurnToolContext ctx = TurnToolContext.fromPrompt(prompt, planService.getCurrentPhase());
        String conversationId = ctx.conversationId();
        WorkflowPhase phase = ctx.phase();
        AtomicInteger turnCounter = ctx.turnCounter();

        int maxTurns =
                settingsService.getSettings() != null
                        ? settingsService.getSettings().getMaxAgentToolTurns()
                        : 0;
        if (maxTurns > 0 && turnCounter.get() >= maxTurns) {
            return buildMaxTurnsExceededResult(prompt, chatResponse, maxTurns);
        }

        int currentTurn = turnCounter.incrementAndGet();
        eventPublisher.publishEvent(
                new AgentTurnEvent(conversationId, AGENT_NAME, currentTurn, phase.name()));

        ToolExecutionResult result = delegate.executeToolCalls(prompt, chatResponse);
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
                boolean isError = RoxyToolExecutionExceptionProcessor.isErrorResponse(unquoted);
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
                                    isError
                                            ? RoxyToolExecutionExceptionProcessor.stripErrorPrefix(
                                                    unquoted)
                                            : null));
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

    private static ToolExecutionResult buildMaxTurnsExceededResult(
            Prompt prompt, ChatResponse chatResponse, int maxTurns) {
        String stopMsg = MAX_TURNS_EXCEEDED_PREFIX + maxTurns + ") exceeded.";
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
                            new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), stopMsg));
                }
            }
        }
        if (responses.isEmpty()) {
            responses.add(new ToolResponseMessage.ToolResponse("call-1", JEXL_TOOL_NAME, stopMsg));
        }
        history.add(ToolResponseMessage.builder().responses(responses).build());
        return ToolExecutionResult.builder()
                .conversationHistory(history)
                .returnDirect(true)
                .build();
    }
}
