package org.roxycode.app.ai.services.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.model.ProjectCacheMeta;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.util.TokenUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@SuppressWarnings("PMD.NullAssignment")
public class ProjectCacheMetaService {
    private static final Logger log = LoggerFactory.getLogger(ProjectCacheMetaService.class);
    private static final String TMP_DIR = "tmp";
    private static final String REPO_CACHE_FILE = TMP_DIR + "/repo_cache.txt";

    private final ProjectService projectService;
    private Path cachedProjectRoot;
    private Optional<ProjectCacheMeta> cachedMeta;

    public ProjectCacheMetaService(ProjectService projectService) {
        this.projectService = projectService;
    }

    private void ensureTmpDir() {
        if (!projectService.hasActiveProject()) return;
        Path dir = projectService.resolveProjectDataPath(TMP_DIR);
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                log.error("Failed to create project cache tmp directory: {}", e.getMessage());
            }
        }
    }

    @EventListener
    public synchronized void onProjectChanged(ProjectChangedEvent event) {
        invalidateCache();
    }

    public synchronized void invalidateCache() {
        cachedProjectRoot = null;
        cachedMeta = null;
    }

    public synchronized Optional<ProjectCacheMeta> loadMeta() {
        if (!projectService.hasActiveProject()) {
            invalidateCache();
            return Optional.empty();
        }
        Path currentRoot = projectService.getCurrentProjectRoot();
        if (cachedMeta != null && Objects.equals(cachedProjectRoot, currentRoot)) {
            return cachedMeta;
        }

        Path cachePath = projectService.resolveProjectDataPath(REPO_CACHE_FILE);
        if (!Files.exists(cachePath)) {
            cachedProjectRoot = currentRoot;
            cachedMeta = Optional.empty();
            return cachedMeta;
        }

        try {
            String content = Files.readString(cachePath, StandardCharsets.UTF_8);
            return cacheAndBuildMeta(currentRoot, cachePath, content);
        } catch (IOException e) {
            log.error("Failed to derive cache meta: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public synchronized void saveRepoCache(String content) {
        if (!projectService.hasActiveProject()) return;
        ensureTmpDir();
        Path currentRoot = projectService.getCurrentProjectRoot();
        Path path = projectService.resolveProjectDataPath(REPO_CACHE_FILE);
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
            cacheAndBuildMeta(currentRoot, path, content);
        } catch (IOException e) {
            log.error("Failed to save repo cache: {}", e.getMessage());
        }
    }

    private Optional<ProjectCacheMeta> cacheAndBuildMeta(
            Path currentRoot, Path cachePath, String content) throws IOException {
        LocalDateTime lastUpdated =
                LocalDateTime.ofInstant(
                        Files.getLastModifiedTime(cachePath).toInstant(), ZoneId.systemDefault());
        long tokens = TokenUtils.estimateTokens(content);
        Optional<ProjectCacheMeta> derived =
                Optional.of(
                        new ProjectCacheMeta(projectService.getProjectName(), lastUpdated, tokens));
        cachedProjectRoot = currentRoot;
        cachedMeta = derived;
        return derived;
    }

    public Optional<String> loadRepoCache() {
        if (!projectService.hasActiveProject()) return Optional.empty();
        Path path = projectService.resolveProjectDataPath(REPO_CACHE_FILE);
        if (!Files.exists(path)) return Optional.empty();
        try {
            return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("Failed to load repo cache: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
