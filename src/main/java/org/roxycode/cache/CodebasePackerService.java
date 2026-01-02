package org.roxycode.cache;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;

@Singleton
public class CodebasePackerService {

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

    @Inject
    public CodebasePackerService() {
    }

    /**
     * Streams the cache directly to a file using "Pretty TOML" format.
     */
    public void packCodebaseToFile(Path rootPath, List<String> exclusions, String user, Path outputPath) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            writeHeader(writer, rootPath, user);
            streamFilesToToml(rootPath, exclusions, writer);
        }
    }

    /**
     * Returns the cache as a String using "Pretty TOML" format.
     * Useful for smaller payloads or direct API responses.
     */
    public String packCodebaseToString(Path rootPath, List<String> exclusions, String user) throws IOException {
        StringWriter stringWriter = new StringWriter();

        // We wrap StringWriter in BufferedWriter to reuse the shared logic
        try (BufferedWriter writer = new BufferedWriter(stringWriter)) {
            writeHeader(writer, rootPath, user);
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
            matchers = exclusions.stream()
                    .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                    .toArray(PathMatcher[]::new);
        }

        Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
            @Override
            public @NotNull FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes attrs) {
                if (shouldExclude(rootPath, dir, matchers)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) throws IOException {
                if (shouldExclude(rootPath, file, matchers)) {
                    return FileVisitResult.CONTINUE;
                }

                String mimeType = detectMimeType(file);
                if (mimeType == null) {
                    return FileVisitResult.CONTINUE; // Skip binaries
                }

                String content = Files.readString(file, StandardCharsets.UTF_8);

                // Reused formatting logic
                writeTomlEntry(writer, rootPath.relativize(file).toString(), attrs.size(), mimeType, content);

                return FileVisitResult.CONTINUE;
            }
        });
    }

    // --- TOML Formatting Logic ---

    private void writeHeader(BufferedWriter w, Path root, String user) throws IOException {
        w.write("projectRoot = " + quoteString(root.toAbsolutePath().toString()));
        w.newLine();
        w.write("user = " + quoteString(user));
        w.newLine();
        w.write("generatedAt = " + quoteString(Instant.now().toString()));
        w.newLine();
        w.newLine();
    }

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
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String escapeMultiLineString(String content) {
        return content.replace("'''", "''\\'");
    }

    // --- Logic Helpers ---

    private boolean shouldExclude(Path root, Path file, PathMatcher[] matchers) {
        Path relative = root.relativize(file);
        for (Path part : relative) {
            if (part.toString().startsWith(".")) return true;
        }
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(relative)) return true;
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

        switch (filename) {
            case "dockerfile" -> { return "text/x-dockerfile"; }
            case "makefile" -> { return "text/x-makefile"; }
            case "jenkinsfile" -> { return "text/x-groovy"; }
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