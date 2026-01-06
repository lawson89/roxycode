package org.roxycode.cache;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.roxycode.core.RoxyProjectService;
import org.roxycode.core.Sandbox;
import org.roxycode.core.tools.ScriptServiceRegistry;
import org.roxycode.core.tools.service.BuildToolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * CodebasePackerService is responsible for creating a snapshot of the project code in a format
 * easy for the LLM to use
 */
@Singleton
public class CodebasePackerService {

    private static final Logger LOG = LoggerFactory.getLogger(CodebasePackerService.class);

    public static final String CACHE_FILENAME = "codebase_cache.toml";


    private final RoxyProjectService roxyProjectService;
    private final Sandbox sandbox;
    private final FileListingService fileListingService;
    private final BuildToolService buildToolService;
    private final JavaContextService javaContextService;

    @Inject
    public CodebasePackerService(RoxyProjectService roxyProjectService, Sandbox sandbox,
                                 FileListingService fileListingService, BuildToolService buildToolService, JavaContextService javaContextService) {
        this.roxyProjectService = roxyProjectService;
        this.sandbox = sandbox;
        this.fileListingService = fileListingService;
        this.buildToolService = buildToolService;
        this.javaContextService = javaContextService;
    }

    public Path getCacheFilePath() throws IOException {
        return roxyProjectService.getRoxyProjectCacheDir().resolve(CACHE_FILENAME);
    }

    public String getCacheLastModified() throws IOException {
        Path path = getCacheFilePath();
        if (Files.exists(path)) {
            return Files.getLastModifiedTime(path).toString();
        }
        return "N/A";
    }

    public long getCacheEstimatedTokenCount() throws IOException {
        return estimateTokenCount(getCacheFilePath());
    }


    public void buildProjectCache() throws IOException {
        Path rootPath = sandbox.getRoot();
        Path cacheDir = roxyProjectService.getRoxyProjectCacheDir();
        Path outputPath = cacheDir.resolve(CACHE_FILENAME);
        packCodebaseToFile(rootPath, outputPath);
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
    public void packCodebaseToFile(Path rootPath, Path outputPath) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            // Header writing removed
            streamFilesToToml(rootPath, writer);
        }
    }

    /**
     * Returns the cache as a String using "Pretty TOML" format.
     */
    public String packCodebaseToString(Path rootPath) throws IOException {
        StringWriter stringWriter = new StringWriter();
        try (BufferedWriter writer = new BufferedWriter(stringWriter)) {
            // Header writing removed
            streamFilesToToml(rootPath, writer);
        }
        return stringWriter.toString();
    }

    /**
     * Shared logic to walk the file tree and write entries to any BufferedWriter.
     */
    protected void streamFilesToToml(Path rootPath, BufferedWriter writer) throws IOException {
        String header = buildToolService.getProjectSummary();
        header += "\n\nThis is a codebase snapshot in TOML format including Java signatures for analysis.";
        writeTomlHeader(writer, "header", header);
        // Java source files via JavaContextService
        writer.write("[java]");
        writer.newLine();
        writer.write("content = '''");
        javaContextService.generateSkeleton(rootPath, writer);
        writer.newLine();
        writer.write("'''");
        writer.newLine();
        //@todo externalize this
//        List<String> sourceExtensions = List.of(
//                "java", "kt", "groovy"
//        );
//        List<Path> sourceFiles = fileListingService.findFiles(rootPath.resolve("src").resolve("main"), sourceExtensions);
//        for (Path sourceFile : sourceFiles) {
//            long size = Files.size(sourceFile);
//            String content;
//            // @todo externalize this
//            if (size > 30 * 1024) {
//                content = "IMPORTANT! File exceeds size limit, please use tools to read if needed";
//                LOG.info("skipping file {} due to size {}", sourceFile, size);
//            } else {
//                try {
//                    content = Files.readString(sourceFile, StandardCharsets.UTF_8);
//                }catch (Exception e) {
//                    LOG.warn("unable to read file {}", sourceFile, e);
//                    content = "IMPORTANT! Unable to read file due to: " + e;
//                }
//            }
//            String mimeType = Files.probeContentType(sourceFile);
//            String path = rootPath.relativize(sourceFile).toString();
//            writeTomlEntry(writer, path, size, mimeType, content);
//        }
    }

    /**
     * Utility to generate consistent cache keys.
     * Kept public as it is used by other services (e.g. push service).
     */
    public String getCacheKey(Path root, String user, String geminiModel) {
        return "roxycode_cache_" + user + "_" + root.getFileName() + "_" + geminiModel;
    }

    protected void writeTomlHeader(BufferedWriter w, String title, String content) throws IOException {
        w.write("[" + title + "]");
        w.newLine();
        w.write("content = '''");
        w.newLine();
        w.write(escapeMultiLineString(content));
        w.newLine();
        w.write("'''");
        w.newLine();
    }

    // --- TOML Formatting Logic ---
    protected void writeTomlEntry(BufferedWriter w, String path, long size, String mime, String content) throws IOException {
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
        w.newLine();
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

}
