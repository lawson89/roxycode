package org.roxycode.app.util;

public final class TokenUtils {

    private TokenUtils() {}

    public static long estimateTokens(String text) {
        return text == null ? 0 : text.length() / 4;
    }
}
