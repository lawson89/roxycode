package org.roxycode.app.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class EdtTest {

    @Test
    void testSupplyAsyncExecutesBackgroundOffEdtAndSuccessOnEdt() throws Exception {
        AtomicBoolean taskRanOnEdt = new AtomicBoolean(true);
        AtomicBoolean callbackRanOnEdt = new AtomicBoolean(false);
        AtomicReference<String> callbackResult = new AtomicReference<>();
        CountDownLatch callbackLatch = new CountDownLatch(1);

        Edt.supplyAsync(() -> {
            taskRanOnEdt.set(SwingUtilities.isEventDispatchThread());
            return "computed-value";
        }, result -> {
            callbackRanOnEdt.set(SwingUtilities.isEventDispatchThread());
            callbackResult.set(result);
            callbackLatch.countDown();
        });

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

        Edt.supplyAsync(() -> {
            throw new IllegalStateException("boom");
        }, result -> successCalled.set(true), ex -> {
            errorRanOnEdt.set(SwingUtilities.isEventDispatchThread());
            capturedError.set(ex);
            errorLatch.countDown();
        });

        assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
        assertFalse(successCalled.get());
        assertTrue(errorRanOnEdt.get(), "Error callback must run on the EDT");
        assertNotNull(capturedError.get());
        assertEquals("boom", capturedError.get().getMessage());
    }

    @Test
    void testRunAsync() throws Exception {
        AtomicBoolean taskRan = new AtomicBoolean(false);
        AtomicBoolean successRan = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        Edt.runAsync(() -> taskRan.set(true), () -> {
            successRan.set(true);
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(taskRan.get());
        assertTrue(successRan.get());
    }

    @Test
    void testAwaitAsyncTasks() throws Exception {
        AtomicBoolean taskDone = new AtomicBoolean(false);

        Edt.runAsync(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            taskDone.set(true);
        }, null);

        Edt.awaitAsyncTasks();
        assertTrue(taskDone.get(), "awaitAsyncTasks must wait for background work");
    }
}
