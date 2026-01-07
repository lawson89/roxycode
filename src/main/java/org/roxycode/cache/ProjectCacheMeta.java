package org.roxycode.cache;

public record ProjectCacheMeta(
        String projectRoot,
        String user,
        String generatedAt,
        String cacheKey,
        String geminiCacheId,
        long skeletonTokenCount,
        String skeletonGeneratedAt
) {
}