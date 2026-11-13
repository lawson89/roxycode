package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
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
}
