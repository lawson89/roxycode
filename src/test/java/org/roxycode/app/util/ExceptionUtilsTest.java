package org.roxycode.app.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class ExceptionUtilsTest {

    @Test
    void testCauseResolutionAndCancellationHandling() {
        IOException root = new IOException("disk full");
        IllegalStateException mid = new IllegalStateException("state error", root);
        RuntimeException top = new RuntimeException("top error", mid);

        assertSame(root, ExceptionUtils.findCause(top, IOException.class));
        assertSame(mid, ExceptionUtils.findCause(top, IllegalStateException.class));
        assertNull(ExceptionUtils.findCause(top, InterruptedException.class));
        assertSame(root, ExceptionUtils.getRootCause(top));
        assertSame(root, ExceptionUtils.getRootCause(root));
        assertNull(ExceptionUtils.getRootCause(null));
        assertEquals("disk full", ExceptionUtils.getRootCauseMessage(top));
        assertEquals("wrapper message",
                ExceptionUtils.getRootCauseMessage(new RuntimeException("  wrapper message  ", new IOException())));
        assertEquals("IOException",
                ExceptionUtils.getRootCauseMessage(new RuntimeException((String) null, new IOException())));
        assertEquals("Unknown error", ExceptionUtils.getRootCauseMessage(null));

        assertFalse(ExceptionUtils.isCancellation(root));
        assertDoesNotThrow(() -> ExceptionUtils.rethrowIfCancelled(root, "Should not throw"));
        assertDoesNotThrow(() -> ExceptionUtils.checkInterrupted("Not interrupted"));

        assertTrue(ExceptionUtils.isCancellation(new RuntimeException(new InterruptedException("sleep interrupted"))));

        CancellationException directCancel = new CancellationException("Future cancelled");
        CancellationException wrappedCancel = assertThrows(CancellationException.class,
                () -> ExceptionUtils.rethrowIfCancelled(new RuntimeException(directCancel), "Fallback message"));
        assertEquals("Future cancelled", wrappedCancel.getMessage());
        assertFalse(Thread.currentThread().isInterrupted());

        assertSame(directCancel, ExceptionUtils.asCancellationException(directCancel, "Fallback message"));
        assertFalse(Thread.currentThread().isInterrupted());

        try {
            CancellationException interruptedEx = ExceptionUtils
                    .asCancellationException(new InterruptedException("sleep interrupted"), "Interrupted default");
            assertEquals("Interrupted default", interruptedEx.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
            Thread.interrupted();

            CancellationException thrown = assertThrows(CancellationException.class,
                    () -> ExceptionUtils.rethrowIfCancelled(new ClosedByInterruptException(), "Channel cancelled"));
            assertEquals("Channel cancelled", thrown.getMessage());
            assertInstanceOf(ClosedByInterruptException.class, thrown.getCause());
            assertTrue(Thread.currentThread().isInterrupted());

            CancellationException ex = assertThrows(CancellationException.class,
                    () -> ExceptionUtils.checkInterrupted("Thread was interrupted"));
            assertEquals("Thread was interrupted", ex.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
