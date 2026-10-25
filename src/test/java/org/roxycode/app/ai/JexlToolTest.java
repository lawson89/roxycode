package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.services.BuildResult;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.events.ToolExecutionEvent;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.ApplicationEventPublisher;

public class JexlToolTest {

    @AgentService(
            value = "testService",
            phases = {"*"})
    public static class TestService {
        private final CountDownLatch loopStarted = new CountDownLatch(1);

        @AgentDoc("Greets a user by name.")
        public String hello(String name) {
            return "Hello, " + name;
        }

        @AgentDoc("Returns a sample BuildResult.")
        public BuildResult sampleBuildResult() {
            return new BuildResult(
                    false,
                    1,
                    "Compilation failed",
                    List.of("App.java:10: error"),
                    List.of("AppTest#testFoo"),
                    "Full build log");
        }

        @AgentDoc("Returns a sample EditorResult.")
        public EditorResult sampleEditorResult() {
            return new EditorResult(true, "matched content", null);
        }

        @AgentDoc("Signals that a background loop has started.")
        public void signalStarted() {
            loopStarted.countDown();
        }

        @AgentDoc("Interrupts current thread and returns.")
        public String interruptSelfAndReturn() {
            Thread.currentThread().interrupt();
            return "done";
        }

        @AgentDoc("Throws IllegalArgumentException.")
        public String throwIllegalArgument() {
            throw new IllegalArgumentException("File does not exist: missing.txt");
        }

        @AgentDoc("Throws ClosedByInterruptException.")
        public String throwClosedByInterrupt() throws IOException {
            throw new ClosedByInterruptException();
        }

        public String internalMethodWithoutAgentDoc() {
            return "secret-internal";
        }
    }

    @AgentService(
            value = "codeOnlyService",
            phases = {"CODE"})
    public static class CodeOnlyService {
        @AgentDoc("Writes data in CODE phase.")
        public String writeData() {
            return "written";
        }
    }

    @Test
    public void testExecuteJexlWithService() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        registry.register(new TestService());

        JexlTool tool = new JexlTool(registry, null, null);

        String varCheck = tool.executeJexl("testService");
        assertNotNull(varCheck);

        String result = tool.executeJexl("testService.hello('Roxy')");
        assertTrue(result.contains("Hello, Roxy"));
    }

    @Test
    public void testExecuteJexlSerializesBuildResultAndEditorResultAsStructuredJson()
            throws Exception {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        registry.register(new TestService());
        JexlTool tool = new JexlTool(registry, null, null);

        String buildJson = tool.executeJexl("testService.sampleBuildResult()");
        JsonNode buildRoot = AiConfig.SHARED_OBJECT_MAPPER.readTree(buildJson);
        JsonNode buildNode = buildRoot.get("result");
        assertNotNull(buildNode);
        assertTrue(buildNode.isObject(), "BuildResult should serialize as a JSON object");
        assertFalse(buildNode.get("success").asBoolean());
        assertEquals(1, buildNode.get("exitCode").asInt());
        assertEquals("Compilation failed", buildNode.get("output").asText());
        assertEquals("App.java:10: error", buildNode.get("errors").get(0).asText());
        assertEquals("AppTest#testFoo", buildNode.get("failedTests").get(0).asText());
        assertEquals("Full build log", buildNode.get("log").asText());

        String editorJson = tool.executeJexl("testService.sampleEditorResult()");
        JsonNode editorRoot = AiConfig.SHARED_OBJECT_MAPPER.readTree(editorJson);
        JsonNode editorNode = editorRoot.get("result");
        assertNotNull(editorNode);
        assertTrue(editorNode.isObject(), "EditorResult should serialize as a JSON object");
        assertTrue(editorNode.get("success").asBoolean());
        assertEquals("matched content", editorNode.get("content").asText());
        assertTrue(editorNode.get("errorHint").isNull());
    }

    @Test
    public void testBuildResultSupportsBothRecordAndBeanAccessorsInJexl() throws Exception {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        registry.register(new TestService());
        JexlTool tool = new JexlTool(registry, null, null);

        String script =
                """
                var res = testService.sampleBuildResult();
                {
                  "recordSuccess": res.success(),
                  "beanIsSuccess": res.isSuccess(),
                  "propSuccess": res.success,
                  "recordExitCode": res.exitCode(),
                  "beanExitCode": res.getExitCode(),
                  "recordErrors": res.errors(),
                  "beanErrors": res.getErrors(),
                  "recordFailedTests": res.failedTests(),
                  "beanFailedTests": res.getFailedTests()
                }
                """;
        String json = tool.executeJexl(script);
        JsonNode node = AiConfig.SHARED_OBJECT_MAPPER.readTree(json);
        assertFalse(node.get("recordSuccess").asBoolean());
        assertFalse(node.get("beanIsSuccess").asBoolean());
        assertFalse(node.get("propSuccess").asBoolean());
        assertEquals(1, node.get("recordExitCode").asInt());
        assertEquals(1, node.get("beanExitCode").asInt());
        assertEquals("App.java:10: error", node.get("recordErrors").get(0).asText());
        assertEquals("App.java:10: error", node.get("beanErrors").get(0).asText());
        assertEquals("AppTest#testFoo", node.get("recordFailedTests").get(0).asText());
        assertEquals("AppTest#testFoo", node.get("beanFailedTests").get(0).asText());

        BuildResult nullListResult = new BuildResult(true, 0, "ok", null, null, "");
        assertTrue(nullListResult.errors().isEmpty());
        assertTrue(nullListResult.failedTests().isEmpty());
    }

    @Test
    public void testExecuteJexlReadsPhaseAndConversationIdFromToolContext() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        registry.register(new CodeOnlyService());

        AtomicReference<ToolExecutionEvent> capturedEvent = new AtomicReference<>();
        ApplicationEventPublisher publisher =
                event -> {
                    if (event instanceof ToolExecutionEvent tee) {
                        capturedEvent.set(tee);
                    }
                };

        JexlTool tool = new JexlTool(registry, publisher, null);

        // Default EXPLORE phase cannot access codeOnlyService
        String exploreRes = tool.executeJexl("codeOnlyService.writeData()");
        assertTrue(exploreRes.contains("error"));

        // Passing CODE phase and conversationId via ToolContext allows access and publishes event
        ToolContext toolContext =
                new ToolContext(
                        new TurnToolContext("task:default", WorkflowPhase.CODE, null).toMap());
        String codeRes = tool.executeJexl("codeOnlyService.writeData()", toolContext);
        assertTrue(codeRes.contains("written"));
        assertNotNull(capturedEvent.get());
        assertEquals("task:default", capturedEvent.get().conversationId());
        assertEquals("codeOnlyService.writeData()", capturedEvent.get().script());
        assertEquals("written", capturedEvent.get().result());
        assertTrue(capturedEvent.get().success());
    }

    @Test
    public void testAgentServiceMethodWithoutAgentDocIsBlockedWhileObjectMethodsAllowed() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        registry.register(new TestService());

        JexlTool tool = new JexlTool(registry, null, null);

        String toStringResult = tool.executeJexl("testService.toString()");
        assertTrue(toStringResult.contains("TestService"), "Object.toString() should be allowed");

        String blockedResult = tool.executeJexl("testService.internalMethodWithoutAgentDoc()");
        assertTrue(
                blockedResult.contains("error"),
                "Unannotated method on @AgentService must be rejected by JexlPermissions");
        assertFalse(blockedResult.contains("secret-internal"));
    }

    @Test
    public void testExecuteJexlSuccess() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        JexlTool tool = new JexlTool(registry, null, null);
        String result = tool.executeJexl("1 + 1");
        assertTrue(
                result.contains(
                        """
                        "result":"2" """
                                .trim()));
    }

    @Test
    public void testExecuteJexlWithVariable() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        JexlTool tool = new JexlTool(registry, null, null);
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
        JexlTool tool = new JexlTool(registry, null, null);
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
        AtomicReference<ToolExecutionEvent> capturedEvent = new AtomicReference<>();
        JexlTool tool =
                new JexlTool(
                        registry,
                        event -> {
                            if (event instanceof ToolExecutionEvent tee) {
                                capturedEvent.set(tee);
                            }
                        },
                        null);

        String result = tool.executeJexl("if (");
        assertTrue(result.contains("Syntax Error"));
        assertNotNull(capturedEvent.get(), "Syntax error should publish ToolExecutionEvent");
        assertEquals("if (", capturedEvent.get().script());
        assertNull(capturedEvent.get().result());
        assertFalse(capturedEvent.get().success());
        assertNotNull(capturedEvent.get().error());
        assertTrue(capturedEvent.get().error().startsWith("Syntax Error: "));
    }

    @Test
    public void testToJsonErrorFallbackProducesValidJsonRecognizedAsErrorResponse()
            throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper failingMapper =
                new com.fasterxml.jackson.databind.ObjectMapper() {
                    @Override
                    public String writeValueAsString(Object value)
                            throws com.fasterxml.jackson.core.JsonProcessingException {
                        throw new com.fasterxml.jackson.core.JsonGenerationException(
                                "Simulated failure",
                                (com.fasterxml.jackson.core.JsonGenerator) null);
                    }
                };
        JexlTool tool = new JexlTool(new JexlServiceRegistry(), null, failingMapper);
        String result = tool.executeJexl("if (");
        assertEquals(
                """
                {"error":"Internal Error during error serialization"}"""
                        .strip(),
                result);
        assertTrue(
                RoxyToolExecutionExceptionProcessor.isErrorResponse(result),
                "Fallback JSON error must be recognized by RoxyToolExecutionExceptionProcessor.isErrorResponse");
        var parsed = AiConfig.SHARED_OBJECT_MAPPER.readTree(result);
        assertEquals("Internal Error during error serialization", parsed.get("error").asText());
    }
    @Test
    public void testExecuteJexlInterrupted() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        JexlTool tool = new JexlTool(registry, null, null);
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
        AtomicReference<ToolExecutionEvent> capturedEvent = new AtomicReference<>();
        JexlTool tool =
                new JexlTool(
                        registry,
                        event -> {
                            if (event instanceof ToolExecutionEvent tee) {
                                capturedEvent.set(tee);
                            }
                        },
                        null);

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
        assertNotNull(capturedEvent.get(), "Event publisher should be notified on cancellation");
        assertFalse(capturedEvent.get().success(), "Event should indicate failure");
        assertEquals("Execution cancelled", capturedEvent.get().error());
    }

    @Test
    public void testExecuteJexlInterruptedDuringScriptExecutionThrowsCancellationException() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        registry.register(new TestService());
        JexlTool tool = new JexlTool(registry, null, null);

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

    @Test
    public void testExecuteJexlServiceExceptionsUnwrappedAndClosedByInterruptCancelled() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        registry.register(new TestService());
        AtomicReference<ToolExecutionEvent> capturedEvent = new AtomicReference<>();
        JexlTool tool =
                new JexlTool(
                        registry,
                        event -> {
                            if (event instanceof ToolExecutionEvent tee) {
                                capturedEvent.set(tee);
                            }
                        },
                        null);

        String errorJson = tool.executeJexl("testService.throwIllegalArgument()");
        assertTrue(errorJson.contains("File does not exist: missing.txt"));
        assertNotNull(capturedEvent.get());
        assertFalse(capturedEvent.get().success());
        assertEquals("File does not exist: missing.txt", capturedEvent.get().error());

        try {
            assertThrows(
                    CancellationException.class,
                    () -> tool.executeJexl("testService.throwClosedByInterrupt()"));
            assertTrue(
                    Thread.currentThread().isInterrupted(),
                    "Interrupt flag should be restored when service throws ClosedByInterruptException");
        } finally {
            Thread.interrupted();
        }
    }
}
