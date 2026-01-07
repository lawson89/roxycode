package org.roxycode.core.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.Pager;
import com.google.genai.types.*;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.roxycode.core.RoxyProjectService;
import org.roxycode.core.SettingsService;
import org.roxycode.core.beans.ProjectCacheMeta;
import org.roxycode.core.tools.ToolRegistry;
import org.roxycode.core.utils.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Singleton
public class GeminiCacheService {

    private static final Logger LOG = LoggerFactory.getLogger(GeminiCacheService.class);

    private final SettingsService settingsService;

    private final ProjectPackerService codebasePackerService;

    private final ObjectMapper tomlMapper;

    private final RoxyProjectService roxyProjectService;

    private Client client;

    private String lastApiKey;

    private final ToolRegistry toolRegistry;

    private final ProjectCacheMetaService projectCacheMetaService;

    public GeminiCacheService(SettingsService settingsService, @Named("toml") ObjectMapper tomlMapper,
                              ProjectPackerService codebasePackerService, RoxyProjectService roxyProjectService,
                              ToolRegistry toolRegistry, ProjectCacheMetaService projectCacheMetaService) {
        this.settingsService = settingsService;
        this.codebasePackerService = codebasePackerService;
        this.tomlMapper = tomlMapper;
        this.roxyProjectService = roxyProjectService;
        this.toolRegistry = toolRegistry;
        this.projectCacheMetaService = projectCacheMetaService;
    }

    private synchronized Client getClient() {
        String currentApiKey = settingsService.getGeminiApiKey();
        if (client == null || !currentApiKey.equals(lastApiKey)) {
            LOG.info("Initializing/Refreshing Gemini Client...");
            this.client = Client.builder().apiKey(currentApiKey).build();
            this.lastApiKey = currentApiKey;
        }
        return client;
    }

    public void pushCache(Path projectPath) {
        LOG.info("Pushing cache to Gemini...");

        String currentModel = settingsService.getGeminiModel();
        String project = settingsService.getCurrentProject();
        String user = SystemUtils.getSystemUser();
        String cacheKey = projectCacheMetaService.getCacheKey(projectPath, user, currentModel);

        LOG.info("cacheKey: {} | project: {} | user: {} | currentModel: {}", cacheKey, project, user, currentModel);

        try {
            deleteCache(cacheKey);
        } catch (RuntimeException e) {
            LOG.warn("Failed to delete cache key: {}", cacheKey);
        }

        try {
            Path cacheFile = projectPath.resolve(RoxyProjectService.ROXY_WORKING_DIR).resolve(".cache").resolve("codebase_cache.toml");

            if (!Files.exists(cacheFile)) {
                LOG.error("Cache file not found at: {}", cacheFile);
                throw new IllegalStateException("Cache file not found at: " + cacheFile);
            }

            String tomlData = Files.readString(cacheFile);

            Content cacheContent = Content.builder()
                    .role("user")
                    .parts(List.of(Part.builder().text(tomlData).build()))
                    .build();

            Content systemInstruction = Content.builder()
                    .parts(List.of(Part.builder()
                            .text("You are RoxyCode. You have tools to assist in local coding. You have access to the full codebase context provided.")
                            .build()))
                    .build();

            List<Tool> geminiTools = toolRegistry.getAllGeminiTools();

            CreateCachedContentConfig config = CreateCachedContentConfig.builder()
                    .displayName(cacheKey)
                    .systemInstruction(systemInstruction)
                    .contents(List.of(cacheContent))
                    .tools(geminiTools)
                    .ttl(Duration.ofMinutes(settingsService.getCacheTTL()))
                    .build();

            LOG.info("Uploading {} bytes to Gemini...", tomlData.length());

            CachedContent response = getClient().caches.create(currentModel, config);

            LOG.info("✅ Cache Pushed Successfully!");
            // Generate Metadata File so GenAIService knows the ID
            String geminiId = response.name().orElse("Unknown");

            Path skeletonFile = roxyProjectService.getRoxyCacheDir().resolve("code_skeleton.txt");
            long skeletonTokenCount = codebasePackerService.estimateTokenCount(skeletonFile);
            String skeletonGeneratedAt = Files.exists(skeletonFile) ? Files.getLastModifiedTime(skeletonFile).toString() : "N/A";

            ProjectCacheMeta meta = new ProjectCacheMeta(
                    projectPath.toString(),
                    user,
                    ZonedDateTime.now().toString(),
                    cacheKey,
                    geminiId,
                    skeletonTokenCount,
                    skeletonGeneratedAt
            );
            projectCacheMetaService.writeProjectCacheMeta(meta);

            LOG.info("geminiId: {}", geminiId);
            LOG.info("Expires At: {}", response.expireTime());

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
            Pager<CachedContent> pager = getClient().caches.list(ListCachedContentsConfig.builder().build());
            List<CachedContent> allCaches = new ArrayList<>();
            for (CachedContent cache : pager) {
                allCaches.add(cache);
            }
            return allCaches;
        } catch (Exception e) {
            LOG.error("Failed to list caches: {}", e.getMessage(), e);
            throw new RuntimeException("Gemini API Error", e);
        }
    }

    /**
     * Deletes a specific cache by its resource name.
     */
    public void deleteCache(String cacheName) {
        try {
            LOG.info("Deleting cache: {}", cacheName);
            getClient().caches.delete(cacheName, DeleteCachedContentConfig.builder().build());
            LOG.info("Successfully deleted: {}", cacheName);
        } catch (Exception e) {
            LOG.warn("Failed to delete cache {}: {}", cacheName, e.getMessage());
        } finally {
            // Remove local metadata
            projectCacheMetaService.deleteProjectCacheMetaByCacheKey(cacheName);
            projectCacheMetaService.deleteProjectCacheMetaByGeminiId(cacheName);
        }
    }

    /**
     * Deletes ALL active caches for the project.
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
            }
        }
        projectCacheMetaService.deleteAllMetadata();
        return count;
    }

    /**
     * Refreshes the cache lifetime by extending it by the configured TTL.
     * @param cacheName The resource name of the cache (e.g. "cachedContents/...")
     */
    public void refreshCache(String cacheName) {
        try {
            LOG.info("Refreshing cache: {}", cacheName);
            int ttlMinutes = settingsService.getCacheTTL();
            UpdateCachedContentConfig config = UpdateCachedContentConfig.builder()
                    .ttl(Duration.ofMinutes(ttlMinutes))
                    .build();
            getClient().caches.update(cacheName, config);
            LOG.info("Successfully refreshed cache: {} (New TTL: {} minutes)", cacheName, ttlMinutes);
        } catch (Exception e) {
            LOG.error("Failed to refresh cache {}: {}", cacheName, e.getMessage(), e);
            throw new RuntimeException("Unable to refresh cache", e);
        }
    }
}
