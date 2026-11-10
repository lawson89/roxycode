package org.roxycode.app.util;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * Utility for marshaling UI updates onto the Swing Event Dispatch Thread (EDT) and running background tasks on virtual
 * threads with EDT completion callbacks.
 */
public final class Edt {

    /**
     * An executor that runs tasks on the Swing Event Dispatch Thread (EDT).
     */
    public static final Executor EXECUTOR = Edt::run;
    /**
     * An executor that runs tasks on virtual threads.
     */
    public static final Executor VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private Edt() {
    }

    /**
     * Runs the given action on the EDT. If the current thread is the EDT, the action is run immediately.
     *
     * @param action
     *            the action to run
     */
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

    /**
     * Runs the given action on the EDT and waits for it to complete. If the current thread is the EDT, the action is
     * run immediately.
     *
     * @param action
     *            the action to run
     */
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

    /**
     * Runs a background task on a virtual thread and invokes the success callback on the EDT.
     *
     * @param <T>
     *            the result type
     * @param backgroundTask
     *            the background task to run
     * @param onEdtSuccess
     *            the callback to run on the EDT upon success
     * @return a CompletableFuture representing the result
     */
    public static <T> CompletableFuture<T> supplyAsync(Callable<T> backgroundTask, Consumer<T> onEdtSuccess) {
        return supplyAsync(backgroundTask, onEdtSuccess, null);
    }

    /**
     * Runs a background task on a virtual thread and invokes the corresponding callback on the EDT.
     *
     * @param <T>
     *            the result type
     * @param backgroundTask
     *            the background task to run
     * @param onEdtSuccess
     *            the callback to run on the EDT upon success
     * @param onEdtError
     *            the callback to run on the EDT upon error
     * @return a CompletableFuture representing the result
     */
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

    /**
     * Runs a background task on a virtual thread and invokes the success callback on the EDT.
     *
     * @param backgroundTask
     *            the background task to run
     * @param onEdtSuccess
     *            the callback to run on the EDT upon success
     * @return a CompletableFuture representing the result
     */
    public static CompletableFuture<Void> runAsync(Runnable backgroundTask, Runnable onEdtSuccess) {
        return runAsync(backgroundTask, onEdtSuccess, null);
    }

    /**
     * Runs a background task on a virtual thread and invokes the corresponding callback on the EDT.
     *
     * @param backgroundTask
     *            the background task to run
     * @param onEdtSuccess
     *            the callback to run on the EDT upon success
     * @param onEdtError
     *            the callback to run on the EDT upon error
     * @return a CompletableFuture representing the result
     */
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
