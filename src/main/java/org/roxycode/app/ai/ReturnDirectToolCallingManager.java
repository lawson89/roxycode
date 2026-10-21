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
        TurnToolContext turnContext =
                TurnToolContext.fromPrompt(prompt, planService.getCurrentPhase());
        String conversationId = turnContext.conversationId();
        WorkflowPhase phase = turnContext.phase();
        AtomicInteger turnCounter = turnContext.turnCounter();

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
        return postProcessToolExecutionResult(turnContext, chatResponse, result);
    }

    /**
     * Post-processes tool execution responses.
     *
     * <p>When a Spring AI tool is annotated with {@code returnDirect = true}, its serialized result
     * is returned directly to the caller instead of being sent back to the model. However, if a
     * {@code returnDirect} tool (such as {@code requestDevelopment} when no plan has been
     * submitted) fails validation and returns an {@code "Error: ..."} message via {@link
     * RoxyToolExecutionExceptionProcessor}, {@code effectiveReturnDirect} is overridden to {@code
     * false} so control returns to the LLM to self-correct.
     */
    private ToolExecutionResult postProcessToolExecutionResult(
            TurnToolContext turnContext, ChatResponse chatResponse, ToolExecutionResult result) {
        List<Message> history = result.conversationHistory();
        boolean returnDirect = result.returnDirect();
        boolean anyError = false;
        boolean historyModified = false;

        if (!history.isEmpty()
                && history.get(history.size() - 1) instanceof ToolResponseMessage toolMsg) {
            List<ToolResponseMessage.ToolResponse> responses = toolMsg.getResponses();
            List<ToolResponseMessage.ToolResponse> updatedResponses =
                    new ArrayList<>(responses.size());
            for (int responseIndex = 0; responseIndex < responses.size(); responseIndex++) {
                ToolResponseMessage.ToolResponse tr = responses.get(responseIndex);
                String unquoted = unquoteIfJsonString(tr.responseData());
                if (RoxyToolExecutionExceptionProcessor.isErrorResponse(unquoted)) {
                    anyError = true;
                }
                ToolResponseMessage.ToolResponse processed =
                        processSingleToolResponse(
                                tr, responseIndex, chatResponse, turnContext, returnDirect);
                if (processed != tr) {
                    historyModified = true;
                }
                updatedResponses.add(processed);
            }
            if (historyModified) {
                history = replaceLastToolResponseMessage(history, toolMsg, updatedResponses);
            }
        }

        // Guard: if any tool failed validation, suppress returnDirect so the LLM can self-correct.
        boolean effectiveReturnDirect = returnDirect && !anyError;
        if (effectiveReturnDirect != returnDirect || historyModified) {
            return ToolExecutionResult.builder()
                    .conversationHistory(history)
                    .returnDirect(effectiveReturnDirect)
                    .build();
        }
        return result;
    }

    /**
     * Unquotes the tool response if {@code returnDirect} is active and the call succeeded, and
     * publishes a synthetic {@link ToolExecutionEvent} for non-JEXL ({@link PlanService}) tools.
     */
    private ToolResponseMessage.ToolResponse processSingleToolResponse(
            ToolResponseMessage.ToolResponse tr,
            int responseIndex,
            ChatResponse chatResponse,
            TurnToolContext turnContext,
            boolean returnDirect) {
        String rawData = tr.responseData();
        String unquoted = unquoteIfJsonString(rawData);
        boolean failed = RoxyToolExecutionExceptionProcessor.isErrorResponse(unquoted);

        if (!JEXL_TOOL_NAME.equals(tr.name())) {
            publishPlanToolExecutionEvent(
                    tr, responseIndex, chatResponse, turnContext, unquoted, failed);
        }

        if (returnDirect && !failed && !unquoted.equals(rawData)) {
            return new ToolResponseMessage.ToolResponse(tr.id(), tr.name(), unquoted);
        }
        return tr;
    }

    /**
     * Formats {@code "planService.<toolName>(<args>)"} and publishes a {@link ToolExecutionEvent}
     * so dedicated {@link PlanService} tool invocations appear in the UI tool log.
     */
    private void publishPlanToolExecutionEvent(
            ToolResponseMessage.ToolResponse tr,
            int responseIndex,
            ChatResponse chatResponse,
            TurnToolContext turnContext,
            String unquotedResponse,
            boolean failed) {
        String args = findToolCallArguments(chatResponse, tr, responseIndex);
        String scriptDesc = "planService." + tr.name() + "(" + args + ")";
        eventPublisher.publishEvent(
                new ToolExecutionEvent(
                        turnContext.conversationId(),
                        scriptDesc,
                        failed ? null : unquotedResponse,
                        !failed,
                        failed
                                ? RoxyToolExecutionExceptionProcessor.stripErrorPrefix(
                                        unquotedResponse)
                                : null));
    }

    /**
     * Replaces the trailing {@link ToolResponseMessage} in the conversation history when tool
     * responses were unquoted for direct return.
     */
    private static List<Message> replaceLastToolResponseMessage(
            List<Message> history,
            ToolResponseMessage original,
            List<ToolResponseMessage.ToolResponse> updatedResponses) {
        List<Message> updatedHistory = new ArrayList<>(history);
        updatedHistory.set(
                updatedHistory.size() - 1,
                ToolResponseMessage.builder()
                        .responses(updatedResponses)
                        .metadata(original.getMetadata())
                        .build());
        return updatedHistory;
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
            ChatResponse chatResponse, ToolResponseMessage.ToolResponse tr, int responseIndex) {
        if (chatResponse != null
                && chatResponse.getResult() != null
                && chatResponse.getResult().getOutput() != null
                && chatResponse.getResult().getOutput().getToolCalls() != null) {
            List<AssistantMessage.ToolCall> toolCalls =
                    chatResponse.getResult().getOutput().getToolCalls();

            // Step 1: Non-blank ID match (with optional tool name verification)
            if (tr.id() != null && !tr.id().isBlank()) {
                for (AssistantMessage.ToolCall tc : toolCalls) {
                    if (tr.id().equals(tc.id())
                            && (tr.name() == null || tr.name().equals(tc.name()))) {
                        return tc.arguments() != null ? tc.arguments() : "";
                    }
                }
            }

            // Step 2: Positional index + name match
            if (responseIndex >= 0 && responseIndex < toolCalls.size()) {
                AssistantMessage.ToolCall indexed = toolCalls.get(responseIndex);
                if (tr.name() != null && tr.name().equals(indexed.name())) {
                    return indexed.arguments() != null ? indexed.arguments() : "";
                }
            }

            // Step 3: Fallback name match
            if (tr.name() != null) {
                for (AssistantMessage.ToolCall tc : toolCalls) {
                    if (tr.name().equals(tc.name())) {
                        return tc.arguments() != null ? tc.arguments() : "";
                    }
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
