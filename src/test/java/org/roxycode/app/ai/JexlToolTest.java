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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.ai.services.BuildResult;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.FileReadService;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.UserInputService;
import org.roxycode.app.events.AgentActivityEvent;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.events.ToolExecutionEvent;
import org.roxycode.app.events.UserInputRequestEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.ProjectService;
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
                    List.of("AppTest#testFoo"));
        }

        @AgentDoc("Returns a sample EditorResult.")
        public EditorResult sampleEditorResult() {
            return new EditorResult(true, "matched content", null);
        }

        @AgentDoc("Returns a sample MemoryEntry without JavaBean getters.")
        public MemoryService.MemoryEntry sampleMemoryEntry() {
            return new MemoryService.MemoryEntry("arch-note", "Use records", List.of("java", "jexl"), "Details body",
                    "2026-11-18T12:00:00Z");
        }

        @AgentDoc("Returns sample MemoryMetadata records without JavaBean getters.")
        public List<MemoryService.MemoryMetadata> sampleMemoryMetadataList() {
            return List.of(
                    new MemoryService.MemoryMetadata("project", "first-id", "First summary", List.of("a"), true,
                            "2026-11-18T12:00:00Z"),
                    new MemoryService.MemoryMetadata("global", "second-id", "Second summary", List.of("b"), false,
                            "2026-11-18T12:01:00Z"));
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
        JexlServiceRegistry registry = new JexlServiceRegistry(null, null);
        for (Object service : services) {
            registry.register(service);
        }
        ApplicationEventPublisher publisher = null;
        if (capturedEvent != null) {
            publisher = event -> {
                if (event instanceof ToolExecutionEvent tee) {
                    capturedEvent.set(tee);
                }
            };
        }
        return new JexlTool(registry, publisher, null, null);
    }

    private static JsonNode evalJson(JexlTool tool, String script) throws Exception {
        return AiConfig.SHARED_OBJECT_MAPPER.readTree(tool.executeJexl(script));
    }

    @Test
    public void testExecuteJexlSerializesAndAccessesResultRecords() throws Exception {
        JexlTool tool = createTool(new TestService());
        JsonNode node = evalJson(tool, """
                var res = testService.sampleBuildResult();
                var ed = testService.sampleEditorResult();
                var mem = testService.sampleMemoryEntry();
                var metas = testService.sampleMemoryMetadataList();
                var metaIds = [...];
                var metaDetails = [...];
                for (var m : metas) {
                  metaIds.add(m.scope + ':' + m.id);
                  metaDetails.add(m.hasDetails);
                }
                {
                  "buildSuccess": res.success, "buildExit": res.exitCode, "buildErrors": res.errors,
                  "edSuccess": ed.success, "edContent": ed.content, "edHint": ed.errorHint,
                  "memId": mem.id, "memSummary": mem.summary, "memTags": mem.tags,
                  "memDetails": mem.details, "memUpdated": mem.updatedAt,
                  "metaIds": metaIds, "metaDetails": metaDetails
                }
                """);
        assertFalse(node.get("buildSuccess").asBoolean());
        assertEquals(1, node.get("buildExit").asInt());
        assertEquals("App.java:10: error", node.get("buildErrors").get(0).asText());
        assertTrue(node.get("edSuccess").asBoolean());
        assertEquals("matched content", node.get("edContent").asText());
        assertTrue(node.get("edHint").isNull());
        assertEquals("arch-note", node.get("memId").asText());
        assertEquals("Use records", node.get("memSummary").asText());
        assertEquals("java", node.get("memTags").get(0).asText());
        assertEquals("Details body", node.get("memDetails").asText());
        assertEquals("2026-11-18T12:00:00Z", node.get("memUpdated").asText());
        assertEquals("project:first-id", node.get("metaIds").get(0).asText());
        assertEquals("global:second-id", node.get("metaIds").get(1).asText());
        assertTrue(node.get("metaDetails").get(0).asBoolean());
        assertFalse(node.get("metaDetails").get(1).asBoolean());
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
        assertEquals("CODE", capturedEvent.get().phase());
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
        List<AgentActivityEvent> activityEvents = new ArrayList<>();
        List<ToolExecutionEvent> toolEvents = new ArrayList<>();
        JexlTool tool = new JexlTool(new JexlServiceRegistry(null, null), event -> {
            if (event instanceof AgentTurnEvent ate) {
                turnEvents.add(ate);
            } else if (event instanceof AgentActivityEvent aae) {
                activityEvents.add(aae);
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
        assertEquals("1 + 1 (1 line)", turnEvents.get(0).scriptSummary());
        assertEquals(2, turnEvents.get(1).turnNumber());
        assertEquals("2 + 2 (1 line)", turnEvents.get(1).scriptSummary());
        assertEquals("CODE", turnEvents.get(0).phase());
        assertEquals(2, activityEvents.size());
        assertEquals(new AgentActivityEvent("conv-1", "Analyzing tool output..."), activityEvents.get(0));
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
    public void testSandboxAllowsCollectionsMapsStringsAndBlocksUnauthorizedCalls() throws Exception {
        JexlTool tool = createTool(new TestService());
        JsonNode result = evalJson(tool, """
                var list = ['alpha', 'beta', ...];
                list.add('gamma');
                var map = { 'greeting': testService.hello('Roxy').trim().toUpperCase(), 'count': list.size() };
                map
                """);
        assertEquals("HELLO, ROXY", result.get("greeting").asText());
        assertEquals(3, result.get("count").asInt());
        assertTrue(evalJson(tool, "new('java.lang.ProcessBuilder', ['ls'])").has("error"));
        assertTrue(evalJson(tool, "testService.internalMethodWithoutAgentDoc()").has("error"));
    }

    @Test
    public void testExecuteJexlSyntaxAndSerializationErrorHandling() throws Exception {
        AtomicReference<ToolExecutionEvent> capturedEvent = new AtomicReference<>();
        JsonNode syntaxErrNode = AiConfig.SHARED_OBJECT_MAPPER
                .readTree(createTool(capturedEvent).executeJexl("var m = ['key': 1];"));
        assertTrue(syntaxErrNode.get("error").asText().startsWith("Syntax Error: "));
        assertTrue(syntaxErrNode.get("hint").asText().contains("JEXL 3 syntax"));
        assertEquals("var m = ['key': 1];", syntaxErrNode.get("offendingLine").asText());
        assertEquals("var m = ['key': 1];", capturedEvent.get().script());
        assertEquals("SYNTAX_ERROR", capturedEvent.get().errorCategory());
        assertEquals(1, capturedEvent.get().line());
        assertNotNull(capturedEvent.get().hint());
        assertNull(capturedEvent.get().result());
        assertFalse(capturedEvent.get().success());
        assertTrue(capturedEvent.get().error().startsWith("Syntax Error: "));

        ObjectMapper failingMapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                throw new JsonGenerationException("Simulated failure", (JsonGenerator) null);
            }
        };
        JexlTool failingTool = new JexlTool(new JexlServiceRegistry(null, null), null, failingMapper, null);
        String result = failingTool.executeJexl("if (");
        assertEquals("Internal Error during error serialization",
                AiConfig.SHARED_OBJECT_MAPPER.readTree(result).get("error").asText());
    }

    @Test
    public void testExecuteJexlInterruptionAndExceptionUnwrapping() throws Exception {
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

        assertTrue(tool.executeJexl("testService.throwIllegalArgument()").contains("File does not exist: missing.txt"));
        assertFalse(capturedEvent.get().success());
        assertEquals("File does not exist: missing.txt", capturedEvent.get().error());

        try {
            assertThrows(CancellationException.class, () -> tool.executeJexl("testService.throwClosedByInterrupt()"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertThrows(CancellationException.class, () -> tool.executeJexl("1 + 1"));
            Thread.interrupted();

            assertThrows(CancellationException.class, () -> tool.executeJexl("testService.interruptSelfAndReturn()"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void testExecuteJexlFileReadServiceWithArrayLiterals(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "alpha");
        Files.writeString(tempDir.resolve("b.txt"), "beta");
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.resolveReadPath("a.txt")).thenReturn(tempDir.resolve("a.txt"));
        when(projectService.resolveReadPath("b.txt")).thenReturn(tempDir.resolve("b.txt"));

        JexlTool tool = createTool(new FileReadService(projectService));
        JsonNode plainNode = evalJson(tool, "fileReadService.readFiles(['a.txt', 'b.txt'])");
        assertEquals("alpha", plainNode.get("a.txt").asText());
        assertEquals("beta", plainNode.get("b.txt").asText());

        JsonNode numberedNode = evalJson(tool, "fileReadService.readFilesNumbered(['a.txt', 'b.txt'])");
        assertEquals("1: alpha", numberedNode.get("a.txt").asText());
        assertEquals("1: beta", numberedNode.get("b.txt").asText());
    }

    @Test
    public void testExecuteJexlBindsUserInputServiceToTurnToolContextConversationId() throws Exception {
        List<UserInputRequestEvent> capturedPrompts = new ArrayList<>();
        UserInputService userInputService = new UserInputService(event -> {
            if (event instanceof UserInputRequestEvent uire) {
                capturedPrompts.add(uire);
                switch (uire.promptType()) {
                    case CONFIRM -> uire.responseFuture().complete(Boolean.TRUE);
                    case TEXT -> uire.responseFuture().complete(uire.defaultValue() + "-ok");
                    case SINGLE_SELECT -> uire.responseFuture().complete(uire.options().get(0));
                    case MULTI_SELECT -> uire.responseFuture().complete(uire.options());
                }
            }
        });

        JexlTool tool = createTool(userInputService);
        ToolContext qaContext = new ToolContext(new TurnToolContext("qa:default", WorkflowPhase.EXPLORE, null).toMap());
        String json = tool.executeJexl("""
                {
                  "confirmed": userInputService.confirm('Continue?', 'Details here'),
                  "text": userInputService.askText('Branch?', 'dev'),
                  "single": userInputService.selectOption('Tool?', ['Maven', 'Gradle']),
                  "multi": userInputService.selectMultiple('Modules?', ['core', 'ui'])
                }
                """, qaContext);

        JsonNode node = AiConfig.SHARED_OBJECT_MAPPER.readTree(json);
        assertTrue(node.get("confirmed").asBoolean());
        assertEquals("dev-ok", node.get("text").asText());
        assertEquals("Maven", node.get("single").asText());
        assertEquals(2, node.get("multi").size());
        assertEquals(4, capturedPrompts.size());
        for (UserInputRequestEvent prompt : capturedPrompts) {
            assertEquals("qa:default", prompt.conversationId());
        }

        // Non-@AgentDoc lifecycle methods must be blocked by JEXL permissions
        assertTrue(evalJson(tool, "userInputService.forConversation('other')").has("error"));
        assertTrue(evalJson(tool, "userInputService.cancelPendingRequest('qa:default')").has("error"));
    }

    @Test
    public void testRetainedLengthIncludesAndMapPropertyAccess() throws Exception {
        JexlTool tool = createTool(new TestService());
        JsonNode node = evalJson(tool, """
                var str = '  hello world  ';
                var list = ['alpha', 'beta', ...];
                list.add('gamma');
                var arr = ['one', 'two', 'three'];
                var map = { 'a': 1, 'b': 2 };
                map.c = 3;
                {
                  "strLen": str.length,
                  "listLen": list.length,
                  "arrLen": arr.length,
                  "mapLen": map.length,
                  "mapC": map.c,
                  "strIncludes": str.includes('world'),
                  "strIncludesFalse": str.includes('missing'),
                  "listIncludes": list.includes('gamma'),
                  "arrIncludes": arr.includes('two'),
                  "arrIncludesFalse": arr.includes('nope')
                }
                """);

        assertEquals(15, node.get("strLen").asInt());
        assertEquals(3, node.get("listLen").asInt());
        assertEquals(3, node.get("arrLen").asInt());
        assertEquals(3, node.get("mapLen").asInt());
        assertEquals(3, node.get("mapC").asInt());
        assertTrue(node.get("strIncludes").asBoolean());
        assertFalse(node.get("strIncludesFalse").asBoolean());
        assertTrue(node.get("listIncludes").asBoolean());
        assertTrue(node.get("arrIncludes").asBoolean());
        assertFalse(node.get("arrIncludesFalse").asBoolean());
    }

    @Test
    public void testEmptyMapBracketAssignmentInStrictModeAndFailureDiagnostics() throws Exception {
        AtomicReference<ToolExecutionEvent> capturedEvent = new AtomicReference<>();
        JexlTool tool = createTool(capturedEvent, new TestService());

        String raw = tool.executeJexl("""
                var counts = {};
                for (var f : ['a/b.txt', 'c/d.txt']) {
                    counts[f] = f.length;
                }
                counts;
                """);
        JsonNode node = AiConfig.SHARED_OBJECT_MAPPER.readTree(raw);
        assertTrue(node.has("a/b.txt"), "Expected map key in response, got: " + raw);
        assertEquals(7, node.get("a/b.txt").asInt());
        assertEquals(7, node.get("c/d.txt").asInt());

        TurnToolContext turnCtx = new TurnToolContext("conv-diag", WorkflowPhase.CODE, new AtomicInteger(2),
                "gemini-4-hydrogen");
        String failRaw = tool.executeJexl("testService.throwIllegalArgument();", new ToolContext(turnCtx.toMap()));
        JsonNode failNode = AiConfig.SHARED_OBJECT_MAPPER.readTree(failRaw);
        assertTrue(failNode.get("error").asText().contains("missing.txt"));

        ToolExecutionEvent event = capturedEvent.get();
        assertNotNull(event);
        assertFalse(event.success());
        assertEquals("conv-diag", event.conversationId());
        assertEquals("CODE", event.phase());
        assertEquals("gemini-4-hydrogen", event.modelName());
        assertEquals(3, event.turnNumber());
        assertNotNull(event.durationMs());
        assertTrue(event.durationMs() >= 0L);
        assertFalse(event.syntaxStage());
        assertNotNull(event.exceptionType());
        assertEquals(IllegalArgumentException.class.getName(), event.rootCauseType());
        assertNotNull(event.causeChain());
        assertFalse(event.causeChain().isEmpty());
        assertNotNull(event.appStackFrames());
        assertFalse(event.appStackFrames().isEmpty());
    }
}
