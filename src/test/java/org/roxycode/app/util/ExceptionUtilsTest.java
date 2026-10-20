package org.roxycode.app.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class ExceptionUtilsTest {

    @Test
    void testFindCauseAndGetRootCause() {
        IOException root = new IOException("disk full");
        IllegalStateException mid = new IllegalStateException("state error", root);
        RuntimeException top = new RuntimeException("top error", mid);

        assertSame(root, ExceptionUtils.findCause(top, IOException.class));
        assertSame(mid, ExceptionUtils.findCause(top, IllegalStateException.class));
        assertNull(ExceptionUtils.findCause(top, InterruptedException.class));
        assertSame(root, ExceptionUtils.getRootCause(top));
        assertNull(ExceptionUtils.getRootCause(null));
    }

    @Test
    void testHasCauseWithMessage() {
        IllegalArgumentException root = new IllegalArgumentException("MAX_TOOL_TURNS_EXCEEDED");
        RuntimeException top = new RuntimeException("Wrapper", root);

        assertTrue(ExceptionUtils.hasCauseWithMessage(top, "MAX_TOOL_TURNS_EXCEEDED"));
        assertFalse(ExceptionUtils.hasCauseWithMessage(top, "NON_EXISTENT"));
    }

    @Test
    void testIsCancellationAndRethrowIfCancelled() {
        assertFalse(ExceptionUtils.isCancellation(new IOException("regular io error")));
        assertDoesNotThrow(
                () ->
                        ExceptionUtils.rethrowIfCancelled(
                                new IOException("regular io error"), "Should not throw"));

        RuntimeException wrappedInterrupt =
                new RuntimeException(new InterruptedException("sleep interrupted"));
        assertTrue(ExceptionUtils.isCancellation(wrappedInterrupt));

        try {
            CancellationException thrown =
                    assertThrows(
                            CancellationException.class,
                            () ->
                                    ExceptionUtils.rethrowIfCancelled(
                                            new ClosedByInterruptException(), "Channel cancelled"));
            assertEquals("Channel cancelled", thrown.getMessage());
            assertInstanceOf(ClosedByInterruptException.class, thrown.getCause());
            assertTrue(
                    Thread.currentThread().isInterrupted(),
                    "Interrupt flag should be restored by rethrowIfCancelled");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void testCheckInterrupted() {
        assertDoesNotThrow(() -> ExceptionUtils.checkInterrupted("Not interrupted"));

        Thread.currentThread().interrupt();
        try {
            CancellationException ex =
                    assertThrows(
                            CancellationException.class,
                            () -> ExceptionUtils.checkInterrupted("Thread was interrupted"));
            assertEquals("Thread was interrupted", ex.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
