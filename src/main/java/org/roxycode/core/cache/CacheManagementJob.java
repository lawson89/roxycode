package org.roxycode.core.cache;

import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.roxycode.core.RoxyProjectService;
import org.roxycode.core.SettingsService;
import org.roxycode.core.beans.ProjectCacheMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Optional;

@Singleton
@Requires(property = "cache.job.enabled", notEquals = "false")
public class CacheManagementJob {

    private static final Logger LOG = LoggerFactory.getLogger(CacheManagementJob.class);

    private final SettingsService settingsService;
    private final GeminiCacheService geminiCacheService;
    private final ProjectCacheMetaService projectCacheMetaService;
    private final RoxyProjectService roxyProjectService;
    private final ProjectPackerService projectPackerService;

    @Inject
    public CacheManagementJob(SettingsService settingsService,
                              GeminiCacheService geminiCacheService,
                              ProjectCacheMetaService projectCacheMetaService,
                              RoxyProjectService roxyProjectService,
                              ProjectPackerService projectPackerService) {
        this.settingsService = settingsService;
        this.geminiCacheService = geminiCacheService;
        this.projectCacheMetaService = projectCacheMetaService;
        this.roxyProjectService = roxyProjectService;
        this.projectPackerService = projectPackerService;
    }

    @Scheduled(fixedDelay = "1m", initialDelay = "10s")
    public void manageCache() {
        LOG.debug("Checking cache status...");
        try {
            if (!settingsService.isCacheEnabled()) {
                LOG.debug("Cache management job skipped: caching is disabled in settings.");
                return;
            }

            if (settingsService.getGeminiApiKey() == null || settingsService.getGeminiApiKey().isBlank()) {
                LOG.debug("Cache management job skipped: Gemini API Key is not set.");
                return;
            }

            Path projectRoot = roxyProjectService.getProjectRoot();
            if (projectRoot == null) {
                LOG.debug("Cache management job skipped: No project root set.");
                return;
            }

            Optional<ProjectCacheMeta> metaOpt = projectCacheMetaService.getProjectCacheMeta();
            if (metaOpt.isEmpty()) {
                LOG.info("No cache found for project {}. Auto-building and pushing cache...", projectRoot.getFileName());
                projectPackerService.buildProjectCache();
                geminiCacheService.pushCache(projectRoot);
            } else {
                ProjectCacheMeta meta = metaOpt.get();
                long secondsLeft = projectCacheMetaService.getSecondsUntilExpiration(meta);
                LOG.debug("Cache exists for project {}. Id: {}. Seconds until expiration: {}", 
                    projectRoot.getFileName(), meta.geminiCacheId(), secondsLeft);

                // Refresh if less than 5 minutes left (300 seconds)
                if (secondsLeft < 300) {
                    LOG.info("Cache for project {} is expiring soon ({}s left). Refreshing...", 
                        projectRoot.getFileName(), secondsLeft);
                    geminiCacheService.refreshCache(meta.geminiCacheId());
                }
            }
        } catch (Exception e) {
            LOG.error("Error in cache management job: {}", e.getMessage(), e);
        }
    }
}
