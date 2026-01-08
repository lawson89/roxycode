package org.roxycode.core;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class GeminiClientFactory {

    private static final Logger LOG = LoggerFactory.getLogger(GeminiClientFactory.class);

    public Client createClient(String apiKey) {
        LOG.info("Creating Gemini Client...");
        return Client.builder()
                .apiKey(apiKey)
                .build();
    }

    public Client createClientWithRetry(String apiKey) {
        LOG.info("Creating Gemini Client with retry options...");
        return Client.builder()
                .apiKey(apiKey)
                .httpOptions(HttpOptions.builder()
                        .retryOptions(HttpRetryOptions.builder()
                                .attempts(5)
                                .httpStatusCodes(429, 503)
                                .build())
                        .timeout(60_000)
                        .build())
                .build();
    }
}
