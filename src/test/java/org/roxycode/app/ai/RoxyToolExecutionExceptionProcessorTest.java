package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

class RoxyToolExecutionExceptionProcessorTest {

    private final RoxyToolExecutionExceptionProcessor processor =
            new RoxyToolExecutionExceptionProcessor();

    @Test
    void testProcessFormatsRootCauseWithErrorPrefix() {
        ToolDefinition toolDef = mock(ToolDefinition.class);
        ToolExecutionException ex =
                new ToolExecutionException(
                        toolDef, new IllegalArgumentException("Invalid parameter"));

        assertEquals("Error: Invalid parameter", processor.process(ex));
    }

    @Test
    void testProcessPreservesExistingErrorPrefix() {
        ToolDefinition toolDef = mock(ToolDefinition.class);
        ToolExecutionException ex =
                new ToolExecutionException(
                        toolDef, new IllegalStateException("Error: Plan is missing"));

        assertEquals("Error: Plan is missing", processor.process(ex));
    }

    @Test
    void testProcessRethrowsCancellationException() {
        ToolDefinition toolDef = mock(ToolDefinition.class);
        ToolExecutionException ex =
                new ToolExecutionException(toolDef, new CancellationException("User stopped"));

        assertThrows(CancellationException.class, () -> processor.process(ex));
    }
}
