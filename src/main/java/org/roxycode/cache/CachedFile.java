package org.roxycode.cache;


public record CachedFile(
        String path,
        long size,
        String mimeType,  // <--- Added this field
        String content
) {}
