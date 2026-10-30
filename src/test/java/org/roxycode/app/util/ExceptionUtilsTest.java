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

        assertFalse(ExceptionUtils.isCancellation(root));
        assertDoesNotThrow(() -> ExceptionUtils.rethrowIfCancelled(root, "Should not throw"));
        assertDoesNotThrow(() -> ExceptionUtils.checkInterrupted("Not interrupted"));

        assertTrue(ExceptionUtils.isCancellation(new RuntimeException(new InterruptedException("sleep interrupted"))));

        try {
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
