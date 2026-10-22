package org.roxycode.app.util;

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
}
