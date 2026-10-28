package org.roxycode.app.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.lang3.StringUtils;
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

public class ReturnDirectToolCallingManager implements ToolCallingManager {

    private static final String AGENT_NAME = "Roxy";
    private static final String JEXL_TOOL_NAME = "executeJexl";
    public static final String MAX_TURNS_EXCEEDED_PREFIX = "Autonomous execution stopped: Maximum tool turns (";

    private final ToolCallingManager delegate;
    private final SettingsService settingsService;
    private final PlanService planService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public ReturnDirectToolCallingManager(ToolCallingManager delegate, SettingsService settingsService,
            PlanService planService, ApplicationEventPublisher eventPublisher) {
        this(delegate, settingsService, planService, eventPublisher, AiConfig.SHARED_OBJECT_MAPPER);
    }

    public ReturnDirectToolCallingManager(ToolCallingManager delegate, SettingsService settingsService,
            PlanService planService, ApplicationEventPublisher eventPublisher, ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.settingsService = settingsService;
        this.planService = planService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper != null ? objectMapper : AiConfig.SHARED_OBJECT_MAPPER;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        TurnToolContext turnContext = TurnToolContext.fromPrompt(prompt, planService.getCurrentPhase());
        AtomicInteger turnCounter = turnContext.turnCounter();

        if (chatResponse != null && chatResponse.getResult() != null && chatResponse.getResult().getOutput() != null) {
            String text = chatResponse.getResult().getOutput().getText();
            if (StringUtils.isNotBlank(text)) {
                turnContext.textAccumulator().append(text).append("\n\n");
            }
        }

        int maxTurns = settingsService.getSettings() != null ? settingsService.getSettings().getMaxAgentToolTurns() : 0;
        if (maxTurns > 0 && turnCounter.get() >= maxTurns) {
            return buildMaxTurnsExceededResult(prompt, chatResponse, maxTurns);
        }

        int currentTurn = turnCounter.incrementAndGet();
        eventPublisher.publishEvent(
                new AgentTurnEvent(turnContext.conversationId(), AGENT_NAME, currentTurn, turnContext.phase().name()));

        return postProcessToolExecutionResult(turnContext, chatResponse,
                delegate.executeToolCalls(prompt, chatResponse));
    }

    private ToolExecutionResult postProcessToolExecutionResult(TurnToolContext turnContext, ChatResponse chatResponse,
            ToolExecutionResult result) {
        List<Message> history = result.conversationHistory();
        boolean returnDirect = result.returnDirect();
        boolean anyError = false;
        boolean historyModified = false;

        if (!history.isEmpty() && history.get(history.size() - 1) instanceof ToolResponseMessage toolMsg) {
            List<ToolResponseMessage.ToolResponse> responses = toolMsg.getResponses();
            List<ToolResponseMessage.ToolResponse> updatedResponses = new ArrayList<>(responses.size());
            for (int i = 0; i < responses.size(); i++) {
                ToolResponseMessage.ToolResponse tr = responses.get(i);
                String unquoted = unquoteIfJsonString(tr.responseData());
                if (RoxyToolExecutionExceptionProcessor.isErrorResponse(unquoted)) {
                    anyError = true;
                }
                ToolResponseMessage.ToolResponse processed = processSingleToolResponse(tr, i, chatResponse, turnContext,
                        returnDirect);
                if (!processed.equals(tr)) {
                    historyModified = true;
                }
                updatedResponses.add(processed);
            }
            if (historyModified) {
                history = replaceLastToolResponseMessage(history, toolMsg, updatedResponses);
            }
        }

        boolean effectiveReturnDirect = returnDirect && !anyError;
        if (effectiveReturnDirect != returnDirect || historyModified) {
            return ToolExecutionResult.builder().conversationHistory(history).returnDirect(effectiveReturnDirect)
                    .build();
        }
        return result;
    }

    private ToolResponseMessage.ToolResponse processSingleToolResponse(ToolResponseMessage.ToolResponse tr,
            int responseIndex, ChatResponse chatResponse, TurnToolContext turnContext, boolean returnDirect) {
        String rawData = tr.responseData();
        String unquoted = unquoteIfJsonString(rawData);
        boolean failed = RoxyToolExecutionExceptionProcessor.isErrorResponse(unquoted);

        if (!JEXL_TOOL_NAME.equals(tr.name())) {
            String args = findToolCallArguments(chatResponse, tr, responseIndex);
            eventPublisher.publishEvent(new ToolExecutionEvent(turnContext.conversationId(),
                    "planService." + tr.name() + "(" + args + ")", failed ? null : unquoted, !failed,
                    failed ? RoxyToolExecutionExceptionProcessor.stripErrorPrefix(unquoted) : null));
        }
        return (returnDirect && !failed && !unquoted.equals(rawData))
                ? new ToolResponseMessage.ToolResponse(tr.id(), tr.name(), unquoted)
                : tr;
    }

    private static List<Message> replaceLastToolResponseMessage(List<Message> history, ToolResponseMessage original,
            List<ToolResponseMessage.ToolResponse> updatedResponses) {
        List<Message> updatedHistory = new ArrayList<>(history);
        updatedHistory.set(updatedHistory.size() - 1,
                ToolResponseMessage.builder().responses(updatedResponses).metadata(original.getMetadata()).build());
        return updatedHistory;
    }

    private String unquoteIfJsonString(String raw) {
        if (raw != null && raw.length() >= 2 && raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"') {
            try {
                return objectMapper.readValue(raw, String.class);
            } catch (Exception ignored) {
            }
        }
        return raw;
    }

    private static String findToolCallArguments(ChatResponse chatResponse, ToolResponseMessage.ToolResponse tr,
            int responseIndex) {
        if (chatResponse == null || chatResponse.getResult() == null || chatResponse.getResult().getOutput() == null
                || chatResponse.getResult().getOutput().getToolCalls() == null) {
            return "";
        }
        List<AssistantMessage.ToolCall> toolCalls = chatResponse.getResult().getOutput().getToolCalls();
        if (StringUtils.isNotBlank(tr.id())) {
            for (AssistantMessage.ToolCall tc : toolCalls) {
                if (tr.id().equals(tc.id()) && (tr.name() == null || tr.name().equals(tc.name()))) {
                    return StringUtils.defaultString(tc.arguments());
                }
            }
        }
        if (responseIndex >= 0 && responseIndex < toolCalls.size()) {
            AssistantMessage.ToolCall indexed = toolCalls.get(responseIndex);
            if (tr.name() != null && tr.name().equals(indexed.name())) {
                return StringUtils.defaultString(indexed.arguments());
            }
        }
        if (tr.name() != null) {
            for (AssistantMessage.ToolCall tc : toolCalls) {
                if (tr.name().equals(tc.name())) {
                    return StringUtils.defaultString(tc.arguments());
                }
            }
        }
        return "";
    }

    private static ToolExecutionResult buildMaxTurnsExceededResult(Prompt prompt, ChatResponse chatResponse,
            int maxTurns) {
        String stopMsg = MAX_TURNS_EXCEEDED_PREFIX + maxTurns + ") exceeded.";
        List<Message> history = prompt != null && prompt.getInstructions() != null
                ? new ArrayList<>(prompt.getInstructions())
                : new ArrayList<>();
        AssistantMessage assistantMsg = chatResponse != null && chatResponse.getResult() != null
                ? chatResponse.getResult().getOutput()
                : null;
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        if (assistantMsg != null) {
            history.add(assistantMsg);
            if (assistantMsg.getToolCalls() != null) {
                for (AssistantMessage.ToolCall tc : assistantMsg.getToolCalls()) {
                    responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), stopMsg));
                }
            }
        }
        if (responses.isEmpty()) {
            responses.add(new ToolResponseMessage.ToolResponse("call-1", JEXL_TOOL_NAME, stopMsg));
        }
        history.add(ToolResponseMessage.builder().responses(responses).build());
        return ToolExecutionResult.builder().conversationHistory(history).returnDirect(true).build();
    }
}
