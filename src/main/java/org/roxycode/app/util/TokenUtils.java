package org.roxycode.app.util;

public final class TokenUtils {

    private TokenUtils() {
    }

    public static long estimateTokens(String text) {
        if (text == null) {
            return 0;
        }
        return text.length() / 4;
    }
}
