package org.roxycode.app.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
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

        CompletableFuture<String> future = Edt.supplyAsync(() -> {
            taskRanOnEdt.set(SwingUtilities.isEventDispatchThread());
            return "computed-value";
        }, result -> {
            callbackRanOnEdt.set(SwingUtilities.isEventDispatchThread());
            callbackResult.set(result);
        });

        assertEquals("computed-value", future.get(5, TimeUnit.SECONDS));
        assertFalse(taskRanOnEdt.get(), "Background task must run off the EDT");
        assertTrue(callbackRanOnEdt.get(), "Success callback must run on the EDT");
        assertEquals("computed-value", callbackResult.get());
    }

    @Test
    void testSupplyAsyncErrorCallbackRunsOnEdtWhenTaskThrows() throws Exception {
        AtomicBoolean errorRanOnEdt = new AtomicBoolean(false);
        AtomicReference<Exception> capturedError = new AtomicReference<>();
        AtomicBoolean successCalled = new AtomicBoolean(false);

        CompletableFuture<Object> future = Edt.supplyAsync(() -> {
            throw new IllegalStateException("boom");
        }, result -> successCalled.set(true), ex -> {
            errorRanOnEdt.set(SwingUtilities.isEventDispatchThread());
            capturedError.set(ex);
        });

        assertNull(future.get(5, TimeUnit.SECONDS));
        assertFalse(successCalled.get());
        assertTrue(errorRanOnEdt.get(), "Error callback must run on the EDT");
        assertNotNull(capturedError.get());
        assertEquals("boom", capturedError.get().getMessage());
    }

    @Test
    void testRunAsync() throws Exception {
        AtomicBoolean taskRan = new AtomicBoolean(false);
        AtomicBoolean successRan = new AtomicBoolean(false);

        CompletableFuture<Void> future = Edt.runAsync(() -> taskRan.set(true), () -> successRan.set(true));

        future.get(5, TimeUnit.SECONDS);
        assertTrue(taskRan.get());
        assertTrue(successRan.get());
    }
}
