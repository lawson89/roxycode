package org.roxycode.app.util;

import java.nio.channels.ClosedByInterruptException;
import java.util.concurrent.CancellationException;
import org.apache.commons.jexl3.JexlException;
import org.apache.commons.lang3.StringUtils;

public final class ExceptionUtils {

    private ExceptionUtils() {
    }

    /**
     * Finds the first cause of the given type in the exception chain.
     *
     * @param <T>
     *            the exception type
     * @param t
     *            the throwable to search
     * @param type
     *            the target exception class
     * @return the found cause, or null if not found
     */
    public static <T extends Throwable> T findCause(Throwable t, Class<T> type) {
        return org.apache.commons.lang3.exception.ExceptionUtils.throwableOfType(t, type);
    }

    /**
     * Returns the root cause of the given throwable.
     *
     * @param t
     *            the throwable
     * @return the root cause, or the throwable itself if it has no cause
     */
    public static Throwable getRootCause(Throwable t) {
        if (t == null) {
            return null;
        }
        Throwable root = org.apache.commons.lang3.exception.ExceptionUtils.getRootCause(t);
        if (root != null) {
            return root;
        }
        return t;
    }

    /**
     * Returns the exception message if present, otherwise the simple class name.
     *
     * @param t
     *            the throwable
     * @return the message or simple name
     */
    public static String getMessageOrSimpleName(Throwable t) {
        if (t == null) {
            return "Unknown error";
        }
        if (t.getMessage() != null) {
            return t.getMessage();
        }
        return t.getClass().getSimpleName();
    }

    /**
     * Returns a concise message from the root cause of the given throwable.
     *
     * @param t
     *            the throwable
     * @return the root cause message
     */
    public static String getRootCauseMessage(Throwable t) {
        if (t == null) {
            return "Unknown error";
        }
        Throwable root = getRootCause(t);
        if (root != null && StringUtils.isNotBlank(root.getMessage())) {
            return root.getMessage().trim();
        }
        if (StringUtils.isNotBlank(t.getMessage())) {
            return t.getMessage().trim();
        }
        if (root != null) {
            return getMessageOrSimpleName(root);
        }
        return getMessageOrSimpleName(t);
    }

    /**
     * Checks if the given throwable or its cause represents a cancellation or interruption.
     *
     * @param t
     *            the throwable to check
     * @return true if it is a cancellation
     */
    public static boolean isCancellation(Throwable t) {
        return Thread.currentThread().isInterrupted() || findCause(t, CancellationException.class) != null
                || findCause(t, InterruptedException.class) != null
                || findCause(t, ClosedByInterruptException.class) != null
                || findCause(t, JexlException.Cancel.class) != null;
    }

    /**
     * Wraps the given throwable in a CancellationException, preserving the original message if possible.
     *
     * @param t
     *            the throwable
     * @param defaultMessage
     *            the fallback message
     * @return a CancellationException
     */
    public static CancellationException asCancellationException(Throwable t, String defaultMessage) {
        if (findCause(t, InterruptedException.class) != null || findCause(t, ClosedByInterruptException.class) != null
                || findCause(t, JexlException.Cancel.class) != null) {
            Thread.currentThread().interrupt();
        }
        if (t instanceof CancellationException ce) {
            return ce;
        }
        CancellationException cancelCause = findCause(t, CancellationException.class);
        String msg = defaultMessage;
        if (cancelCause != null && cancelCause.getMessage() != null) {
            msg = cancelCause.getMessage();
        }
        CancellationException cancelEx = new CancellationException(msg);
        if (t != null) {
            cancelEx.initCause(t);
        }
        return cancelEx;
    }

    /**
     * Rethrows the given throwable as a CancellationException if it represents a cancellation.
     *
     * @param t
     *            the throwable
     * @param message
     *            the fallback message
     */
    public static void rethrowIfCancelled(Throwable t, String message) {
        if (isCancellation(t)) {
            throw asCancellationException(t, message);
        }
    }

    /**
     * Checks if the current thread is interrupted and throws a CancellationException if so.
     *
     * @param message
     *            the message for the exception
     * @throws CancellationException
     *             if the thread is interrupted
     */
    public static void checkInterrupted(String message) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException(message);
        }
    }
}
