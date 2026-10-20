package org.roxycode.app.util;

import java.nio.channels.ClosedByInterruptException;
import java.util.concurrent.CancellationException;
import org.apache.commons.jexl3.JexlException;

/** Utility methods for inspecting exception causal chains and handling cancellation. */
public final class ExceptionUtils {

    private ExceptionUtils() {}

    /**
     * Traverses the causal chain of the given throwable to find the first instance of the target
     * exception type.
     */
    public static <T extends Throwable> T findCause(Throwable t, Class<T> type) {
        return org.apache.commons.lang3.exception.ExceptionUtils.throwableOfType(t, type);
    }

    /** Returns the innermost cause of the given throwable. */
    public static Throwable getRootCause(Throwable t) {
        if (t == null) {
            return null;
        }
        Throwable root = org.apache.commons.lang3.exception.ExceptionUtils.getRootCause(t);
        return root != null ? root : t;
    }

    /**
     * Returns true if the current thread is interrupted or if the given throwable (or any cause in
     * its chain) represents a cancellation or interruption.
     */
    public static boolean isCancellation(Throwable t) {
        return Thread.currentThread().isInterrupted()
                || findCause(t, CancellationException.class) != null
                || findCause(t, InterruptedException.class) != null
                || findCause(t, ClosedByInterruptException.class) != null
                || findCause(t, JexlException.Cancel.class) != null;
    }

    /**
     * Restores the thread interrupt flag and converts or unwraps the given throwable into a {@link
     * CancellationException}.
     */
    public static CancellationException asCancellationException(
            Throwable t, String defaultMessage) {
        Thread.currentThread().interrupt();
        if (t instanceof CancellationException ce) {
            return ce;
        }
        CancellationException cancelCause = findCause(t, CancellationException.class);
        String msg =
                (cancelCause != null && cancelCause.getMessage() != null)
                        ? cancelCause.getMessage()
                        : defaultMessage;
        CancellationException cancelEx = new CancellationException(msg);
        if (t != null) {
            cancelEx.initCause(t);
        }
        return cancelEx;
    }

    /**
     * If {@link #isCancellation(Throwable)} is true, restores the interrupt flag and throws a
     * {@link CancellationException}.
     */
    public static void rethrowIfCancelled(Throwable t, String message) {
        if (isCancellation(t)) {
            throw asCancellationException(t, message);
        }
    }

    /** Throws a {@link CancellationException} if the current thread is interrupted. */
    public static void checkInterrupted(String message) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException(message);
        }
    }
}
