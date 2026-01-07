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
}
