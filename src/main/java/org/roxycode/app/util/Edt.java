package org.roxycode.app.util;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

public final class Edt {

    private Edt() {
    }

    public static void run(Runnable action) {
        if (action == null)
            return;
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    @SuppressWarnings("PMD.PreserveStackTrace")
    public static void runAndWait(Runnable action) {
        if (action == null)
            return;
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException)
                throw runtimeException;
            if (cause instanceof Error error)
                throw error;
            throw new IllegalStateException("Failed to execute action on EDT", cause);
        }
    }

    public static <T> SwingWorker<T, Void> supplyAsync(Callable<T> backgroundTask, Consumer<T> onEdtSuccess) {
        return supplyAsync(null, backgroundTask, onEdtSuccess, null);
    }

    public static <T> SwingWorker<T, Void> supplyAsync(Callable<T> backgroundTask, Consumer<T> onEdtSuccess,
            Consumer<Exception> onEdtError) {
        return supplyAsync(null, backgroundTask, onEdtSuccess, onEdtError);
    }

    public static <T> SwingWorker<T, Void> supplyAsync(AtomicReference<SwingWorker<T, Void>> activeWorkerRef,
            Callable<T> backgroundTask, Consumer<T> onEdtSuccess) {
        return supplyAsync(activeWorkerRef, backgroundTask, onEdtSuccess, null);
    }

    public static <T> SwingWorker<T, Void> supplyAsync(AtomicReference<SwingWorker<T, Void>> activeWorkerRef,
            Callable<T> backgroundTask, Consumer<T> onEdtSuccess, Consumer<Exception> onEdtError) {
        SwingWorker<T, Void> worker = new SwingWorker<>() {
            private boolean isActive() {
                if (isCancelled()) {
                    return false;
                }
                return activeWorkerRef == null || activeWorkerRef.get() == this;
            }

            @Override
            protected T doInBackground() throws Exception {
                try {
                    T result = null;
                    if (backgroundTask != null) {
                        result = backgroundTask.call();
                    }
                    final T finalResult = result;
                    if (isActive() && onEdtSuccess != null) {
                        Edt.run(() -> {
                            if (isActive()) {
                                onEdtSuccess.accept(finalResult);
                            }
                        });
                    }
                    return result;
                } catch (Exception e) {
                    if (isActive() && onEdtError != null) {
                        Edt.run(() -> {
                            if (isActive()) {
                                onEdtError.accept(e);
                            }
                        });
                    }
                    throw e;
                }
            }
        };
        if (activeWorkerRef != null) {
            activeWorkerRef.set(worker);
        }
        worker.execute();
        return worker;
    }
}
