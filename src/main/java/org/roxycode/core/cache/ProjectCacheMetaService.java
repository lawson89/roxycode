package org.roxycode.core.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.roxycode.core.RoxyProjectService;
import org.roxycode.core.SettingsService;
import org.roxycode.core.beans.ProjectCacheMeta;
import org.roxycode.core.utils.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Singleton
public class ProjectCacheMetaService {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectCacheMetaService.class);

    private final SettingsService settingsService;
    private final RoxyProjectService roxyProjectService;
    private final ObjectMapper tomlMapper;

    @Inject
    public ProjectCacheMetaService(SettingsService settingsService,
                                   RoxyProjectService roxyProjectService,
                                   @Named("toml") ObjectMapper tomlMapper) {
        this.settingsService = settingsService;
        this.roxyProjectService = roxyProjectService;
        this.tomlMapper = tomlMapper;
    }

    public String getCacheKey(Path root, String user, String geminiModel) {
        return "roxycode_cache_" + user + "_" + root.getFileName() + "_" + geminiModel;
    }

    public Optional<ProjectCacheMeta> getProjectCacheMeta() {
        return getProjectCacheMeta(roxyProjectService.getProjectRoot());
    }

    public Optional<ProjectCacheMeta> getProjectCacheMeta(Path projectPath) {
        String currentModel = settingsService.getGeminiModel();
        String user = SystemUtils.getSystemUser();
        if (projectPath == null) {
            return Optional.empty();
        }
        String cacheKey = getCacheKey(projectPath, user, currentModel);
        try {
            Path cacheDir = roxyProjectService.getRoxyCacheDir();
            Path metaFilePath = cacheDir.resolve(cacheKey + ".toml");
            if (Files.exists(metaFilePath)) {
                return Optional.of(tomlMapper.readValue(metaFilePath.toFile(), ProjectCacheMeta.class));
            }
        } catch (IOException e) {
            LOG.error("Failed to read cache metadata for key {}: {}", cacheKey, e.getMessage());
        }
        return Optional.empty();
    }

    public void writeProjectCacheMeta(ProjectCacheMeta codebaseCacheMeta) throws IOException {
        Path cacheDir = roxyProjectService.getRoxyCacheDir();
        String metaFileName = codebaseCacheMeta.cacheKey() + ".toml";
        Path metaFilePath = cacheDir.resolve(metaFileName);
        tomlMapper.writeValue(metaFilePath.toFile(), codebaseCacheMeta);
    }

    public void deleteProjectCacheMetaByCacheKey(String cacheKey) {
        try {
            Path cacheDir = roxyProjectService.getRoxyCacheDir();
            Path metaFilePath = cacheDir.resolve(cacheKey + ".toml");
            if (Files.deleteIfExists(metaFilePath)) {
                LOG.info("Deleted metadata for cache key: {}", cacheKey);
            }
        } catch (IOException e) {
            LOG.error("Failed to delete cache metadata for key {}: {}", cacheKey, e.getMessage());
        }
    }

    public void deleteProjectCacheMetaByGeminiId(String geminiId) {
        try {
            Path cacheDir = roxyProjectService.getRoxyCacheDir();
            if (!Files.exists(cacheDir)) return;
            try (var stream = Files.list(cacheDir)) {
                stream.filter(p -> p.toString().endsWith(".toml"))
                        .forEach(p -> {
                            try {
                                ProjectCacheMeta meta = tomlMapper.readValue(p.toFile(), ProjectCacheMeta.class);
                                if (geminiId.equals(meta.geminiCacheId())) {
                                    if (Files.deleteIfExists(p)) {
                                        LOG.info("Deleted metadata file: {} for geminiId: {}", p.getFileName(), geminiId);
                                    }
                                }
                            } catch (IOException e) {
                                // Skip files that are not valid metadata or can't be read
                            }
                        });
            }
        } catch (IOException e) {
            LOG.error("Failed to scan cache directory for geminiId {}: {}", geminiId, e.getMessage());
        }
    }


    public void deleteAllMetadata() {
        try {
            Path cacheDir = roxyProjectService.getRoxyCacheDir();
            if (!Files.exists(cacheDir)) return;
            try (var stream = Files.list(cacheDir)) {
                stream.filter(p -> p.toString().endsWith(".toml"))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                                LOG.info("Deleted metadata file: {}", p.getFileName());
                            } catch (IOException e) {
                                LOG.error("Failed to delete metadata file: {}", p.getFileName());
                            }
                        });
            }
        } catch (IOException e) {
            LOG.error("Failed to list cache directory for cleanup: {}", e.getMessage());
        }
    }

}