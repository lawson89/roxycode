package org.roxycode.core.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.roxycode.core.RoxyProjectService;
import org.roxycode.core.Sandbox;
import org.roxycode.core.analysis.JavaSourceAnalysisService;
import org.roxycode.core.analysis.JavaSourceGraphService;
import org.roxycode.core.beans.NamedContent;
import org.roxycode.core.tools.service.BuildToolService;
import org.roxycode.core.tools.service.FileSystemService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ProjectPackerService is responsible for creating a snapshot of the project code in a format
 * easy for the LLM to use
 */
@Singleton
public class ProjectPackerService {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectPackerService.class);

    public static final String CACHE_FILENAME = "codebase_cache.toml";

    private final RoxyProjectService roxyProjectService;
    private final Sandbox sandbox;
    private final BuildToolService buildToolService;
    private final JavaSourceAnalysisService javaSourceAnalysisService;
    private final JavaSourceGraphService javaSourceGraphService;
    private final FileSystemService fileSystemService;
    private final ObjectMapper objectMapper;

    @Inject
    public ProjectPackerService(RoxyProjectService roxyProjectService, Sandbox sandbox,
                                BuildToolService buildToolService, JavaSourceAnalysisService javaSourceAnalysisService,
                                JavaSourceGraphService javaSourceGraphService,
                                FileSystemService fileSystemService,
                                @Named("toml") ObjectMapper objectMapper) {
        this.roxyProjectService = roxyProjectService;
        this.sandbox = sandbox;
        this.buildToolService = buildToolService;
        this.javaSourceAnalysisService = javaSourceAnalysisService;
        this.javaSourceGraphService = javaSourceGraphService;
        this.fileSystemService = fileSystemService;
        this.objectMapper = objectMapper;
    }

    public Path getCacheFilePath() throws IOException {
        return roxyProjectService.getRoxyCacheDir().resolve(CACHE_FILENAME);
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
        Path cacheDir = roxyProjectService.getRoxyCacheDir();
        Path outputPath = cacheDir.resolve(CACHE_FILENAME);
        packCodebaseToFile(rootPath, outputPath);
    }

    /**
     * Estimates the token count of a cache file.
     * Rough estimate: 4 characters per token.
     */
    public long estimateTokenCount(Path cacheFile) throws IOException {
        if (cacheFile == null || !Files.exists(cacheFile)) return 0;
        long bytes = Files.size(cacheFile);
        return bytes / 4;
    }

    /**
     * Streams the cache directly to a file using "Pretty TOML" format.
     */
    public void packCodebaseToFile(Path rootPath, Path outputPath) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            streamFilesToToml(rootPath, writer);
        }
    }

    /**
     * Returns the cache as a String using "Pretty TOML" format.
     */
    public String packCodebaseToString(Path rootPath) throws IOException {
        StringWriter sw = new StringWriter();
        try (BufferedWriter writer = new BufferedWriter(sw)) {
            streamFilesToToml(rootPath, writer);
        }
        return sw.toString();
    }

    /**
     * Shared logic to walk the file tree and write entries to any BufferedWriter.
     */
    protected void streamFilesToToml(Path rootPath, BufferedWriter writer) throws IOException {
        writer.write("[[content]]\n");
        writer.write("name = \"java_skeleton\"\n");
        writer.write("content = '''\n");
        writer.write(javaSourceAnalysisService.generateSkeletonToString(rootPath));
        writer.write("\n'''\n\n");

        writer.write("[[content]]\n");
        writer.write("name = \"java_graph\"\n");
        writer.write("content = '''\n");
        writer.write(javaSourceGraphService.generateMermaidGraph(rootPath));
        writer.write("\n'''\n\n");
        writer.write("[[content]]\n");
        writer.write("name = \"project_tree\"\n");
        writer.write("content = '''\n");
        writer.write(fileSystemService.tree("."));
        writer.write("\n'''\n\n");


    }

    public String convertContentToToml(NamedContent content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (IOException e) {
            LOG.error("Failed to convert content to TOML: {}", e.getMessage());
            return "";
        }
    }
}
