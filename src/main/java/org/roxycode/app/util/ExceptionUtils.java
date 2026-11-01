package org.roxycode.app.util;

import java.nio.channels.ClosedByInterruptException;
import java.util.concurrent.CancellationException;
import org.apache.commons.jexl3.JexlException;
import org.apache.commons.lang3.StringUtils;

public final class ExceptionUtils {

    private ExceptionUtils() {
    }

    public static <T extends Throwable> T findCause(Throwable t, Class<T> type) {
        return org.apache.commons.lang3.exception.ExceptionUtils.throwableOfType(t, type);
    }

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

    public static String getMessageOrSimpleName(Throwable t) {
        if (t == null) {
            return "Unknown error";
        }
        if (t.getMessage() != null) {
            return t.getMessage();
        }
        return t.getClass().getSimpleName();
    }

    public static String getRootCauseMessage(Throwable t) {
        if (t == null) {
            return "Unknown error";
        }
        Throwable root = getRootCause(t);
        if (root != null && StringUtils.isNotBlank(root.getMessage())) {
            return root.getMessage();
        }
        return getMessageOrSimpleName(t);
    }

    public static boolean isCancellation(Throwable t) {
        return Thread.currentThread().isInterrupted() || findCause(t, CancellationException.class) != null
                || findCause(t, InterruptedException.class) != null
                || findCause(t, ClosedByInterruptException.class) != null
                || findCause(t, JexlException.Cancel.class) != null;
    }

    public static CancellationException asCancellationException(Throwable t, String defaultMessage) {
        Thread.currentThread().interrupt();
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

    public static void rethrowIfCancelled(Throwable t, String message) {
        if (isCancellation(t)) {
            throw asCancellationException(t, message);
        }
    }

    public static void checkInterrupted(String message) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException(message);
        }
    }
}
