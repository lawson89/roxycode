package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.events.ToolExecutionEvent;
import org.springframework.ai.chat.model.ToolContext;

public class JexlToolNotificationTest {

    @Test
    public void testListenerNotification() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        AtomicBoolean called = new AtomicBoolean(false);
        AtomicReference<AgentTurnEvent> turnEvent = new AtomicReference<>();

        JexlTool tool = new JexlTool(registry, event -> {
            if (event instanceof AgentTurnEvent ate) {
                turnEvent.set(ate);
            } else if (event instanceof ToolExecutionEvent tee) {
                called.set(true);
                assertEquals("qa:default", tee.conversationId());
                assertEquals("1 + 1", tee.script());
                assertEquals(2, ((Number) tee.result()).intValue());
                assertTrue(tee.success());
            }
        }, null, null);

        tool.executeJexl("1 + 1",
                new ToolContext(new TurnToolContext("qa:default", WorkflowPhase.EXPLORE, null).toMap()));
        assertTrue(called.get());
        assertNotNull(turnEvent.get());
        assertEquals("qa:default", turnEvent.get().conversationId());
        assertEquals("Roxy", turnEvent.get().agentName());
        assertEquals(1, turnEvent.get().turnNumber());
        assertEquals("EXPLORE", turnEvent.get().phase());
    }

    @Test
    public void testSyntaxErrorListenerNotification() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        AtomicBoolean called = new AtomicBoolean(false);

        JexlTool tool = new JexlTool(registry, event -> {
            if (event instanceof ToolExecutionEvent tee) {
                called.set(true);
                assertEquals("default", tee.conversationId());
                assertEquals("if (", tee.script());
                assertNull(tee.result());
                assertFalse(tee.success());
                assertNotNull(tee.error());
                assertTrue(tee.error().startsWith("Syntax Error: "));
            }
        }, null, null);

        tool.executeJexl("if (");
        assertTrue(called.get(), "ApplicationEventPublisher should be notified on JEXL syntax error");
    }
}
