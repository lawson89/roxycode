package org.roxycode.app.ai;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * Encapsulates per-turn tool context parameters (conversation ID, workflow phase, and turn counter)
 * passed between {@link org.roxycode.app.service.AiService}, {@link
 * ReturnDirectToolCallingManager}, and {@link JexlTool}.
 */
public record TurnToolContext(
        String conversationId, WorkflowPhase phase, AtomicInteger turnCounter) {

    public static final String CONVERSATION_ID_KEY = "conversationId";
    public static final String PHASE_KEY = "phase";
    public static final String TURN_COUNTER_KEY = "turnCounter";
    public static final String DEFAULT_CONVERSATION_ID = "default";

    public TurnToolContext {
        conversationId = StringUtils.defaultIfBlank(conversationId, DEFAULT_CONVERSATION_ID);
        phase = phase != null ? phase : WorkflowPhase.EXPLORE;
        turnCounter = turnCounter != null ? turnCounter : new AtomicInteger(0);
    }

    public Map<String, Object> toMap() {
        return Map.of(
                CONVERSATION_ID_KEY,
                conversationId,
                PHASE_KEY,
                phase,
                TURN_COUNTER_KEY,
                turnCounter);
    }

    public static TurnToolContext fromToolContext(ToolContext toolContext) {
        return fromMap(
                toolContext != null ? toolContext.getContext() : null, WorkflowPhase.EXPLORE);
    }

    public static TurnToolContext fromPrompt(Prompt prompt, WorkflowPhase fallbackPhase) {
        Map<String, Object> map =
                prompt != null && prompt.getOptions() instanceof ToolCallingChatOptions options
                        ? options.getToolContext()
                        : null;
        return fromMap(map, fallbackPhase);
    }

    public static TurnToolContext fromMap(
            Map<String, Object> contextMap, WorkflowPhase fallbackPhase) {
        WorkflowPhase defaultPhase = fallbackPhase != null ? fallbackPhase : WorkflowPhase.EXPLORE;
        if (contextMap == null || contextMap.isEmpty()) {
            return new TurnToolContext(DEFAULT_CONVERSATION_ID, defaultPhase, new AtomicInteger(0));
        }

        String convId =
                contextMap.get(CONVERSATION_ID_KEY) instanceof String s && StringUtils.isNotBlank(s)
                        ? s
                        : DEFAULT_CONVERSATION_ID;

        WorkflowPhase resolvedPhase = defaultPhase;
        Object phaseObj = contextMap.get(PHASE_KEY);
        if (phaseObj instanceof WorkflowPhase wp) {
            resolvedPhase = wp;
        } else if (phaseObj instanceof String s) {
            try {
                resolvedPhase = WorkflowPhase.valueOf(s);
            } catch (IllegalArgumentException ignored) {
                // Keep fallback phase
            }
        }

        AtomicInteger counter =
                contextMap.get(TURN_COUNTER_KEY) instanceof AtomicInteger ai
                        ? ai
                        : new AtomicInteger(0);

        return new TurnToolContext(convId, resolvedPhase, counter);
    }
}
