package org.roxycode.app.model;

import java.util.Locale;

public record GeminiModelConfig(String apiName, String name, String description, int contextWindowTokens,
        double inputPricePerMillion, double cachedInputPricePerMillion, double outputPricePerMillion) {

    public static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 1_048_576;
    public static final double DEFAULT_FLASH_INPUT_PRICE = 0.75;
    public static final double DEFAULT_FLASH_CACHED_INPUT_PRICE = 0.075;
    public static final double DEFAULT_FLASH_OUTPUT_PRICE = 3.75;
    public static final double DEFAULT_PRO_INPUT_PRICE = 2.00;
    public static final double DEFAULT_PRO_CACHED_INPUT_PRICE = 0.10;
    public static final double DEFAULT_PRO_OUTPUT_PRICE = 10.00;
    private static final double TOKENS_PER_MILLION = 1_000_000.0;

    public GeminiModelConfig {
        if (contextWindowTokens <= 0) {
            contextWindowTokens = DEFAULT_CONTEXT_WINDOW_TOKENS;
        }
        inputPricePerMillion = Math.max(0.0, inputPricePerMillion);
        cachedInputPricePerMillion = Math.max(0.0, cachedInputPricePerMillion);
        outputPricePerMillion = Math.max(0.0, outputPricePerMillion);
    }

    public GeminiModelConfig(String apiName, String name, String description) {
        this(apiName, name, description, DEFAULT_CONTEXT_WINDOW_TOKENS, defaultInputPrice(apiName),
                defaultCachedInputPrice(apiName), defaultOutputPrice(apiName));
    }

    public static GeminiModelConfig defaultForApiName(String apiName) {
        String resolvedApiName = "";
        if (apiName != null) {
            resolvedApiName = apiName.trim();
        }
        return new GeminiModelConfig(resolvedApiName, resolvedApiName, "");
    }

    public static boolean isProTierModel(String apiName) {
        if (apiName == null) {
            return false;
        }
        String lower = apiName.toLowerCase(Locale.ROOT);
        return lower.contains("pro") || lower.contains("argon");
    }

    private static double defaultInputPrice(String apiName) {
        if (isProTierModel(apiName)) {
            return DEFAULT_PRO_INPUT_PRICE;
        }
        return DEFAULT_FLASH_INPUT_PRICE;
    }

    private static double defaultCachedInputPrice(String apiName) {
        if (isProTierModel(apiName)) {
            return DEFAULT_PRO_CACHED_INPUT_PRICE;
        }
        return DEFAULT_FLASH_CACHED_INPUT_PRICE;
    }

    private static double defaultOutputPrice(String apiName) {
        if (isProTierModel(apiName)) {
            return DEFAULT_PRO_OUTPUT_PRICE;
        }
        return DEFAULT_FLASH_OUTPUT_PRICE;
    }

    public double calculateUncachedInputCostUsd(long uncachedInputTokens) {
        return (Math.max(0L, uncachedInputTokens) * inputPricePerMillion) / TOKENS_PER_MILLION;
    }

    public double calculateCachedInputCostUsd(long cachedInputTokens) {
        return (Math.max(0L, cachedInputTokens) * cachedInputPricePerMillion) / TOKENS_PER_MILLION;
    }

    public double calculateOutputCostUsd(long tokens) {
        return (Math.max(0L, tokens) * outputPricePerMillion) / TOKENS_PER_MILLION;
    }

    @Override
    public String toString() {
        return name;
    }
}
