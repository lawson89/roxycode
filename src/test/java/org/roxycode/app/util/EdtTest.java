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

    @Test
    void testSupplyAsyncWithActiveWorkerRefSuppressesSupersededSuccessAndErrorCallbacks() throws Exception {
        AtomicReference<SwingWorker<String, Void>> activeRef = new AtomicReference<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondDoneLatch = new CountDownLatch(1);
        AtomicReference<String> appliedValue = new AtomicReference<>();

        SwingWorker<String, Void> staleWorker = Edt.supplyAsync(activeRef, () -> {
            firstStarted.countDown();
            releaseFirst.await(5, TimeUnit.SECONDS);
            return "stale";
        }, appliedValue::set);

        assertSame(staleWorker, activeRef.get());
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

        SwingWorker<String, Void> freshWorker = Edt.supplyAsync(activeRef, () -> "fresh", val -> {
            appliedValue.set(val);
            secondDoneLatch.countDown();
        });

        assertSame(freshWorker, activeRef.get());
        assertEquals("fresh", freshWorker.get(5, TimeUnit.SECONDS));
        assertTrue(secondDoneLatch.await(5, TimeUnit.SECONDS));

        releaseFirst.countDown();
        assertEquals("stale", staleWorker.get(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
        });
        assertEquals("fresh", appliedValue.get(), "Stale worker must not overwrite fresh result");

        CountDownLatch failStarted = new CountDownLatch(1);
        CountDownLatch releaseFail = new CountDownLatch(1);
        AtomicBoolean staleErrorCalled = new AtomicBoolean(false);

        SwingWorker<String, Void> staleFailingWorker = Edt.supplyAsync(activeRef, () -> {
            failStarted.countDown();
            releaseFail.await(5, TimeUnit.SECONDS);
            throw new IllegalStateException("stale-failure");
        }, appliedValue::set, ex -> staleErrorCalled.set(true));

        assertTrue(failStarted.await(5, TimeUnit.SECONDS));
        SwingWorker<String, Void> latestWorker = Edt.supplyAsync(activeRef, () -> "latest", appliedValue::set);
        assertEquals("latest", latestWorker.get(5, TimeUnit.SECONDS));

        releaseFail.countDown();
        assertThrows(Exception.class, () -> staleFailingWorker.get(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
        });
        assertFalse(staleErrorCalled.get(), "Superseded worker must not invoke error callback");
    }
}
