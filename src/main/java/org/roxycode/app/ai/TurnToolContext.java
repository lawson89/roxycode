package org.roxycode.app.ai;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ToolContext;

public record TurnToolContext(String conversationId, WorkflowPhase phase, AtomicInteger turnCounter) {

    public static final String CONVERSATION_ID_KEY = "conversationId";
    public static final String PHASE_KEY = "phase";
    public static final String TURN_COUNTER_KEY = "turnCounter";
    public static final String DEFAULT_CONVERSATION_ID = "default";

    public TurnToolContext {
        conversationId = StringUtils.defaultIfBlank(conversationId, DEFAULT_CONVERSATION_ID);
        if (phase == null) {
            phase = WorkflowPhase.EXPLORE;
        }
        if (turnCounter == null) {
            turnCounter = new AtomicInteger(0);
        }
    }

    public Map<String, Object> toMap() {
        return Map.of(CONVERSATION_ID_KEY, conversationId, PHASE_KEY, phase, TURN_COUNTER_KEY, turnCounter);
    }

    public static TurnToolContext fromToolContext(ToolContext toolContext) {
        Map<String, Object> contextMap = null;
        if (toolContext != null) {
            contextMap = toolContext.getContext();
        }
        if (contextMap == null || contextMap.isEmpty()) {
            return new TurnToolContext(DEFAULT_CONVERSATION_ID, WorkflowPhase.EXPLORE, null);
        }
        String convId = DEFAULT_CONVERSATION_ID;
        if (contextMap.get(CONVERSATION_ID_KEY) instanceof String s && StringUtils.isNotBlank(s)) {
            convId = s;
        }
        WorkflowPhase resolvedPhase = WorkflowPhase.EXPLORE;
        Object phaseObj = contextMap.get(PHASE_KEY);
        if (phaseObj instanceof WorkflowPhase wp) {
            resolvedPhase = wp;
        } else if (phaseObj instanceof String s) {
            try {
                resolvedPhase = WorkflowPhase.valueOf(s);
            } catch (IllegalArgumentException ignored) {
            }
        }
        AtomicInteger counter = null;
        if (contextMap.get(TURN_COUNTER_KEY) instanceof AtomicInteger ai) {
            counter = ai;
        }
        return new TurnToolContext(convId, resolvedPhase, counter);
    }
}
