package org.roxycode.app.model.cache;

import java.time.LocalDateTime;

/**
 * Metadata for a project codebase cache (Repo Map).
 */
public record ProjectCacheMeta(
    String projectName,
    LocalDateTime lastUpdated,
    long estimatedTokens
) {
}
