package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

class TurnToolContextTest {

    @Test
    void testDefaultsAndToMapRoundTrip() {
        TurnToolContext defaultCtx = new TurnToolContext(null, null, null, null);
        assertEquals(TurnToolContext.DEFAULT_CONVERSATION_ID, defaultCtx.conversationId());
        assertEquals(WorkflowPhase.EXPLORE, defaultCtx.phase());
        assertNotNull(defaultCtx.turnCounter());
        assertEquals(0, defaultCtx.turnCounter().get());
        assertNotNull(defaultCtx.textAccumulator());

        AtomicInteger counter = new AtomicInteger(3);
        AtomicReference<StringBuilder> accumulator =
                new AtomicReference<>(new StringBuilder("existing"));
        TurnToolContext ctx =
                new TurnToolContext("task:default", WorkflowPhase.CODE, counter, accumulator);
        Map<String, Object> map = ctx.toMap();

        TurnToolContext restored = TurnToolContext.fromToolContext(new ToolContext(map));
        assertEquals("task:default", restored.conversationId());
        assertEquals(WorkflowPhase.CODE, restored.phase());
        assertSame(counter, restored.turnCounter());
        assertEquals("existing", restored.textAccumulator().get().toString());
    }

    @Test
    void testFromPromptAndStringPhaseFallback() {
        Prompt prompt =
                new Prompt(
                        new UserMessage("hi"),
                        ToolCallingChatOptions.builder()
                                .toolContext(
                                        Map.of(
                                                TurnToolContext.CONVERSATION_ID_KEY,
                                                "qa:1",
                                                TurnToolContext.PHASE_KEY,
                                                "REVIEW"))
                                .build());

        TurnToolContext fromPrompt = TurnToolContext.fromPrompt(prompt, WorkflowPhase.PLAN);
        assertEquals("qa:1", fromPrompt.conversationId());
        assertEquals(WorkflowPhase.REVIEW, fromPrompt.phase());

        TurnToolContext fallback = TurnToolContext.fromPrompt(null, WorkflowPhase.PLAN);
        assertEquals("default", fallback.conversationId());
        assertEquals(WorkflowPhase.PLAN, fallback.phase());
    }
}
