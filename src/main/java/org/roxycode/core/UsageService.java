package org.roxycode.core;

import jakarta.inject.Singleton;
import org.roxycode.core.config.GeminiModel;
import org.roxycode.core.config.GeminiModelRegistry;

import java.util.prefs.Preferences;

@Singleton
public class UsageService {
    private static final String KEY_API_CALLS = "usage_api_calls";
    private static final String KEY_PROMPT_TOKENS = "usage_prompt_tokens";
    private static final String KEY_CANDIDATE_TOKENS = "usage_candidate_tokens";

    private final Preferences preferences;
    private final SettingsService settingsService;
    private final GeminiModelRegistry geminiModelRegistry;

    public UsageService(SettingsService settingsService, GeminiModelRegistry geminiModelRegistry) {
        this.preferences = Preferences.userNodeForPackage(UsageService.class);
        this.settingsService = settingsService;
        this.geminiModelRegistry = geminiModelRegistry;
    }

    public synchronized void recordUsage(int promptTokens, int candidateTokens) {
        setApiCalls(getApiCalls() + 1);
        setPromptTokens(getPromptTokens() + promptTokens);
        setCandidateTokens(getCandidateTokens() + candidateTokens);
    }

    public int getApiCalls() {
        return preferences.getInt(KEY_API_CALLS, 0);
    }

    private void setApiCalls(int value) {
        preferences.putInt(KEY_API_CALLS, value);
    }

    public long getPromptTokens() {
        return preferences.getLong(KEY_PROMPT_TOKENS, 0L);
    }

    private void setPromptTokens(long value) {
        preferences.putLong(KEY_PROMPT_TOKENS, value);
    }

    public long getCandidateTokens() {
        return preferences.getLong(KEY_CANDIDATE_TOKENS, 0L);
    }

    private void setCandidateTokens(long value) {
        preferences.putLong(KEY_CANDIDATE_TOKENS, value);
    }

    public long getTotalTokens() {
        return getPromptTokens() + getCandidateTokens();
    }

    public double getInputTokenPrice(){
        String model = settingsService.getGeminiModel();
        GeminiModel gm = geminiModelRegistry.getModelByName(model).orElse(null);
        if(gm != null) {
            return gm.getInputPrice();
        }else{
            return 0.0;
        }
    }

    public double getOutputTokenPrice(){
        String model = settingsService.getGeminiModel();
        GeminiModel gm = geminiModelRegistry.getModelByName(model).orElse(null);
        if(gm != null) {
            return gm.getOutputPrice();
        }else{
            return 0.0;
        }
    }

    public double getEstimatedCost() {
        double inputCost = (getPromptTokens() / 1_000_000.0) * getInputTokenPrice();
        double outputCost = (getCandidateTokens() / 1_000_000.0) * getOutputTokenPrice();
        return inputCost + outputCost;
    }

    public void reset() {
        preferences.putInt(KEY_API_CALLS, 0);
        preferences.putLong(KEY_PROMPT_TOKENS, 0L);
        preferences.putLong(KEY_CANDIDATE_TOKENS, 0L);
    }
}
