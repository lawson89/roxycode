package org.roxycode.app.ai.services.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.roxycode.app.model.ProjectCacheMeta;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.util.PathUtils;
import org.roxycode.app.util.TokenUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ProjectCacheMetaService {
    private static final Logger log = LoggerFactory.getLogger(ProjectCacheMetaService.class);
    private static final String REPO_CACHE_FILE = "tmp/repo_cache.txt";

    private final ProjectService projectService;

    public ProjectCacheMetaService(ProjectService projectService) {
        this.projectService = projectService;
    }

    public void deleteRepoCache() {
        if (!projectService.hasActiveProject()) {
            return;
        }
        try {
            Files.deleteIfExists(projectService.resolveProjectDataPath(REPO_CACHE_FILE));
        } catch (IOException e) {
            log.error("Failed to delete repo cache: {}", e.getMessage());
        }
    }

    public Optional<ProjectCacheMeta> loadMeta() {
        if (!projectService.hasActiveProject()) {
            return Optional.empty();
        }
        Path cachePath = projectService.resolveProjectDataPath(REPO_CACHE_FILE);
        if (!Files.exists(cachePath)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(cachePath, StandardCharsets.UTF_8);
            LocalDateTime lastUpdated = LocalDateTime.ofInstant(Files.getLastModifiedTime(cachePath).toInstant(),
                    ZoneId.systemDefault());
            return Optional.of(new ProjectCacheMeta(projectService.getProjectName(), lastUpdated,
                    TokenUtils.estimateTokens(content)));
        } catch (IOException e) {
            log.error("Failed to derive cache meta: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void saveRepoCache(String content) {
        if (!projectService.hasActiveProject()) {
            return;
        }
        Path path = projectService.resolveProjectDataPath(REPO_CACHE_FILE);
        try {
            PathUtils.createParentDirectories(path);
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to save repo cache: {}", e.getMessage());
        }
    }

    public Optional<String> loadRepoCache() {
        if (!projectService.hasActiveProject()) {
            return Optional.empty();
        }
        Path path = projectService.resolveProjectDataPath(REPO_CACHE_FILE);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("Failed to load repo cache: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
