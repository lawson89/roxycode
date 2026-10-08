package org.roxycode.app.ai.services.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import org.roxycode.app.model.ProjectCacheMeta;
import org.roxycode.app.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

@Service
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

    public ProjectCacheMetaService(ProjectService projectService) {
        this.projectService = projectService;
        this.mapper = new TomlMapper();
        this.mapper.findAndRegisterModules();
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
        Path legacyMeta = root.resolve(LEGACY_CACHE_FILE);
        Path targetMeta = root.resolve(CACHE_FILE);

        if (Files.exists(legacyMeta) && !Files.exists(targetMeta)) {
            try {
                ensureTmpDir();
                Files.move(legacyMeta, targetMeta, StandardCopyOption.REPLACE_EXISTING);
                log.info("Migrated legacy cache meta to {}", targetMeta);
            } catch (IOException e) {
                log.warn("Failed to migrate legacy cache meta: {}", e.getMessage());
            }
        }

        Path legacyRepo = root.resolve(LEGACY_REPO_CACHE_FILE);
        Path targetRepo = root.resolve(REPO_CACHE_FILE);
        if (Files.exists(legacyRepo) && !Files.exists(targetRepo)) {
            try {
                ensureTmpDir();
                Files.move(legacyRepo, targetRepo, StandardCopyOption.REPLACE_EXISTING);
                log.info("Migrated legacy repo cache to {}", targetRepo);
            } catch (IOException e) {
                log.warn("Failed to migrate legacy repo cache: {}", e.getMessage());
            }
        }
    }

    public Optional<ProjectCacheMeta> loadMeta() {
        if (!projectService.hasActiveProject()) return Optional.empty();
        migrateLegacyFiles();
        Path metaPath = projectService.getCurrentProjectRoot().resolve(CACHE_FILE);
        File file = metaPath.toFile();
        if (!file.exists()) return Optional.empty();

        try {
            return Optional.of(mapper.readValue(file, ProjectCacheMeta.class));
        } catch (IOException e) {
            log.error("Failed to load cache meta: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void saveMeta(ProjectCacheMeta meta) {
        if (!projectService.hasActiveProject()) return;
        ensureTmpDir();
        Path metaPath = projectService.getCurrentProjectRoot().resolve(CACHE_FILE);
        try {
            mapper.writeValue(metaPath.toFile(), meta);
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
