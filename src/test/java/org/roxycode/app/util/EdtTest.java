package org.roxycode.app.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class EdtTest {

    @Test
    void testRunNullIsNoOp() {
        assertDoesNotThrow(() -> Edt.run(null));
    }

    @Test
    void testRunOnEdtExecutesImmediately() throws Exception {
        AtomicBoolean executedSynchronously = new AtomicBoolean(false);
        SwingUtilities.invokeAndWait(
                () -> {
                    AtomicBoolean inner = new AtomicBoolean(false);
                    Edt.run(() -> inner.set(true));
                    executedSynchronously.set(inner.get());
                });
        assertTrue(executedSynchronously.get(), "Edt.run should execute immediately when on EDT");
    }

    @Test
    void testRunOffEdtDispatchesToEdt() throws Exception {
        assertFalse(SwingUtilities.isEventDispatchThread());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean ranOnEdt = new AtomicBoolean(false);

        Edt.run(
                () -> {
                    ranOnEdt.set(SwingUtilities.isEventDispatchThread());
                    latch.countDown();
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Action should be executed on EDT");
        assertTrue(ranOnEdt.get(), "Action must run on the Event Dispatch Thread");
    }

    @Test
    void testRunAndWaitOnAndOffEdt() throws Exception {
        assertDoesNotThrow(() -> Edt.runAndWait(null));

        assertFalse(SwingUtilities.isEventDispatchThread());
        AtomicBoolean ranOnEdt = new AtomicBoolean(false);
        Edt.runAndWait(() -> ranOnEdt.set(SwingUtilities.isEventDispatchThread()));
        assertTrue(ranOnEdt.get(), "Edt.runAndWait should execute synchronously on the EDT");

        AtomicBoolean ranImmediatelyOnEdt = new AtomicBoolean(false);
        SwingUtilities.invokeAndWait(() -> Edt.runAndWait(() -> ranImmediatelyOnEdt.set(true)));
        assertTrue(ranImmediatelyOnEdt.get(), "Edt.runAndWait on EDT should execute immediately");
    }
}
