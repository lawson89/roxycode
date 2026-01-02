package org.roxycode.cache;

import com.google.genai.Client;
import com.google.genai.Pager;
import com.google.genai.types.CachedContent;
import com.google.genai.types.DeleteCachedContentConfig;
import com.google.genai.types.ListCachedContentsConfig;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.roxycode.core.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Singleton
public class GeminiCacheService {

    private static final Logger LOG = LoggerFactory.getLogger(GeminiCacheService.class);

    private final SettingsService settingsService;

    private Client client;

    public GeminiCacheService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @PostConstruct
    public void init() {
        this.client = Client.builder()
                .apiKey(settingsService.getGeminiApiKey())
                .build();
    }

    /**
     * Lists all active context caches for the project.
     */
    public List<CachedContent> listCaches() {
        try {
            LOG.info("Scanning for active Gemini Context Caches...");

            // 1. Get the Pager (handles pagination automatically)
            Pager<CachedContent> pager = client.caches.list(
                    ListCachedContentsConfig.builder().build()
            );

            // 2. Collect items into a standard List
            List<CachedContent> allCaches = new ArrayList<>();
            for (CachedContent cache : pager) {
                allCaches.add(cache);
            }

            if (allCaches.isEmpty()) {
                return Collections.emptyList();
            }
            return allCaches;

        } catch (Exception e) {
            LOG.error("Failed to list caches: {}", e.getMessage(), e);
            throw new RuntimeException("Gemini API Error", e);
        }
    }

    /**
     * Deletes a specific cache by its resource name.
     *
     * @param cacheName The full resource name (e.g., "cachedContents/12345...")
     */
    public void deleteCache(String cacheName) {
        try {
            LOG.info("Deleting cache: {}", cacheName);
            client.caches.delete(cacheName, DeleteCachedContentConfig.builder().build());
            LOG.info("Successfully deleted: {}", cacheName);
        } catch (Exception e) {
            LOG.error("Failed to delete cache {}: {}", cacheName, e.getMessage());
            throw new RuntimeException("Failed to delete cache", e);
        }
    }

    /**
     * Deletes ALL active caches for the project.
     * Use with caution.
     *
     * @return The count of deleted caches.
     */
    public int deleteAllCaches() {
        List<CachedContent> allCaches = listCaches();
        int count = 0;

        for (CachedContent cache : allCaches) {
            try {
                String name = cache.name().orElseThrow(() -> new IllegalStateException("Cache has no name"));
                deleteCache(name);
                count++;
            } catch (Exception e) {
                LOG.warn("Could not delete cache {}: {}", cache.name(), e.getMessage());
                // Continue deleting others even if one fails
            }
        }
        return count;
    }
}