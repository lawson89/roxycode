package org.roxycode.cache;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jetbrains.annotations.NotNull;
import org.roxycode.core.RoxyProjectService;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CodebasePackerService is responsible for creating a snapshot of the project code in a format
 * easy for the LLM to use
 */
@Singleton
public class CodebasePackerService {

    public static final String CACHE_FILENAME = "codebase_cache.toml";

    // Helper map for fast MIME detection
    private static final Map<String, String> EXTENSION_MAP = new HashMap<>();

    static {
        // Java Ecosystem
        EXTENSION_MAP.put("java", "text/x-java-source");
        EXTENSION_MAP.put("kt", "text/x-kotlin");
        EXTENSION_MAP.put("kts", "text/x-kotlin");
        EXTENSION_MAP.put("groovy", "text/x-groovy");
        EXTENSION_MAP.put("gradle", "text/x-gradle");
        EXTENSION_MAP.put("properties", "text/x-java-properties");
        // Web / Config
        EXTENSION_MAP.put("xml", "text/xml");
        EXTENSION_MAP.put("json", "application/json");
        EXTENSION_MAP.put("yaml", "text/yaml");
        EXTENSION_MAP.put("yml", "text/yaml");
        EXTENSION_MAP.put("toml", "text/toml");
        EXTENSION_MAP.put("sql", "text/x-sql");
        EXTENSION_MAP.put("html", "text/html");
        EXTENSION_MAP.put("css", "text/css");
        EXTENSION_MAP.put("js", "text/javascript");
        EXTENSION_MAP.put("ts", "text/typescript");
        EXTENSION_MAP.put("sh", "text/x-shellscript");
        EXTENSION_MAP.put("md", "text/markdown");
        EXTENSION_MAP.put("txt", "text/plain");
        EXTENSION_MAP.put("gitignore", "text/plain");
        EXTENSION_MAP.put("dockerfile", "text/x-dockerfile");
    }

    private final RoxyProjectService roxyProjectService;

    @Inject
    public CodebasePackerService(RoxyProjectService roxyProjectService) {
        this.roxyProjectService = roxyProjectService;
    }

    public void buildProjectCache() throws IOException {
        Path cacheDir = roxyProjectService.getRoxyProjectCacheDir();
        Path outputPath = cacheDir.resolve(CACHE_FILENAME);
        // User argument removed as it was only for metadata
        packCodebaseToFile(cacheDir, Collections.emptyList(), outputPath);
    }

    /**
     * Estimates the token count of a cache file.
     * Rough estimate: 4 characters per token.
     */
    public long estimateTokenCount(Path cacheFile) throws IOException {
        if (cacheFile == null || !Files.exists(cacheFile)) {
            return 0;
        }
        long bytes = Files.size(cacheFile);
        return bytes / 4;
    }

    /**
     * Streams the cache directly to a file using "Pretty TOML" format.
     */
    public void packCodebaseToFile(Path rootPath, List<String> exclusions, Path outputPath) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            // Header writing removed
            streamFilesToToml(rootPath, exclusions, writer);
        }
    }

    /**
     * Returns the cache as a String using "Pretty TOML" format.
     */
    public String packCodebaseToString(Path rootPath, List<String> exclusions) throws IOException {
        StringWriter stringWriter = new StringWriter();
        try (BufferedWriter writer = new BufferedWriter(stringWriter)) {
            // Header writing removed
            streamFilesToToml(rootPath, exclusions, writer);
        }
        return stringWriter.toString();
    }

    /**
     * Shared logic to walk the file tree and write entries to any BufferedWriter.
     */
    private void streamFilesToToml(Path rootPath, List<String> exclusions, BufferedWriter writer) throws IOException {
        PathMatcher[] matchers;
        if (exclusions == null || exclusions.isEmpty()) {
            matchers = new PathMatcher[0];
        } else {
            matchers = exclusions.stream().map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern)).toArray(PathMatcher[]::new);
        }
        Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {

            @Override
            @NotNull
            public FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes attrs) {
                if (shouldExclude(rootPath, dir, matchers)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            @NotNull
            public FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) throws IOException {
                if (shouldExclude(rootPath, file, matchers)) {
                    return FileVisitResult.CONTINUE;
                }
                String mimeType = detectMimeType(file);
                if (mimeType == null) {
                    // Skip binaries
                    return FileVisitResult.CONTINUE;
                }
                String content = Files.readString(file, StandardCharsets.UTF_8);
                // Reused formatting logic
                writeTomlEntry(writer, rootPath.relativize(file).toString(), attrs.size(), mimeType, content);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Utility to generate consistent cache keys.
     * Kept public as it is used by other services (e.g. push service).
     */
    public String getCacheKey(Path root, String user, String geminiModel) {
        return "roxycode_cache_" + user + "_" + root.getFileName() + "_" + geminiModel;
    }

    // --- TOML Formatting Logic ---
    private void writeTomlEntry(BufferedWriter w, String path, long size, String mime, String content) throws IOException {
        w.write("[[files]]");
        w.newLine();
        w.write("path = " + quoteString(path));
        w.newLine();
        w.write("size = " + size);
        w.newLine();
        w.write("mimeType = " + quoteString(mime));
        w.newLine();
        w.write("content = '''");
        w.newLine();
        w.write(escapeMultiLineString(content));
        if (!content.endsWith("\n")) {
            w.newLine();
        }
        w.write("'''");
        w.newLine();
        w.newLine();
    }

    private String quoteString(String s) {
        if (s == null)
            return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String escapeMultiLineString(String content) {
        return content.replace("'''", "''\\'");
    }

    // --- Logic Helpers ---
    private boolean shouldExclude(Path root, Path file, PathMatcher[] matchers) {
        Path relative = root.relativize(file);
        for (Path part : relative) {
            if (part.toString().startsWith("."))
                return true;
        }
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(relative))
                return true;
        }
        return false;
    }

    private String detectMimeType(Path file) {
        String filename = file.getFileName().toString().toLowerCase();
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex != -1) {
            String ext = filename.substring(dotIndex + 1);
            if (EXTENSION_MAP.containsKey(ext)) {
                return EXTENSION_MAP.get(ext);
            }
        }
        switch(filename) {
            case "dockerfile" ->
                {
                    return "text/x-dockerfile";
                }
            case "makefile" ->
                {
                    return "text/x-makefile";
                }
            case "jenkinsfile" ->
                {
                    return "text/x-groovy";
                }
        }
        try {
            if (BinaryCheck.isBinaryFile(file.toFile())) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        return "text/plain";
    }
}
