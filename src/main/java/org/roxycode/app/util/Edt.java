package org.roxycode.app.util;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

public final class Edt {

    public static final Executor EXECUTOR = Edt::run;
    private static final Executor VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private Edt() {
    }

    public static void run(Runnable action) {
        if (action == null) {
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    @SuppressWarnings("PMD.PreserveStackTrace")
    public static void runAndWait(Runnable action) {
        if (action == null) {
            return;
        }
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
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Failed to execute action on EDT", cause);
        }
    }

    public static <T> CompletableFuture<T> supplyAsync(Callable<T> backgroundTask, Consumer<T> onEdtSuccess) {
        return supplyAsync(backgroundTask, onEdtSuccess, null);
    }

    public static <T> CompletableFuture<T> supplyAsync(Callable<T> backgroundTask, Consumer<T> onEdtSuccess,
            Consumer<Exception> onEdtError) {
        return CompletableFuture.supplyAsync(() -> {
            if (backgroundTask == null) {
                return null;
            }
            try {
                return backgroundTask.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, VIRTUAL_EXECUTOR).handleAsync((result, ex) -> {
            if (ex == null) {
                if (onEdtSuccess != null) {
                    onEdtSuccess.accept(result);
                }
                return result;
            }
            Throwable cause = ex;
            if (ex instanceof CompletionException && ex.getCause() != null) {
                cause = ex.getCause();
            }
            if (onEdtError != null) {
                Exception exceptionToReport;
                if (cause instanceof Exception exception) {
                    exceptionToReport = exception;
                } else {
                    exceptionToReport = new RuntimeException(cause);
                }
                onEdtError.accept(exceptionToReport);
                return null;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new CompletionException(cause);
        }, EXECUTOR);
    }

    public static CompletableFuture<Void> runAsync(Runnable backgroundTask, Runnable onEdtSuccess) {
        return runAsync(backgroundTask, onEdtSuccess, null);
    }

    public static CompletableFuture<Void> runAsync(Runnable backgroundTask, Runnable onEdtSuccess,
            Consumer<Exception> onEdtError) {
        return supplyAsync(() -> {
            if (backgroundTask != null) {
                backgroundTask.run();
            }
            return null;
        }, result -> {
            if (onEdtSuccess != null) {
                onEdtSuccess.run();
            }
        }, onEdtError);
    }
}
