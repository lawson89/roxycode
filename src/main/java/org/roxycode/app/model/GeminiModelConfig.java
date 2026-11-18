package org.roxycode.app.model;

import java.util.Locale;

/** Configuration for a Gemini AI model, including pricing and context window information. */
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

    /** Constructs a GeminiModelConfig with default pricing based on the model name. */
    public GeminiModelConfig(String apiName, String name, String description) {
        this(apiName, name, description, DEFAULT_CONTEXT_WINDOW_TOKENS,
                resolveDefaultPrice(apiName, DEFAULT_PRO_INPUT_PRICE, DEFAULT_FLASH_INPUT_PRICE),
                resolveDefaultPrice(apiName, DEFAULT_PRO_CACHED_INPUT_PRICE, DEFAULT_FLASH_CACHED_INPUT_PRICE),
                resolveDefaultPrice(apiName, DEFAULT_PRO_OUTPUT_PRICE, DEFAULT_FLASH_OUTPUT_PRICE));
    }

    private static double resolveDefaultPrice(String apiName, double proPrice, double flashPrice) {
        if (isProTierModel(apiName)) {
            return proPrice;
        }
        return flashPrice;
    }

    /** Creates a default GeminiModelConfig for a given API name. */
    public static GeminiModelConfig defaultForApiName(String apiName) {
        String resolved = "";
        if (apiName != null) {
            resolved = apiName.trim();
        }
        return new GeminiModelConfig(resolved, resolved, "");
    }

    /** Checks if the model is a Pro tier model based on its API name. */
    public static boolean isProTierModel(String apiName) {
        if (apiName == null) {
            return false;
        }
        String lower = apiName.toLowerCase(Locale.ROOT);
        return lower.contains("pro") || lower.contains("argon") || lower.contains("carbon");
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