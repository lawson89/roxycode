package org.roxycode.cache;

import com.google.genai.Client;
import com.google.genai.Pager;
import com.google.genai.types.*;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.apache.commons.logging.Log;
import org.roxycode.core.SettingsService;
import org.roxycode.core.utils.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Singleton
public class GeminiCacheService {

    private static final Logger LOG = LoggerFactory.getLogger(GeminiCacheService.class);

    private final SettingsService settingsService;

    private final CodebasePackerService codebasePackerService;

    private Client client;

    public GeminiCacheService(SettingsService settingsService, CodebasePackerService codebasePackerService) {
        this.settingsService = settingsService;
        this.codebasePackerService = codebasePackerService;
    }

    @PostConstruct
    public void init() {
        this.client = Client.builder()
                .apiKey(settingsService.getGeminiApiKey())
                .build();
    }

    public void pushCache() {
        LOG.info("Pushing cache to Gemini...");

        // 1. Gather Metadata
        String currentModel = settingsService.getGeminiModel();
        String project = settingsService.getCurrentProject();
        String user = SystemUtils.getSystemUser();
        Path projectPath = settingsService.getCurrentProjectPath();

        // 2. Calculate Key & Cleanup
        // ensuring the cacheKey is "clean" for the API (no spaces/weird chars)
        String cacheKey = codebasePackerService.getCacheKey(projectPath, user, currentModel);

        LOG.info("cacheKey: {} | project: {} | user: {} | currentModel: {}", cacheKey, project, user, currentModel);

        // Delete existing cache with this name to avoid "Already Exists" errors
        // (Assuming deleteCache handles "not found" gracefully)
        deleteCache(cacheKey);

        try {
            // 3. Read the TOML Content
            // Assuming the file is named using the cacheKey or a standard name in the cache dir
            Path cacheFile = projectPath.resolve("codebase_cache.toml"); // Or leverage your specific naming logic

            if (!Files.exists(cacheFile)) {
                LOG.error("Cache file not found at: {}", cacheFile);
                return;
            }

            String tomlData = Files.readString(cacheFile);

            // 4. Create the Content Object (The Payload)
            Content cacheContent = Content.builder()
                    .role("user")
                    .parts(List.of(Part.builder()
                            .text(tomlData) // Loading the entire TOML string
                            .build()))
                    .build();

            // 5. Create System Instructions (Optional but Recommended)
            // It is cheaper to bake this "Identity" into the cache now.
            Content systemInstruction = Content.builder()
                    .parts(List.of(Part.builder()
                            .text("You are RoxyCode. You answer in TOML. You have access to the full codebase context provided.")
                            .build()))
                    .build();

            CreateCachedContentConfig config = CreateCachedContentConfig.builder()
                    .displayName(cacheKey)
                    .systemInstruction(systemInstruction)
                    .contents(List.of(cacheContent))
                    .ttl(Duration.ofMinutes(settingsService.getCacheTTL()))
                    .build();

            // 7. Execute Upload
            LOG.info("Uploading {} bytes to Gemini...", tomlData.length());

            CachedContent response = client.caches.create(currentModel, config);
            String geminiId = response.name().orElse("missing");

            LOG.info("✅ Cache Pushed Successfully!");
            LOG.info("Resource Name: {}", response.name()); // e.g., cachedContents/12345...
            LOG.info("Expires At: {}", response.expireTime());

            CodebaseCacheMeta codebaseCacheMeta = new CodebaseCacheMeta(project, user, LocalDateTime.now().toString(), cacheKey, geminiId);
            codebasePackerService.writeProjectCacheMeta(codebaseCacheMeta);

        } catch (Exception e) {
            LOG.error("Failed to push cache to Gemini: {}", e.getMessage(), e);
            throw new RuntimeException("Cache Push Failed", e);
        }
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
            LOG.warn("Failed to delete cache {}: {}", cacheName, e.getMessage());
            throw new RuntimeException("Unable to delete cache", e);
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