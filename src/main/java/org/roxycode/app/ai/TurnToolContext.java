package org.roxycode.app.ai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Captures the execution context for a single AI tool turn, including conversation state, workflow phase tracking, and
 * resolved model name.
 */
public record TurnToolContext(String conversationId, WorkflowPhase phase, AtomicInteger turnCounter,
        @Nullable String modelName) {

    public static final String CONVERSATION_ID_KEY = "conversationId";
    public static final String PHASE_KEY = "phase";
    public static final String TURN_COUNTER_KEY = "turnCounter";
    public static final String MODEL_NAME_KEY = "modelName";
    public static final String DEFAULT_CONVERSATION_ID = "default";

    public TurnToolContext {
        conversationId = StringUtils.defaultIfBlank(conversationId, DEFAULT_CONVERSATION_ID);
        if (phase == null) {
            phase = WorkflowPhase.EXPLORE;
        }
        if (turnCounter == null) {
            turnCounter = new AtomicInteger(0);
        }
        modelName = StringUtils.trimToNull(modelName);
    }

    /**
     * Backward-compatible constructor without an explicit model name.
     *
     * @param conversationId
     *            the conversation identifier
     * @param phase
     *            the active workflow phase
     * @param turnCounter
     *            the mutable per-turn tool counter
     */
    public TurnToolContext(String conversationId, WorkflowPhase phase, AtomicInteger turnCounter) {
        this(conversationId, phase, turnCounter, null);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(CONVERSATION_ID_KEY, conversationId);
        map.put(PHASE_KEY, phase);
        map.put(TURN_COUNTER_KEY, turnCounter);
        if (modelName != null) {
            map.put(MODEL_NAME_KEY, modelName);
        }
        return Collections.unmodifiableMap(map);
    }

    public static TurnToolContext fromToolContext(ToolContext toolContext) {
        Map<String, Object> contextMap = null;
        if (toolContext != null) {
            contextMap = toolContext.getContext();
        }
        if (contextMap == null || contextMap.isEmpty()) {
            return new TurnToolContext(DEFAULT_CONVERSATION_ID, WorkflowPhase.EXPLORE, null, null);
        }
        String convId = DEFAULT_CONVERSATION_ID;
        if (contextMap.get(CONVERSATION_ID_KEY) instanceof String s && StringUtils.isNotBlank(s)) {
            convId = s;
        }
        WorkflowPhase resolvedPhase = WorkflowPhase.fromObject(contextMap.get(PHASE_KEY), WorkflowPhase.EXPLORE);
        AtomicInteger counter = null;
        if (contextMap.get(TURN_COUNTER_KEY) instanceof AtomicInteger ai) {
            counter = ai;
        }
        String resolvedModel = null;
        if (contextMap.get(MODEL_NAME_KEY) instanceof String m && StringUtils.isNotBlank(m)) {
            resolvedModel = m;
        }
        return new TurnToolContext(convId, resolvedPhase, counter, resolvedModel);
    }
}
