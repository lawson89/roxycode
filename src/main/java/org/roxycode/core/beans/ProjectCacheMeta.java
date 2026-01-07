package org.roxycode.core.beans;

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
