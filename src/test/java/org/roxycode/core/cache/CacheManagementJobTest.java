package org.roxycode.core.cache;

import io.micronaut.test.annotation.MockBean;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.roxycode.core.RoxyProjectService;
import org.roxycode.core.GeminiClientFactory;
import org.roxycode.core.SettingsService;

import java.io.IOException;
import java.util.List;
import java.util.Collections;
import java.nio.file.Path;
import java.util.Optional;

import static org.mockito.Mockito.*;

@MicronautTest
@Property(name = "cache.job.enabled", value = "true")
public class CacheManagementJobTest {

    @Inject
    CacheManagementJob cacheManagementJob;

    @Inject
    SettingsService settingsService;

    @Inject
    GeminiCacheService geminiCacheService;

    @Inject
    ProjectCacheMetaService projectCacheMetaService;

    @Inject
    RoxyProjectService roxyProjectService;

    @Inject
    ProjectPackerService projectPackerService;
    @BeforeEach
    void resetCleanup() {
        cacheManagementJob.cleanupDone = false;
    }


    @Test
    void testManageCache_WhenEnabledAndMissing() throws IOException {
        Path mockPath = Path.of("/mock/project");
        when(settingsService.isCacheEnabled()).thenReturn(true);
        when(settingsService.getRoxyHome()).thenReturn(java.nio.file.Paths.get("target/roxy_home"));
        when(settingsService.getGeminiModel()).thenReturn("gemini-1.5-flash");
        when(roxyProjectService.getProjectRoot()).thenReturn(mockPath);
        when(projectCacheMetaService.getProjectCacheMeta()).thenReturn(Optional.empty());

        cacheManagementJob.manageCache();

        verify(projectPackerService, times(1)).buildProjectCache();
        verify(geminiCacheService, times(1)).pushCache(mockPath);
    }

    @Test
    void testManageCache_WhenDisabled() throws IOException {
        when(settingsService.isCacheEnabled()).thenReturn(false);

        cacheManagementJob.manageCache();

        verify(projectPackerService, never()).buildProjectCache();
        verify(geminiCacheService, never()).pushCache(any());
    }


    @Test
    void testManageCache_WhenExpiringSoon() throws IOException {
        Path mockPath = Path.of("/mock/project");
        when(settingsService.isCacheEnabled()).thenReturn(true);
        when(settingsService.getGeminiApiKey()).thenReturn("mock-api-key");
        when(roxyProjectService.getProjectRoot()).thenReturn(mockPath);
        
        org.roxycode.core.beans.ProjectCacheMeta meta = mock(org.roxycode.core.beans.ProjectCacheMeta.class);
        when(meta.geminiCacheId()).thenReturn("cache-123");
        when(projectCacheMetaService.getProjectCacheMeta()).thenReturn(Optional.of(meta));
        when(projectCacheMetaService.getSecondsUntilExpiration(meta)).thenReturn(100L); // Less than 300

        cacheManagementJob.manageCache();

        verify(geminiCacheService, times(1)).refreshCache("cache-123");
    }

    @Test
    void testManageCache_WhenAlreadyExists() throws IOException {
        Path mockPath = Path.of("/mock/project");
        when(settingsService.isCacheEnabled()).thenReturn(true);
        when(roxyProjectService.getProjectRoot()).thenReturn(mockPath);
        when(projectCacheMetaService.getProjectCacheMeta()).thenReturn(Optional.of(mock(org.roxycode.core.beans.ProjectCacheMeta.class)));

        cacheManagementJob.manageCache();

        verify(projectPackerService, never()).buildProjectCache();
        verify(geminiCacheService, never()).pushCache(any());
    }

    @MockBean(SettingsService.class)
    SettingsService settingsService() {
        SettingsService mock = mock(SettingsService.class);
        lenient().when(mock.getRoxyHome()).thenReturn(java.nio.file.Paths.get("target/roxy_home"));
        lenient().when(mock.getGeminiModel()).thenReturn("gemini-1.5-flash");
        lenient().when(mock.getGeminiApiKey()).thenReturn("mock-api-key");
        return mock;
    }

    @MockBean(GeminiCacheService.class)
    GeminiCacheService geminiCacheService() {
        return mock(GeminiCacheService.class);
    }

    @MockBean(ProjectCacheMetaService.class)
    ProjectCacheMetaService projectCacheMetaService() {
        return mock(ProjectCacheMetaService.class);
    }

    @MockBean(RoxyProjectService.class)
    RoxyProjectService roxyProjectService() {
        return mock(RoxyProjectService.class);
    }

    @MockBean(ProjectPackerService.class)
    ProjectPackerService projectPackerService() {
        return mock(ProjectPackerService.class);
    }

    @MockBean(GeminiClientFactory.class)
    GeminiClientFactory geminiClientFactory() {
        return mock(GeminiClientFactory.class);
    }

    @Test
    void testManageCache_WhenNotExpiringSoon() throws IOException {
        Path mockPath = Path.of("/mock/project");
        when(settingsService.isCacheEnabled()).thenReturn(true);
        when(settingsService.getGeminiApiKey()).thenReturn("mock-api-key");
        when(roxyProjectService.getProjectRoot()).thenReturn(mockPath);

        org.roxycode.core.beans.ProjectCacheMeta meta = mock(org.roxycode.core.beans.ProjectCacheMeta.class);
        when(meta.geminiCacheId()).thenReturn("cache-123");
        when(projectCacheMetaService.getProjectCacheMeta()).thenReturn(Optional.of(meta));
        when(projectCacheMetaService.getSecondsUntilExpiration(meta)).thenReturn(600L); // More than 300

        cacheManagementJob.manageCache();

        verify(geminiCacheService, never()).refreshCache(anyString());
    }


    @Test
    void testManageCache_PerformsCleanupOnFirstRun() throws IOException {
        Path mockPath = Path.of("/mock/project");
        when(settingsService.isCacheEnabled()).thenReturn(true);
        when(settingsService.getGeminiApiKey()).thenReturn("mock-api-key");
        when(roxyProjectService.getProjectRoot()).thenReturn(mockPath);

        org.roxycode.core.beans.ProjectCacheMeta meta1 = mock(org.roxycode.core.beans.ProjectCacheMeta.class);
        when(meta1.cacheKey()).thenReturn("key1");
        when(meta1.geminiCacheId()).thenReturn("id1");
        
        org.roxycode.core.beans.ProjectCacheMeta meta2 = mock(org.roxycode.core.beans.ProjectCacheMeta.class);
        when(meta2.cacheKey()).thenReturn("key2");
        when(meta2.geminiCacheId()).thenReturn("id2");

        when(projectCacheMetaService.listAllMetadata()).thenReturn(List.of(meta1, meta2));
        
        // Mock meta1 as expired
        when(projectCacheMetaService.getSecondsUntilExpiration(meta1)).thenReturn(0L);
        // Mock meta2 as active but orphaned (not in online caches)
        when(projectCacheMetaService.getSecondsUntilExpiration(meta2)).thenReturn(1000L);
        
        when(geminiCacheService.listCaches()).thenReturn(Collections.emptyList());

        cacheManagementJob.manageCache();

        verify(projectCacheMetaService, times(1)).deleteProjectCacheMetaByCacheKey("key1");
        verify(projectCacheMetaService, times(1)).deleteProjectCacheMetaByCacheKey("key2");
    }
}
