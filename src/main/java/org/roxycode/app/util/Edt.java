package org.roxycode.app.util;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

public final class Edt {

    private static final AtomicInteger activeTasks = new AtomicInteger(0);
    private static final Object lock = new Object();

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

    public static <T> void supplyAsync(Callable<T> backgroundTask, Consumer<T> onEdtSuccess) {
        supplyAsync(backgroundTask, onEdtSuccess, null);
    }

    public static <T> void supplyAsync(Callable<T> backgroundTask, Consumer<T> onEdtSuccess,
            Consumer<Exception> onEdtError) {
        activeTasks.incrementAndGet();
        try {
            Thread.ofVirtual().start(() -> {
                boolean callbackDispatched = false;
                try {
                    T result = null;
                    if (backgroundTask != null) {
                        result = backgroundTask.call();
                    }
                    final T finalResult = result;
                    if (onEdtSuccess != null) {
                        callbackDispatched = true;
                        Edt.run(() -> {
                            try {
                                onEdtSuccess.accept(finalResult);
                            } finally {
                                completeAsyncTask();
                            }
                        });
                    }
                } catch (Exception e) {
                    if (onEdtError != null) {
                        callbackDispatched = true;
                        Edt.run(() -> {
                            try {
                                onEdtError.accept(e);
                            } finally {
                                completeAsyncTask();
                            }
                        });
                    }
                } finally {
                    if (!callbackDispatched) {
                        completeAsyncTask();
                    }
                }
            });
        } catch (Throwable t) {
            // Safeguard: Decrement if the virtual thread fails to start entirely
            completeAsyncTask();
            if (t instanceof RuntimeException re) {
                throw re;
            }
            if (t instanceof Error e) {
                throw e;
            }
            throw new RuntimeException("Failed to start virtual thread", t);
        }
    }

    private static void completeAsyncTask() {
        if (activeTasks.decrementAndGet() == 0) {
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }

    public static void runAsync(Runnable backgroundTask, Runnable onEdtSuccess) {
        runAsync(backgroundTask, onEdtSuccess, null);
    }

    public static void runAsync(Runnable backgroundTask, Runnable onEdtSuccess, Consumer<Exception> onEdtError) {
        supplyAsync(() -> {
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

    /**
     * Waits for all in-flight background tasks to complete. Used primarily for testing.
     */
    public static void awaitAsyncTasks() {
        long startTime = System.currentTimeMillis();
        long timeoutMs = 8000; // Hard timeout prevents CI deadlocks

        synchronized (lock) {
            while (activeTasks.get() > 0) {
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    throw new IllegalStateException(
                            "Timeout waiting for background tasks to complete. Active tasks leaked: "
                                    + activeTasks.get());
                }
                try {
                    lock.wait(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}
