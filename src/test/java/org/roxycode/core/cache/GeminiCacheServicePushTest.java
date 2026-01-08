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
import com.google.genai.types.CachedContent;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
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

        Path projectPath = tempDir.resolve("project");
        Files.createDirectories(projectPath);
        
        String cacheKey = "rlawson_project_gemini-1.5-flash";
        when(metaService.getCacheKey(any(), any(), any())).thenReturn(cacheKey);
        when(settingsService.getGeminiModel()).thenReturn("gemini-1.5-flash");

        // Mock listCaches to return a duplicate
        CachedContent duplicateCache = mock(CachedContent.class);
        when(duplicateCache.displayName()).thenReturn(Optional.of(cacheKey));
        when(duplicateCache.name()).thenReturn(Optional.of("cachedContents/gemini-duplicate-id"));
        
        CachedContent otherCache = mock(CachedContent.class);
        when(otherCache.displayName()).thenReturn(Optional.of("other-key"));
        when(otherCache.name()).thenReturn(Optional.of("cachedContents/other-id"));

        doReturn(List.of(duplicateCache, otherCache)).when(service).listCaches();
        
        // Mock deleteCache to avoid actual Gemini calls
        doNothing().when(service).deleteCache(anyString());
        
        when(settingsService.getGeminiApiKey()).thenReturn("api-key");

        // We expect it to fail after deletion due to missing files in our mock environment
        try {
            service.pushCache(projectPath);
        } catch (Exception e) {
            // Expected
        }

        // Verify that deleteCache was called for the duplicate
        verify(service).deleteCache(eq("cachedContents/gemini-duplicate-id"));
        
        // Verify that deleteCache was NOT called for the other one
        verify(service, never()).deleteCache(eq("cachedContents/other-id"));

        // Verify that local metadata cleanup was called
        verify(metaService).deleteProjectCacheMetaByCacheKey(eq(cacheKey));
    }
}