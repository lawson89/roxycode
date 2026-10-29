package org.roxycode.app.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import org.junit.jupiter.api.Test;

class EdtTest {

    @Test
    void testRunNullIsNoOp() {
        assertDoesNotThrow(() -> Edt.run(null));
    }

    @Test
    void testRunOnEdtExecutesImmediately() throws Exception {
        AtomicBoolean executedSynchronously = new AtomicBoolean(false);
        SwingUtilities.invokeAndWait(() -> {
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

        Edt.run(() -> {
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

    @Test
    void testSupplyAsyncExecutesBackgroundOffEdtAndSuccessOnEdt() throws Exception {
        AtomicBoolean taskRanOnEdt = new AtomicBoolean(true);
        AtomicBoolean callbackRanOnEdt = new AtomicBoolean(false);
        AtomicReference<String> callbackResult = new AtomicReference<>();
        CountDownLatch callbackLatch = new CountDownLatch(1);

        SwingWorker<String, Void> worker = Edt.supplyAsync(() -> {
            taskRanOnEdt.set(SwingUtilities.isEventDispatchThread());
            return "computed-value";
        }, result -> {
            callbackRanOnEdt.set(SwingUtilities.isEventDispatchThread());
            callbackResult.set(result);
            callbackLatch.countDown();
        });

        assertEquals("computed-value", worker.get(5, TimeUnit.SECONDS));
        assertTrue(callbackLatch.await(5, TimeUnit.SECONDS));
        assertFalse(taskRanOnEdt.get(), "Background task must run off the EDT");
        assertTrue(callbackRanOnEdt.get(), "Success callback must run on the EDT");
        assertEquals("computed-value", callbackResult.get());
    }

    @Test
    void testSupplyAsyncErrorCallbackRunsOnEdtWhenTaskThrows() throws Exception {
        AtomicBoolean errorRanOnEdt = new AtomicBoolean(false);
        AtomicReference<Exception> capturedError = new AtomicReference<>();
        AtomicBoolean successCalled = new AtomicBoolean(false);
        CountDownLatch errorLatch = new CountDownLatch(1);

        SwingWorker<String, Void> worker = Edt.supplyAsync(() -> {
            throw new IllegalStateException("boom");
        }, result -> successCalled.set(true), ex -> {
            errorRanOnEdt.set(SwingUtilities.isEventDispatchThread());
            capturedError.set(ex);
            errorLatch.countDown();
        });

        assertThrows(Exception.class, () -> worker.get(5, TimeUnit.SECONDS));
        assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
        assertFalse(successCalled.get());
        assertTrue(errorRanOnEdt.get(), "Error callback must run on the EDT");
        assertNotNull(capturedError.get());
        assertEquals("boom", capturedError.get().getMessage());
    }

    @Test
    void testSupplyAsyncSkipsCallbacksWhenCancelled() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean successCalled = new AtomicBoolean(false);

        SwingWorker<String, Void> worker = Edt.supplyAsync(() -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "cancelled-result";
        }, result -> successCalled.set(true));

        assertTrue(started.await(5, TimeUnit.SECONDS));
        worker.cancel(false);
        release.countDown();
        SwingUtilities.invokeAndWait(() -> {
        });
        assertFalse(successCalled.get(), "Cancelled worker must not invoke success callback");
    }
}
