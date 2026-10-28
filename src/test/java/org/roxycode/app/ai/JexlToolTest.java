package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.services.BuildResult;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.events.ToolExecutionEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.SettingsService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.ApplicationEventPublisher;

public class JexlToolTest {

    @AgentService(value = "testService", phases = {"*"})
    public static class TestService {
        private final CountDownLatch loopStarted = new CountDownLatch(1);

        @AgentDoc("Greets a user by name.")
        public String hello(String name) {
            return "Hello, " + name;
        }

        @AgentDoc("Returns a sample BuildResult.")
        public BuildResult sampleBuildResult() {
            return new BuildResult(false, 1, "Compilation failed", List.of("App.java:10: error"),
                    List.of("AppTest#testFoo"), "Full build log");
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

    @AgentService(value = "codeOnlyService", phases = {"CODE"})
    public static class CodeOnlyService {
        @AgentDoc("Writes data in CODE phase.")
        public String writeData() {
            return "written";
        }
    }

    private static JexlTool createTool(Object... services) {
        return createTool(null, services);
    }

    private static JexlTool createTool(AtomicReference<ToolExecutionEvent> capturedEvent, Object... services) {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        for (Object service : services) {
            registry.register(service);
        }
        ApplicationEventPublisher publisher = capturedEvent == null ? null : event -> {
            if (event instanceof ToolExecutionEvent tee) {
                capturedEvent.set(tee);
            }
        };
        return new JexlTool(registry, publisher, null, null);
    }

    private static JsonNode evalJson(JexlTool tool, String script) throws Exception {
        return AiConfig.SHARED_OBJECT_MAPPER.readTree(tool.executeJexl(script));
    }

    @Test
    public void testExecuteJexlWithService() {
        JexlTool tool = createTool(new TestService());
        assertNotNull(tool.executeJexl("testService"));
        assertTrue(tool.executeJexl("testService.hello('Roxy')").contains("Hello, Roxy"));
    }

    @Test
    public void testExecuteJexlSerializesBuildResultAndEditorResultAsStructuredJson() throws Exception {
        JexlTool tool = createTool(new TestService());

        JsonNode buildNode = evalJson(tool, "testService.sampleBuildResult()").get("result");
        assertTrue(buildNode.isObject());
        assertFalse(buildNode.get("success").asBoolean());
        assertEquals(1, buildNode.get("exitCode").asInt());
        assertEquals("Compilation failed", buildNode.get("output").asText());
        assertEquals("App.java:10: error", buildNode.get("errors").get(0).asText());
        assertEquals("AppTest#testFoo", buildNode.get("failedTests").get(0).asText());
        assertEquals("Full build log", buildNode.get("log").asText());

        JsonNode editorNode = evalJson(tool, "testService.sampleEditorResult()").get("result");
        assertTrue(editorNode.isObject());
        assertTrue(editorNode.get("success").asBoolean());
        assertEquals("matched content", editorNode.get("content").asText());
        assertTrue(editorNode.get("errorHint").isNull());
    }

    @Test
    public void testBuildResultSupportsBothRecordAndBeanAccessorsInJexl() throws Exception {
        JexlTool tool = createTool(new TestService());
        JsonNode node = evalJson(tool, """
                var res = testService.sampleBuildResult();
                {
                  "recordSuccess": res.success(), "beanIsSuccess": res.isSuccess(), "propSuccess": res.success,
                  "recordExitCode": res.exitCode(), "beanExitCode": res.getExitCode(),
                  "recordErrors": res.errors(), "beanErrors": res.getErrors(),
                  "recordFailedTests": res.failedTests(), "beanFailedTests": res.getFailedTests()
                }
                """);
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
        AtomicReference<ToolExecutionEvent> capturedEvent = new AtomicReference<>();
        JexlTool tool = createTool(capturedEvent, new CodeOnlyService());
        assertTrue(tool.executeJexl("codeOnlyService.writeData()").contains("error"));

        ToolContext toolContext = new ToolContext(
                new TurnToolContext("task:default", WorkflowPhase.CODE, null).toMap());
        assertTrue(tool.executeJexl("codeOnlyService.writeData()", toolContext).contains("written"));
        assertEquals("task:default", capturedEvent.get().conversationId());
        assertEquals("codeOnlyService.writeData()", capturedEvent.get().script());
        assertEquals("written", capturedEvent.get().result());
        assertTrue(capturedEvent.get().success());
    }

    @Test
    public void testExecuteJexlEnforcesMaxTurnsAndPublishesAgentTurnEvents() throws Exception {
        SettingsService settingsService = mock(SettingsService.class);
        AppSettings settings = new AppSettings();
        settings.setMaxAgentToolTurns(2);
        when(settingsService.getSettings()).thenReturn(settings);

        List<AgentTurnEvent> turnEvents = new ArrayList<>();
        List<ToolExecutionEvent> toolEvents = new ArrayList<>();
        JexlTool tool = new JexlTool(new JexlServiceRegistry(), event -> {
            if (event instanceof AgentTurnEvent ate) {
                turnEvents.add(ate);
            } else if (event instanceof ToolExecutionEvent tee) {
                toolEvents.add(tee);
            }
        }, null, settingsService);

        AtomicInteger turnCounter = new AtomicInteger(0);
        ToolContext toolContext = new ToolContext(
                new TurnToolContext("conv-1", WorkflowPhase.CODE, turnCounter).toMap());

        assertEquals("2",
                AiConfig.SHARED_OBJECT_MAPPER.readTree(tool.executeJexl("1 + 1", toolContext)).get("result").asText());
        assertEquals("4",
                AiConfig.SHARED_OBJECT_MAPPER.readTree(tool.executeJexl("2 + 2", toolContext)).get("result").asText());
        assertEquals(2, turnCounter.get());
        assertEquals(2, turnEvents.size());
        assertEquals(1, turnEvents.get(0).turnNumber());
        assertEquals(2, turnEvents.get(1).turnNumber());
        assertEquals("CODE", turnEvents.get(0).phase());
        assertEquals(2, toolEvents.size());

        String thirdTurnResult = tool.executeJexl("3 + 3", toolContext);
        assertEquals(
                "Autonomous execution stopped: Maximum tool turns (2) reached. Stop calling tools and summarize your progress for the user.",
                AiConfig.SHARED_OBJECT_MAPPER.readTree(thirdTurnResult).get("error").asText());
        assertEquals(2, turnCounter.get());
        assertEquals(2, turnEvents.size());
        assertEquals(2, toolEvents.size());
    }

    @Test
    public void testAgentServiceMethodWithoutAgentDocIsBlockedWhileObjectMethodsAllowed() {
        JexlTool tool = createTool(new TestService());
        assertTrue(tool.executeJexl("testService.toString()").contains("TestService"));
        String blockedResult = tool.executeJexl("testService.internalMethodWithoutAgentDoc()");
        assertTrue(blockedResult.contains("error"));
        assertFalse(blockedResult.contains("secret-internal"));
    }

    @Test
    public void testSandboxBlocksNewInstantiationReflectionAndSystemAccess() throws Exception {
        JexlTool tool = createTool(new TestService());

        JsonNode newProcessBuilder = evalJson(tool, "new('java.lang.ProcessBuilder', ['ls'])");
        assertTrue(newProcessBuilder.has("error"));
        assertTrue(newProcessBuilder.get("error").asText().contains("Syntax Error"));

        JsonNode getClassAttempt = evalJson(tool, "''.getClass()");
        assertTrue(getClassAttempt.has("error"));

        JsonNode serviceGetClassAttempt = evalJson(tool, "testService.getClass()");
        assertTrue(serviceGetClassAttempt.has("error"));

        JsonNode systemAttempt = evalJson(tool, "System.exit(0)");
        assertTrue(systemAttempt.has("error"));

        JsonNode runtimeAttempt = evalJson(tool, "Runtime.getRuntime()");
        assertTrue(runtimeAttempt.has("error"));
    }

    @Test
    public void testSandboxAllowsCollectionsMapsStringsAndRecords() throws Exception {
        JexlTool tool = createTool(new TestService());
        JsonNode result = evalJson(tool, """
                var list = ['alpha', 'beta', ...];
                list.add('gamma');
                var map = { 'greeting': testService.hello('Roxy').trim().toUpperCase(), 'count': list.size() };
                map
                """);
        assertEquals("HELLO, ROXY", result.get("greeting").asText());
        assertEquals(3, result.get("count").asInt());
    }

    @Test
    public void testExecuteJexlSuccess() throws Exception {
        assertEquals("2", evalJson(createTool(), "1 + 1").get("result").asText());
    }

    @Test
    public void testExecuteJexlWithVariable() throws Exception {
        assertEquals("20", evalJson(createTool(), "var x = 10; x * 2").get("result").asText());
    }

    @Test
    public void testExecuteJexlNull() throws Exception {
        assertEquals("null", evalJson(createTool(), "null").get("result").asText());
    }

    @Test
    public void testExecuteJexlError() {
        AtomicReference<ToolExecutionEvent> capturedEvent = new AtomicReference<>();
        assertTrue(createTool(capturedEvent).executeJexl("if (").contains("Syntax Error"));
        assertEquals("if (", capturedEvent.get().script());
        assertNull(capturedEvent.get().result());
        assertFalse(capturedEvent.get().success());
        assertTrue(capturedEvent.get().error().startsWith("Syntax Error: "));
    }

    @Test
    public void testToJsonErrorFallbackProducesValidJsonRecognizedAsErrorResponse() throws Exception {
        ObjectMapper failingMapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                throw new JsonGenerationException("Simulated failure", (JsonGenerator) null);
            }
        };
        JexlTool tool = new JexlTool(new JexlServiceRegistry(), null, failingMapper, null);
        String result = tool.executeJexl("if (");
        assertEquals("Internal Error during error serialization",
                AiConfig.SHARED_OBJECT_MAPPER.readTree(result).get("error").asText());
    }

    @Test
    public void testExecuteJexlInterrupted() {
        JexlTool tool = createTool();
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> tool.executeJexl("1 + 1"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void testExecuteJexlLoopInterruptedPropagatesCancelAsCancellationException() throws Exception {
        TestService testService = new TestService();
        AtomicReference<ToolExecutionEvent> capturedEvent = new AtomicReference<>();
        JexlTool tool = createTool(capturedEvent, testService);

        AtomicReference<Throwable> thrownRef = new AtomicReference<>();
        AtomicBoolean interruptFlagPreserved = new AtomicBoolean(false);
        Thread worker = new Thread(() -> {
            try {
                tool.executeJexl("testService.signalStarted(); while (true) { var x = 1; }");
            } catch (Throwable t) {
                thrownRef.set(t);
                interruptFlagPreserved.set(Thread.currentThread().isInterrupted());
            }
        });
        worker.start();

        assertTrue(testService.loopStarted.await(5, TimeUnit.SECONDS));
        worker.interrupt();
        worker.join(5000);

        assertFalse(worker.isAlive());
        assertInstanceOf(CancellationException.class, thrownRef.get());
        assertTrue(interruptFlagPreserved.get());
        assertFalse(capturedEvent.get().success());
        assertEquals("Execution cancelled", capturedEvent.get().error());
    }

    @Test
    public void testExecuteJexlInterruptedDuringScriptExecutionThrowsCancellationException() {
        JexlTool tool = createTool(new TestService());
        try {
            assertThrows(CancellationException.class, () -> tool.executeJexl("testService.interruptSelfAndReturn()"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void testExecuteJexlServiceExceptionsUnwrappedAndClosedByInterruptCancelled() {
        AtomicReference<ToolExecutionEvent> capturedEvent = new AtomicReference<>();
        JexlTool tool = createTool(capturedEvent, new TestService());

        assertTrue(tool.executeJexl("testService.throwIllegalArgument()").contains("File does not exist: missing.txt"));
        assertFalse(capturedEvent.get().success());
        assertEquals("File does not exist: missing.txt", capturedEvent.get().error());

        try {
            assertThrows(CancellationException.class, () -> tool.executeJexl("testService.throwClosedByInterrupt()"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
