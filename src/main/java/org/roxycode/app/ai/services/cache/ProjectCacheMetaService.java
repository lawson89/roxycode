package org.roxycode.app.ai.services.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.model.ProjectCacheMeta;
import org.roxycode.app.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@SuppressWarnings("PMD.NullAssignment")
public class ProjectCacheMetaService {
    private static final Logger log = LoggerFactory.getLogger(ProjectCacheMetaService.class);
    private static final String ROXY_DIR = ".roxycode";
    private static final String TMP_DIR = ROXY_DIR + "/tmp";
    private static final String CACHE_FILE = TMP_DIR + "/cache_meta.toml";
    private static final String REPO_CACHE_FILE = TMP_DIR + "/repo_cache.txt";

    // Legacy paths for automatic migration
    private static final String LEGACY_CACHE_FILE = ROXY_DIR + "/cache_meta.toml";
    private static final String LEGACY_REPO_CACHE_FILE = ROXY_DIR + "/repo_cache.txt";

    private final ProjectService projectService;
    private final ObjectMapper mapper;
    private Path cachedProjectRoot;
    private Optional<ProjectCacheMeta> cachedMeta;

    public ProjectCacheMetaService(ProjectService projectService) {
        this(projectService, org.roxycode.app.ai.AiConfig.SHARED_TOML_MAPPER);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProjectCacheMetaService(ProjectService projectService, TomlMapper tomlMapper) {
        this.projectService = projectService;
        this.mapper =
                tomlMapper != null ? tomlMapper : org.roxycode.app.ai.AiConfig.SHARED_TOML_MAPPER;
    }

    private void ensureTmpDir() {
        if (!projectService.hasActiveProject()) return;
        Path dir = projectService.getCurrentProjectRoot().resolve(TMP_DIR);
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                log.error("Failed to create .roxycode/tmp directory: {}", e.getMessage());
            }
        }
    }

    private void migrateLegacyFiles() {
        if (!projectService.hasActiveProject()) return;
        Path root = projectService.getCurrentProjectRoot();
        migrateFileIfNeeded(root.resolve(LEGACY_CACHE_FILE), root.resolve(CACHE_FILE));
        migrateFileIfNeeded(root.resolve(LEGACY_REPO_CACHE_FILE), root.resolve(REPO_CACHE_FILE));
    }

    private void migrateFileIfNeeded(Path legacyPath, Path targetPath) {
        if (Files.exists(legacyPath) && !Files.exists(targetPath)) {
            try {
                ensureTmpDir();
                Files.move(legacyPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                log.info("Migrated legacy cache file to {}", targetPath);
            } catch (IOException e) {
                log.warn("Failed to migrate legacy cache file {}: {}", legacyPath, e.getMessage());
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

        migrateLegacyFiles();
        Path metaPath = currentRoot.resolve(CACHE_FILE);
        File file = metaPath.toFile();
        if (!file.exists()) {
            cachedProjectRoot = currentRoot;
            cachedMeta = Optional.empty();
            return cachedMeta;
        }

        try {
            Optional<ProjectCacheMeta> loaded =
                    Optional.ofNullable(mapper.readValue(file, ProjectCacheMeta.class));
            cachedProjectRoot = currentRoot;
            cachedMeta = loaded;
            return loaded;
        } catch (IOException e) {
            log.error("Failed to load cache meta: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public synchronized void saveMeta(ProjectCacheMeta meta) {
        if (!projectService.hasActiveProject()) return;
        ensureTmpDir();
        Path currentRoot = projectService.getCurrentProjectRoot();
        Path metaPath = currentRoot.resolve(CACHE_FILE);
        try {
            mapper.writeValue(metaPath.toFile(), meta);
            cachedProjectRoot = currentRoot;
            cachedMeta = Optional.ofNullable(meta);
        } catch (IOException e) {
            log.error("Failed to save cache meta: {}", e.getMessage());
        }
    }

    public void saveRepoCache(String content) {
        if (!projectService.hasActiveProject()) return;
        ensureTmpDir();
        Path path = projectService.getCurrentProjectRoot().resolve(REPO_CACHE_FILE);
        try {
            Files.writeString(path, content);
        } catch (IOException e) {
            log.error("Failed to save repo cache: {}", e.getMessage());
        }
    }

    public Optional<String> loadRepoCache() {
        if (!projectService.hasActiveProject()) return Optional.empty();
        migrateLegacyFiles();
        Path path = projectService.getCurrentProjectRoot().resolve(REPO_CACHE_FILE);
        if (!Files.exists(path)) return Optional.empty();
        try {
            return Optional.of(Files.readString(path));
        } catch (IOException e) {
            log.error("Failed to load repo cache: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
