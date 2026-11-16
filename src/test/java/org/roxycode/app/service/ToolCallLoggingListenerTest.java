package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.events.ToolExecutionEvent;
import org.slf4j.Logger;

class ToolCallLoggingListenerTest {

    @Test
    void testLogsSuccessAndFailureEventsAsSingleLineJsonl() throws Exception {
        Logger mockLogger = mock(Logger.class);
        ToolCallLoggingListener listener = new ToolCallLoggingListener(AiConfig.SHARED_OBJECT_MAPPER, mockLogger);

        listener.onToolExecution(null);
        verifyNoInteractions(mockLogger);

        ToolExecutionEvent successEvent = new ToolExecutionEvent("qa:1", "EXPLORE", "1 + 1", 2, true, null, null, null,
                null, null, null);
        listener.onToolExecution(successEvent);

        ArgumentCaptor<String> infoCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockLogger, times(1)).info(infoCaptor.capture());
        String infoJson = infoCaptor.getValue();
        assertFalse(infoJson.contains("\n"));

        JsonNode infoNode = AiConfig.SHARED_OBJECT_MAPPER.readTree(infoJson);
        assertNotNull(infoNode.get("timestamp").asText());
        assertEquals("qa:1", infoNode.get("conversationId").asText());
        assertEquals("EXPLORE", infoNode.get("phase").asText());
        assertTrue(infoNode.get("success").asBoolean());
        assertEquals("1 + 1", infoNode.get("script").asText());
        assertEquals(2, infoNode.get("result").asInt());
        assertTrue(infoNode.get("errorCategory").isNull());

        ToolExecutionEvent failureEvent = new ToolExecutionEvent("task:2", "CODE", "const x = 1;", null, false,
                "JS_CONST_LET", 1, 1, "const x = 1;", "Syntax Error", "Use 'var'");
        listener.onToolExecution(failureEvent);

        ArgumentCaptor<String> warnCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockLogger, times(1)).warn(warnCaptor.capture());
        String warnJson = warnCaptor.getValue();
        assertFalse(warnJson.contains("\n"));

        JsonNode warnNode = AiConfig.SHARED_OBJECT_MAPPER.readTree(warnJson);
        assertFalse(warnNode.get("success").asBoolean());
        assertEquals("CODE", warnNode.get("phase").asText());
        assertEquals("JS_CONST_LET", warnNode.get("errorCategory").asText());
        assertEquals(1, warnNode.get("line").asInt());
        assertEquals(1, warnNode.get("column").asInt());
        assertEquals("const x = 1;", warnNode.get("offendingLine").asText());
        assertEquals("Syntax Error", warnNode.get("error").asText());
        assertEquals("Use 'var'", warnNode.get("hint").asText());
    }

    @Test
    void testFallsBackToStringValueWhenResultIsNotJsonSerializable() throws Exception {
        Logger mockLogger = mock(Logger.class);
        ToolCallLoggingListener listener = new ToolCallLoggingListener(AiConfig.SHARED_OBJECT_MAPPER, mockLogger);

        Object unserializable = new Object() {
            @Override
            public String toString() {
                return "unserializable-marker";
            }
        };
        listener.onToolExecution(new ToolExecutionEvent("conv", "PLAN", "svc.op()", Map.of("obj", unserializable), true,
                null, null, null, null, null, null));

        ArgumentCaptor<String> infoCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockLogger).info(infoCaptor.capture());
        JsonNode node = AiConfig.SHARED_OBJECT_MAPPER.readTree(infoCaptor.getValue());
        assertTrue(node.get("result").asText().contains("unserializable-marker"));
    }

    @Test
    void testLogsFailureDiagnosticsToJexlErrorsLoggerWithoutResult() throws Exception {
        Logger mockToolCallLogger = mock(Logger.class);
        Logger mockErrorsLogger = mock(Logger.class);
        ToolCallLoggingListener listener = new ToolCallLoggingListener(AiConfig.SHARED_OBJECT_MAPPER,
                mockToolCallLogger, mockErrorsLogger);

        ToolExecutionEvent successEvent = new ToolExecutionEvent("qa:1", "EXPLORE", "1 + 1", 2, true, null, null, null,
                null, null, null, "gemini-4-carbon", 1, 5L, false, null, null, null, null);
        listener.onToolExecution(successEvent);
        verify(mockToolCallLogger, times(1)).info(anyString());
        verifyNoInteractions(mockErrorsLogger);

        ToolExecutionEvent failureEvent = new ToolExecutionEvent("task:9", "CODE", "counts[f] = 1;", null, false,
                "UNSOLVABLE_PROPERTY", 1, 7, "counts[f] = 1;", "undefined property 'a/b'", "Check map",
                "gemini-4-hydrogen", 3, 42L, false, "org.apache.commons.jexl3.JexlException$Property",
                "java.lang.IllegalArgumentException",
                List.of("Property: undefined property", "IllegalArgumentException: bad"),
                List.of("JexlTool.executeJexl(JexlTool.java:132)"));
        listener.onToolExecution(failureEvent);

        verify(mockToolCallLogger, times(1)).warn(anyString());
        ArgumentCaptor<String> errCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockErrorsLogger, times(1)).warn(errCaptor.capture());

        String errJson = errCaptor.getValue();
        assertFalse(errJson.contains("\n"));
        JsonNode errNode = AiConfig.SHARED_OBJECT_MAPPER.readTree(errJson);
        assertFalse(errNode.has("result"));
        assertEquals("task:9", errNode.get("conversationId").asText());
        assertEquals("CODE", errNode.get("phase").asText());
        assertEquals("gemini-4-hydrogen", errNode.get("modelName").asText());
        assertEquals(3, errNode.get("turnNumber").asInt());
        assertEquals(42L, errNode.get("durationMs").asLong());
        assertFalse(errNode.get("syntaxStage").asBoolean());
        assertEquals("UNSOLVABLE_PROPERTY", errNode.get("errorCategory").asText());
        assertEquals("org.apache.commons.jexl3.JexlException$Property", errNode.get("exceptionType").asText());
        assertEquals("java.lang.IllegalArgumentException", errNode.get("rootCauseType").asText());
        assertEquals(2, errNode.get("causeChain").size());
        assertEquals(1, errNode.get("appStackFrames").size());
        assertEquals("counts[f] = 1;", errNode.get("offendingLine").asText());
        assertEquals("undefined property 'a/b'", errNode.get("error").asText());
        assertEquals("Check map", errNode.get("hint").asText());
        assertEquals("counts[f] = 1;", errNode.get("script").asText());
    }
}
