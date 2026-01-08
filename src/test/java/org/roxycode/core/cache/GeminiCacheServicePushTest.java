package org.roxycode.core.cache;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.roxycode.core.SettingsService;
import org.roxycode.core.GeminiClientFactory;
import org.roxycode.core.RoxyProjectService;
import org.roxycode.core.tools.ToolRegistry;
import org.roxycode.core.beans.ProjectCacheMeta;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class GeminiCacheServicePushTest {

    @Test
    public void testPushCacheDeletesExistingCache(@TempDir Path tempDir) throws Exception {
        SettingsService settingsService = mock(SettingsService.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        ProjectPackerService packerService = mock(ProjectPackerService.class);
        RoxyProjectService roxyProjectService = mock(RoxyProjectService.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ProjectCacheMetaService metaService = mock(ProjectCacheMetaService.class);
        GeminiClientFactory geminiClientFactory = mock(GeminiClientFactory.class);
        GeminiCacheService service = spy(new GeminiCacheService(settingsService, objectMapper, packerService, roxyProjectService, toolRegistry, metaService, geminiClientFactory));
        // Mock deleteCache to avoid actual Gemini calls
        doNothing().when(service).deleteCache(anyString());
        Path projectPath = tempDir.resolve("project");
        Files.createDirectories(projectPath);
        String cacheKey = "rlawson_project_gemini-1.5-flash";
        ProjectCacheMeta existingMeta = new ProjectCacheMeta(projectPath.toString(), "rlawson", "now", "future", cacheKey, "cachedContents/test-id");
        when(metaService.getProjectCacheMeta(eq(projectPath))).thenReturn(Optional.of(existingMeta));
        when(settingsService.getGeminiApiKey()).thenReturn("api-key");
        // We expect it to fail after deletion due to missing files in our mock environment
        try {
            service.pushCache(projectPath);
        } catch (Exception e) {
            // Expected
        }
        // Verify that deleteCache was called for the existing geminiCacheId
        verify(service).deleteCache(eq("cachedContents/test-id"));
        // Verify that deleteProjectCacheMetaByGeminiId was called for the geminiCacheId
        verify(metaService).deleteProjectCacheMetaByGeminiId(eq("cachedContents/test-id"));
    }
}
