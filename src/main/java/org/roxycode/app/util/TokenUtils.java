package org.roxycode.app.util;

/** Utility methods for token estimation. */
public final class TokenUtils {

    private TokenUtils() {}

    /**
     * Roughly estimates the number of tokens in a string.
     *
     * @param text the text to estimate tokens for
     * @return the estimated number of tokens
     */
    public static long estimateTokens(String text) {
        if (text == null) {
            return 0;
        }
        return text.length() / 4;
    }
}
