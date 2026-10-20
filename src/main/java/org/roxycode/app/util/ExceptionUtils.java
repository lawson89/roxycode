package org.roxycode.app.util;

import java.util.Objects;

/** Utility methods for inspecting exception causal chains. */
public final class ExceptionUtils {

    private ExceptionUtils() {}

    /**
     * Traverses the causal chain of the given throwable to find the first instance of the target
     * exception type, guarding against causal cycles.
     */
    public static <T extends Throwable> T findCause(Throwable t, Class<T> type) {
        Throwable current = t;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            if (Objects.equals(current.getCause(), current)) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * Checks whether any throwable in the causal chain has a message containing the given fragment.
     */
    public static boolean hasCauseWithMessage(Throwable t, String fragment) {
        Throwable current = t;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(fragment)) {
                return true;
            }
            if (Objects.equals(current.getCause(), current)) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }

    /** Returns the innermost cause of the given throwable, guarding against causal cycles. */
    public static Throwable getRootCause(Throwable t) {
        if (t == null) {
            return null;
        }
        Throwable cause = t;
        while (cause.getCause() != null && !Objects.equals(cause.getCause(), cause)) {
            cause = cause.getCause();
        }
        return cause;
    }
}
