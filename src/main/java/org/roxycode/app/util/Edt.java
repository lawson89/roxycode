package org.roxycode.app.util;

import java.lang.reflect.InvocationTargetException;
import javax.swing.SwingUtilities;

/** Utility for running actions on the Swing Event Dispatch Thread (EDT). */
public final class Edt {

    private Edt() {}

    /**
     * Runs the given action immediately if the current thread is the Swing Event Dispatch Thread,
     * or schedules it via {@link SwingUtilities#invokeLater(Runnable)} otherwise.
     *
     * @param action the runnable action to execute on the EDT (ignored if {@code null})
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
     * Runs the given action immediately if the current thread is the Swing Event Dispatch Thread,
     * or synchronously waits for it via {@link SwingUtilities#invokeAndWait(Runnable)} otherwise.
     *
     * @param action the runnable action to execute on the EDT (ignored if {@code null})
     */
    public static void runAndWait(Runnable action) {
        if (action == null) {
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
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
    }
}
