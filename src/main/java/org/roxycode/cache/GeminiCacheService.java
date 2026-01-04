package org.roxycode.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.Pager;
import com.google.genai.types.*;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.roxycode.core.RoxyProjectService;
import org.roxycode.core.SettingsService;
import org.roxycode.core.utils.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Singleton
public class GeminiCacheService {

    private static final Logger LOG = LoggerFactory.getLogger(GeminiCacheService.class);

    private final SettingsService settingsService;

    private final CodebasePackerService codebasePackerService;

    private final ObjectMapper tomlMapper;

    private final ObjectMapper jsonMapper;

    private final RoxyProjectService roxyProjectService;

    private final HttpClient httpClient;

    private Client client;

    private String lastApiKey;

    public GeminiCacheService(SettingsService settingsService, @Named("toml") ObjectMapper tomlMapper, @Named("json") ObjectMapper jsonMapper, CodebasePackerService codebasePackerService, RoxyProjectService roxyProjectService) {
        this.settingsService = settingsService;
        this.codebasePackerService = codebasePackerService;
        this.tomlMapper = tomlMapper;
        this.jsonMapper = jsonMapper;
        this.roxyProjectService = roxyProjectService;
        this.httpClient = HttpClient.newBuilder().build();
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

    public void pushCache(Path projectPath) throws Exception {
        Path cacheFile = codebasePackerService.getCacheFilePath();
        if (!Files.exists(cacheFile)) {
            throw new IOException("Cache file not found: " + cacheFile);
        }
        String content = Files.readString(cacheFile);
        String apiKey = settingsService.getGeminiApiKey();
        String model = settingsService.getGeminiModel();
        int ttlMinutes = settingsService.getCacheTTL();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini API key is not configured.");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("model", "models/" + model);
        body.put("ttl", ttlMinutes * 60 + "s");
        Map<String, Object> contentsMap = new HashMap<>();
        contentsMap.put("role", "user");
        Map<String, Object> partsMap = new HashMap<>();
        partsMap.put("text", content);
        contentsMap.put("parts", List.of(partsMap));
        body.put("contents", List.of(contentsMap));
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create("https://generativelanguage.googleapis.com/v1beta/cachedContents")).header("Content-Type", "application/json").header("x-goog-api-key", apiKey).POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body))).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            LOG.error("Failed to push cache. Status: {}, Body: {}", response.statusCode(), response.body());
            throw new IOException("Failed to push cache to Gemini: " + response.body());
        }
        JsonNode root = jsonMapper.readTree(response.body());
        String geminiId = root.get("name").asText();
        CodebaseCacheMeta meta = new CodebaseCacheMeta(projectPath.toString(), System.getProperty("user.name"), ZonedDateTime.now().toString(), codebasePackerService.getCacheKey(projectPath, SystemUtils.getSystemUser(), model), geminiId);
        writeProjectCacheMeta(meta);
    }

    public Optional<CodebaseCacheMeta> getProjectCacheMeta(Path projectPath) {
        String currentModel = settingsService.getGeminiModel();
        String user = SystemUtils.getSystemUser();
        if (projectPath == null) {
            return Optional.empty();
        }
        String cacheKey = codebasePackerService.getCacheKey(projectPath, user, currentModel);
        try {
            Path cacheDir = roxyProjectService.getRoxyProjectCacheDir();
            Path metaFilePath = cacheDir.resolve(cacheKey + ".toml");
            if (Files.exists(metaFilePath)) {
                return Optional.of(tomlMapper.readValue(metaFilePath.toFile(), CodebaseCacheMeta.class));
            }
        } catch (IOException e) {
            LOG.error("Failed to read cache metadata for key {}: {}", cacheKey, e.getMessage());
        }
        return Optional.empty();
    }

    protected void writeProjectCacheMeta(CodebaseCacheMeta codebaseCacheMeta) throws IOException {
        Path cacheDir = roxyProjectService.getRoxyProjectCacheDir();
        String metaFileName = codebaseCacheMeta.cacheKey() + ".toml";
        Path metaFilePath = cacheDir.resolve(metaFileName);
        tomlMapper.writeValue(metaFilePath.toFile(), codebaseCacheMeta);
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
            throw new RuntimeException("Unable to delete cache", e);
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
        return count;
    }
}
