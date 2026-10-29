package org.roxycode.app.util;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Callable;
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
        return supplyAsync(backgroundTask, onEdtSuccess, null);
    }

    public static <T> SwingWorker<T, Void> supplyAsync(Callable<T> backgroundTask, Consumer<T> onEdtSuccess,
            Consumer<Exception> onEdtError) {
        SwingWorker<T, Void> worker = new SwingWorker<>() {
            @Override
            protected T doInBackground() throws Exception {
                try {
                    T result = backgroundTask != null ? backgroundTask.call() : null;
                    if (!isCancelled() && onEdtSuccess != null) {
                        Edt.run(() -> {
                            if (!isCancelled()) {
                                onEdtSuccess.accept(result);
                            }
                        });
                    }
                    return result;
                } catch (Exception e) {
                    if (!isCancelled() && onEdtError != null) {
                        Edt.run(() -> {
                            if (!isCancelled()) {
                                onEdtError.accept(e);
                            }
                        });
                    }
                    throw e;
                }
            }
        };
        worker.execute();
        return worker;
    }
}
