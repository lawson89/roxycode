package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

public class JexlToolTest {

    @AgentService(
            value = "testService",
            phases = {"*"})
    public static class TestService {
        private final CountDownLatch loopStarted = new CountDownLatch(1);

        public String hello(String name) {
            return "Hello, " + name;
        }

        public String requestTransition() {
            return """
                    {"message":"Yielded","reason":"Done"}""";
        }

        public void signalStarted() {
            loopStarted.countDown();
        }

        public String interruptSelfAndReturn() {
            Thread.currentThread().interrupt();
            return "done";
        }
    }

    @Test
    public void testExecuteJexlWithService() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        registry.register(new TestService());

        JexlTool tool = new JexlTool(registry, WorkflowPhase.EXPLORE);

        String varCheck = tool.executeJexl("testService");
        assertNotNull(varCheck);
        assertFalse(tool.isReturnDirect());

        String result = tool.executeJexl("testService.hello('Roxy')");
        assertTrue(result.contains("Hello, Roxy"));
        assertFalse(tool.isReturnDirect());
    }

    @Test
    public void testExecuteJexlTransitionSetsReturnDirectAndNotifiesListeners() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        registry.register(new TestService());

        JexlTool tool = new JexlTool(registry, WorkflowPhase.PLAN);
        AtomicBoolean listenerNotified = new AtomicBoolean(false);
        tool.addListener(
                event -> {
                    listenerNotified.set(true);
                    assertTrue(event.success());
                    assertTrue(event.result().toString().contains("Yielded"));
                });

        String response = tool.executeJexl("testService.requestTransition()");
        assertEquals(
                """
                {"message":"Yielded","reason":"Done"}""",
                response);
        assertTrue(tool.isReturnDirect(), "Transition should mark tool as returnDirect");
        assertTrue(listenerNotified.get(), "Execution listener should be notified on transition");
    }

    @Test
    public void testExecuteJexlTurnCountAndMaxTurnsShortCircuit() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        JexlTool tool = new JexlTool(registry, WorkflowPhase.EXPLORE, 2);

        AtomicInteger listenerCallCount = new AtomicInteger(0);
        AtomicInteger lastSeenTurnCount = new AtomicInteger(0);
        tool.addListener(
                event -> {
                    listenerCallCount.incrementAndGet();
                    lastSeenTurnCount.set(tool.getTurnCount());
                });

        assertEquals(0, tool.getTurnCount());

                String r1 = tool.executeJexl("1 + 1");
        assertTrue(r1.contains("2"));
        assertEquals(1, tool.getTurnCount());
        assertEquals(1, lastSeenTurnCount.get());
        assertFalse(tool.isReturnDirect());
        assertFalse(JexlTool.isMaxTurnsExceededResponse(r1));

        String r2 = tool.executeJexl("2 + 2");
        assertTrue(r2.contains("4"));
        assertEquals(2, tool.getTurnCount());
        assertEquals(2, lastSeenTurnCount.get());
        assertFalse(tool.isReturnDirect());
        assertFalse(JexlTool.isMaxTurnsExceededResponse(r2));

        String r3 = tool.executeJexl("3 + 3");
        assertEquals("Autonomous execution stopped: Maximum tool turns (2) exceeded.", r3);
        assertTrue(JexlTool.isMaxTurnsExceededResponse(r3));
        assertTrue(tool.isReturnDirect(), "Max turns exceeded should set returnDirect to true");
        assertEquals(2, tool.getTurnCount(), "Turn count should not increment after maxTurns");
        assertEquals(
                2,
                listenerCallCount.get(),
                "Listener should not be notified when maxTurns short-circuits");
    }

    @Test
    public void testExecuteJexlSuccess() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        JexlTool tool = new JexlTool(registry, WorkflowPhase.EXPLORE);
        String result = tool.executeJexl("1 + 1");
        assertTrue(
                result.contains(
                        """
                        "result":"2" """
                                .trim()));
        assertFalse(tool.isReturnDirect());
    }

    @Test
    public void testExecuteJexlWithVariable() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        JexlTool tool = new JexlTool(registry, WorkflowPhase.EXPLORE);
        String result = tool.executeJexl("var x = 10; x * 2");
        assertTrue(
                result.contains(
                        """
                        "result":"20" """
                                .trim()));
    }

    @Test
    public void testExecuteJexlNull() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        JexlTool tool = new JexlTool(registry, WorkflowPhase.EXPLORE);
        String result = tool.executeJexl("null");
        assertTrue(
                result.contains(
                        """
                        "result":"null" """
                                .trim()));
    }

    @Test
    public void testExecuteJexlError() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        JexlTool tool = new JexlTool(registry, WorkflowPhase.EXPLORE);
        AtomicReference<JexlExecutionEvent> capturedEvent = new AtomicReference<>();
        tool.addListener(capturedEvent::set);

        String result = tool.executeJexl("if (");
        assertTrue(result.contains("Syntax Error"));
        assertNotNull(capturedEvent.get(), "Syntax error should fire JexlExecutionEvent");
        assertEquals("if (", capturedEvent.get().script());
        assertNull(capturedEvent.get().result());
        assertFalse(capturedEvent.get().success());
        assertNotNull(capturedEvent.get().error());
        assertTrue(capturedEvent.get().error().startsWith("Syntax Error: "));
    }

    @Test
    public void testExecuteJexlInterrupted() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        JexlTool tool = new JexlTool(registry, WorkflowPhase.EXPLORE);
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> tool.executeJexl("1 + 1"));
        } finally {
            Thread.interrupted(); // Clear flag
        }
    }

    @Test
    public void testExecuteJexlLoopInterruptedPropagatesCancelAsCancellationException()
            throws Exception {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        TestService testService = new TestService();
        registry.register(testService);
        JexlTool tool = new JexlTool(registry, WorkflowPhase.EXPLORE);

        AtomicReference<JexlExecutionEvent> capturedEvent = new AtomicReference<>();
        tool.addListener(capturedEvent::set);

        AtomicReference<Throwable> thrownRef = new AtomicReference<>();
        AtomicBoolean interruptFlagPreserved = new AtomicBoolean(false);

        Thread worker =
                new Thread(
                        () -> {
                            try {
                                tool.executeJexl(
                                        "testService.signalStarted(); while (true) { var x = 1; }");
                            } catch (Throwable t) {
                                thrownRef.set(t);
                                interruptFlagPreserved.set(Thread.currentThread().isInterrupted());
                            }
                        });
        worker.start();

        assertTrue(
                testService.loopStarted.await(5, TimeUnit.SECONDS),
                "JEXL loop should have started");
        worker.interrupt();
        worker.join(5000);

        assertFalse(worker.isAlive(), "Worker thread should have terminated after interrupt");
        assertInstanceOf(
                CancellationException.class,
                thrownRef.get(),
                "JexlException.Cancel should be propagated as CancellationException");
        assertTrue(
                interruptFlagPreserved.get(),
                "Thread interrupt flag should be restored when JexlException.Cancel is caught");
        assertNotNull(capturedEvent.get(), "Listener should be notified on cancellation");
        assertFalse(capturedEvent.get().success(), "Listener event should indicate failure");
        assertEquals("Execution cancelled", capturedEvent.get().error());
    }

    @Test
    public void testExecuteJexlInterruptedDuringScriptExecutionThrowsCancellationException() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        registry.register(new TestService());
        JexlTool tool = new JexlTool(registry, WorkflowPhase.EXPLORE);

        try {
            assertThrows(
                    CancellationException.class,
                    () -> tool.executeJexl("testService.interruptSelfAndReturn()"));
            assertTrue(
                    Thread.currentThread().isInterrupted(),
                    "Interrupt flag should remain set after post-execution interrupt check");
        } finally {
            Thread.interrupted();
        }
    }
}
