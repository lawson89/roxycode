package org.roxycode.app.model.cache;

import java.time.LocalDateTime;

/**
 * Metadata for a project codebase cache in Gemini.
 */
public record ProjectCacheMeta(
    String projectName,
    LocalDateTime lastCached,
    String cacheName,
    long ttlSeconds,
    long estimatedTokens
) {
}
