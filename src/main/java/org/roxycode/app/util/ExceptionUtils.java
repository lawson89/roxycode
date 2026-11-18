package org.roxycode.app.util;

import java.nio.channels.ClosedByInterruptException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import org.apache.commons.jexl3.JexlException;
import org.apache.commons.lang3.StringUtils;

/** Exception chain inspection and cancellation handling utilities. */
public final class ExceptionUtils {

    private ExceptionUtils() {
    }

    /** Finds the first cause of the given type in the exception chain. */
    public static <T extends Throwable> T findCause(Throwable t, Class<T> type) {
        return org.apache.commons.lang3.exception.ExceptionUtils.throwableOfType(t, type);
    }

    /** Returns the root cause of {@code t}, or {@code t} itself if it has no cause. */
    public static Throwable getRootCause(Throwable t) {
        if (t == null) {
            return null;
        }
        return Objects.requireNonNullElse(org.apache.commons.lang3.exception.ExceptionUtils.getRootCause(t), t);
    }

    /** Returns the exception message if present, otherwise the simple class name. */
    public static String getMessageOrSimpleName(Throwable t) {
        if (t == null) {
            return "Unknown error";
        }
        return Objects.requireNonNullElse(t.getMessage(), t.getClass().getSimpleName());
    }

    /** Returns a concise message from the root cause of {@code t}. */
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

    /** Checks if {@code t} or any cause represents a cancellation or interruption. */
    public static boolean isCancellation(Throwable t) {
        return Thread.currentThread().isInterrupted() || findCause(t, CancellationException.class) != null
                || findCause(t, InterruptedException.class) != null
                || findCause(t, ClosedByInterruptException.class) != null
                || findCause(t, JexlException.Cancel.class) != null;
    }

    /** Wraps {@code t} in a {@link CancellationException}, preserving the original message if possible. */
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

    /** Rethrows {@code t} as a {@link CancellationException} if it represents a cancellation. */
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