package org.roxycode.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.roxycode.core.RoxyProjectService;
import org.roxycode.core.SettingsService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class GeminiCacheServiceTest {

    @TempDir
    Path tempDir;

    private GeminiCacheService geminiCacheService;
    private SettingsService settingsService;
    private ObjectMapper objectMapper;
    private CodebasePackerService codebasePackerService;
    private RoxyProjectService roxyProjectService;

    @BeforeEach
    void setUp() {
        settingsService = Mockito.mock(SettingsService.class);
        objectMapper = Mockito.mock(ObjectMapper.class);
        codebasePackerService = Mockito.mock(CodebasePackerService.class);
        roxyProjectService = Mockito.mock(RoxyProjectService.class);

        geminiCacheService = new GeminiCacheService(settingsService, objectMapper, codebasePackerService, roxyProjectService);
    }

    @Test
    void testGetProjectCacheMetaNoProject() {
        when(settingsService.getCurrentProjectPath()).thenReturn(null);

        Optional<CodebaseCacheMeta> result = geminiCacheService.getProjectCacheMeta();

        assertTrue(result.isEmpty());
    }

    @Test
    void testGetProjectCacheMetaFileNotFound() throws IOException {
        Path projectPath = tempDir.resolve("project");
        when(settingsService.getCurrentProjectPath()).thenReturn(projectPath);
        when(settingsService.getGeminiModel()).thenReturn("model");
        when(codebasePackerService.getCacheKey(any(), any(), any())).thenReturn("testKey");
        when(roxyProjectService.getRoxyProjectCacheDir()).thenReturn(tempDir.resolve("cache"));

        Optional<CodebaseCacheMeta> result = geminiCacheService.getProjectCacheMeta();

        assertTrue(result.isEmpty());
    }

    @Test
    void testGetProjectCacheMetaSuccess() throws IOException {
        Path projectPath = tempDir.resolve("project");
        Path cacheDir = tempDir.resolve("cache");
        Files.createDirectories(cacheDir);
        Path metaFile = cacheDir.resolve("testKey.toml");
        Files.createFile(metaFile);

        CodebaseCacheMeta expectedMeta = new CodebaseCacheMeta("project", "user", "now", "testKey", "geminiId");

        when(settingsService.getCurrentProjectPath()).thenReturn(projectPath);
        when(settingsService.getGeminiModel()).thenReturn("model");
        when(codebasePackerService.getCacheKey(any(), any(), any())).thenReturn("testKey");
        when(roxyProjectService.getRoxyProjectCacheDir()).thenReturn(cacheDir);
        when(objectMapper.readValue(eq(metaFile.toFile()), eq(CodebaseCacheMeta.class))).thenReturn(expectedMeta);

        Optional<CodebaseCacheMeta> result = geminiCacheService.getProjectCacheMeta();

        assertTrue(result.isPresent());
        assertEquals(expectedMeta, result.get());
    }
}
