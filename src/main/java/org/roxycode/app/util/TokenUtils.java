package org.roxycode.app.util;

import java.util.Locale;

public final class TokenUtils {

    private static final long THOUSAND = 1_000L;
    private static final long MILLION = 1_000_000L;
    private static final double THOUSAND_DOUBLE = 1_000.0;
    private static final double MILLION_DOUBLE = 1_000_000.0;
    private static final double ZERO_COST = 0.0;
    private static final double SMALL_COST_THRESHOLD = 0.01;

    private TokenUtils() {
    }

    /**
     * Provides a rough estimate of the number of tokens in the given text.
     *
     * @param text
     *            the text to estimate
     * @return the estimated token count
     */
    public static long estimateTokens(String text) {
        if (text == null) {
            return 0;
        }
        return text.length() / 4;
    }

    /**
     * Formats a token count for display (e.g., "1.2k", "1.5M").
     *
     * @param tokens
     *            the token count
     * @return the formatted token count string
     */
    public static String formatTokenCount(long tokens) {
        long safeTokens = Math.max(0L, tokens);
        if (safeTokens < THOUSAND) {
            return String.valueOf(safeTokens);
        }
        if (safeTokens < MILLION) {
            return String.format(Locale.US, "%.1fk", safeTokens / THOUSAND_DOUBLE);
        }
        return String.format(Locale.US, "%.1fM", safeTokens / MILLION_DOUBLE);
    }

    /**
     * Formats a USD cost for display.
     *
     * @param costUsd
     *            the cost in USD
     * @return the formatted cost string
     */
    public static String formatCostUsd(double costUsd) {
        if (costUsd <= ZERO_COST) {
            return "$0.00";
        }
        if (costUsd < SMALL_COST_THRESHOLD) {
            return String.format(Locale.US, "$%.4f", costUsd);
        }
        return String.format(Locale.US, "$%.2f", costUsd);
    }

    /**
     * Computes the cache hit percentage.
     *
     * @param cachedTokens
     *            the number of cached tokens
     * @param promptTokens
     *            the total number of prompt tokens
     * @return the cache hit percentage (0-100)
     */
    public static int computeCacheHitPercent(long cachedTokens, long promptTokens) {
        if (promptTokens <= 0L || cachedTokens <= 0L) {
            return 0;
        }
        long clampedCached = Math.min(promptTokens, cachedTokens);
        return (int) Math.min(100L, Math.round((clampedCached * 100.0) / promptTokens));
    }
}
