package org.roxycode.cache;

import java.util.List;

public record CodebaseCache(
        String projectRoot,
        String user,
        String generatedAt,
        List<CachedFile> files
) {}