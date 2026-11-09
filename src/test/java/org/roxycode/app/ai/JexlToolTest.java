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
import org.roxycode.app.ai.services.UserInputService;
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
                {
                  "buildSuccess": res.success, "buildExit": res.exitCode, "buildErrors": res.errors,
                  "edSuccess": ed.success, "edContent": ed.content, "edHint": ed.errorHint
                }
                """);
        assertFalse(node.get("buildSuccess").asBoolean());
        assertEquals(1, node.get("buildExit").asInt());
        assertEquals("App.java:10: error", node.get("buildErrors").get(0).asText());
        assertTrue(node.get("edSuccess").asBoolean());
        assertEquals("matched content", node.get("edContent").asText());
        assertTrue(node.get("edHint").isNull());
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
        JexlTool tool = new JexlTool(new JexlServiceRegistry(null, null), event -> {
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
        assertTrue(createTool(capturedEvent).executeJexl("if (").contains("Syntax Error"));
        assertEquals("if (", capturedEvent.get().script());
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
    public void testJsPolyfillPropertiesAndMethodsForStringsCollectionsArraysAndMaps() throws Exception {
        JexlTool tool = createTool(new TestService());
        JsonNode node = evalJson(tool, """
                var str = '  hello world  ';
                var list = ['alpha', 'beta', ...];
                var pushedLen = list.push('gamma', 'delta');
                var arr = ['one', 'two', 'three'];
                var map = { 'a': 1, 'b': 2, 'c': 3 };
                var deletedB = map.delete('b');
                var deletedMissing = map.delete('zzz');
                {
                  "strLen": str.length,
                  "listLen": list.length,
                  "arrLen": arr.length,
                  "mapLen": map.length,
                  "pushedLen": pushedLen,
                  "strIncludes": str.includes('world'),
                  "strIncludesFalse": str.includes('missing'),
                  "strSlicePos": 'abcdef'.slice(1, 4),
                  "strSliceNeg": 'abcdef'.slice(-3, -1),
                  "strSliceTail": 'abcdef'.slice(-2),
                  "strTrimStart": str.trimStart(),
                  "strTrimEnd": str.trimEnd(),
                  "padStart": '7'.padStart(3, '0'),
                  "padEnd": '7'.padEnd(4, '.'),
                  "listIncludes": list.includes('gamma'),
                  "arrIncludes": arr.includes('two'),
                  "listJoinDefault": ['a', 'b', 'c'].join(),
                  "listJoinCustom": list.join(' | '),
                  "arrJoin": arr.join('-'),
                  "listIndexOf": list.indexOf('gamma'),
                  "arrIndexOf": arr.indexOf('three'),
                  "arrIndexOfMissing": arr.indexOf('nope'),
                  "listSlice": list.slice(1, -1),
                  "arrSlice": arr.slice(-2),
                  "mapHasA": map.has('a'),
                  "mapHasB": map.has('b'),
                  "deletedB": deletedB,
                  "deletedMissing": deletedMissing
                }
                """);

        assertEquals(15, node.get("strLen").asInt());
        assertEquals(4, node.get("listLen").asInt());
        assertEquals(3, node.get("arrLen").asInt());
        assertEquals(2, node.get("mapLen").asInt());
        assertEquals(4, node.get("pushedLen").asInt());
        assertTrue(node.get("strIncludes").asBoolean());
        assertFalse(node.get("strIncludesFalse").asBoolean());
        assertEquals("bcd", node.get("strSlicePos").asText());
        assertEquals("de", node.get("strSliceNeg").asText());
        assertEquals("ef", node.get("strSliceTail").asText());
        assertEquals("hello world  ", node.get("strTrimStart").asText());
        assertEquals("  hello world", node.get("strTrimEnd").asText());
        assertEquals("007", node.get("padStart").asText());
        assertEquals("7...", node.get("padEnd").asText());
        assertTrue(node.get("listIncludes").asBoolean());
        assertTrue(node.get("arrIncludes").asBoolean());
        assertEquals("a,b,c", node.get("listJoinDefault").asText());
        assertEquals("alpha | beta | gamma | delta", node.get("listJoinCustom").asText());
        assertEquals("one-two-three", node.get("arrJoin").asText());
        assertEquals(2, node.get("listIndexOf").asInt());
        assertEquals(2, node.get("arrIndexOf").asInt());
        assertEquals(-1, node.get("arrIndexOfMissing").asInt());
        assertEquals(2, node.get("listSlice").size());
        assertEquals("beta", node.get("listSlice").get(0).asText());
        assertEquals("gamma", node.get("listSlice").get(1).asText());
        assertEquals(2, node.get("arrSlice").size());
        assertEquals("two", node.get("arrSlice").get(0).asText());
        assertEquals("three", node.get("arrSlice").get(1).asText());
        assertTrue(node.get("mapHasA").asBoolean());
        assertFalse(node.get("mapHasB").asBoolean());
        assertTrue(node.get("deletedB").asBoolean());
        assertFalse(node.get("deletedMissing").asBoolean());
    }

    @Test
    public void testJsPolyfillHigherOrderLambdaMethodsAndGlobals() throws Exception {
        JexlTool tool = createTool(new TestService());
        String raw = tool.executeJexl("""
                var nums = [1, 2, 3, 4, ...];
                var arr = ['a', 'b', 'c'];
                var forEachAcc = [...];
                nums.forEach(n => forEachAcc.push(n * 10));
                var serviceMapped = ['Roxy', 'Dev'].map(name => testService.hello(name));
                var indexed = arr.map((val, idx) => idx + ':' + val);
                var obj = { 'x': 10, 'y': 20 };
                var jsonStr = JSON.stringify(obj);
                var parsed = JSON.parse(jsonStr);
                {
                  "mapped": nums.map(x => x * 2),
                  "filtered": nums.filter(x => x > 2),
                  "found": nums.find(x => x == 3),
                  "foundMissing": nums.find(x => x > 99),
                  "someTrue": nums.some(x => x == 4),
                  "someFalse": nums.some(x => x < 0),
                  "everyTrue": nums.every(x => x > 0),
                  "everyFalse": nums.every(x => x < 3),
                  "forEachAcc": forEachAcc,
                  "serviceMapped": serviceMapped,
                  "indexed": indexed,
                  "keys": Object.keys(obj),
                  "values": Object.values(obj),
                  "entries": Object.entries(obj),
                  "parsedX": parsed.x,
                  "prettyJson": JSON.stringify(obj, null, 2).includes('  "x"')
                }
                """);
        JsonNode node = AiConfig.SHARED_OBJECT_MAPPER.readTree(raw);
        assertEquals(List.of(2, 4, 6, 8), AiConfig.SHARED_OBJECT_MAPPER.convertValue(node.get("mapped"), List.class));
        assertEquals(List.of(3, 4), AiConfig.SHARED_OBJECT_MAPPER.convertValue(node.get("filtered"), List.class));
        assertEquals(3, node.get("found").asInt());
        assertTrue(node.get("foundMissing").isNull());
        assertTrue(node.get("someTrue").asBoolean());
        assertFalse(node.get("someFalse").asBoolean());
        assertTrue(node.get("everyTrue").asBoolean());
        assertFalse(node.get("everyFalse").asBoolean());
        assertEquals(List.of(10, 20, 30, 40),
                AiConfig.SHARED_OBJECT_MAPPER.convertValue(node.get("forEachAcc"), List.class));
        assertEquals(List.of("Hello, Roxy", "Hello, Dev"),
                AiConfig.SHARED_OBJECT_MAPPER.convertValue(node.get("serviceMapped"), List.class));
        assertEquals(List.of("0:a", "1:b", "2:c"),
                AiConfig.SHARED_OBJECT_MAPPER.convertValue(node.get("indexed"), List.class));
        assertEquals(List.of("x", "y"), AiConfig.SHARED_OBJECT_MAPPER.convertValue(node.get("keys"), List.class));
        assertEquals(List.of(10, 20), AiConfig.SHARED_OBJECT_MAPPER.convertValue(node.get("values"), List.class));
        assertEquals(2, node.get("entries").size());
        assertEquals("x", node.get("entries").get(0).get(0).asText());
        assertEquals(10, node.get("entries").get(0).get(1).asInt());
        assertEquals(10, node.get("parsedX").asInt());
        assertTrue(node.get("prettyJson").asBoolean());
    }
}
