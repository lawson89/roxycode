package org.roxycode.core.beans;

public record ProjectCacheMeta(
        String projectRoot,
        String user,
        String generatedAt,
        String expiresAt,
        String cacheKey,
        String geminiCacheId
) {
}
