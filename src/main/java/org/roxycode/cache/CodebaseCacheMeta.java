package org.roxycode.cache;

import java.util.List;

public record CodebaseCacheMeta(
        String projectRoot,
        String user,
        String generatedAt,
        String cacheKey,
        String geminiCacheId
) {}