package org.roxycode.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.roxycode.core.RoxyProjectService;
import org.roxycode.core.Sandbox;
import org.roxycode.core.analysis.JavaAnalysisService;
import org.roxycode.core.beans.ContentBundle;
import org.roxycode.core.beans.NamedContent;
import org.roxycode.core.tools.service.BuildToolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
    private final JavaAnalysisService javaContextService;
    private final ObjectMapper objectMapper;

    @Inject
    public ProjectPackerService(RoxyProjectService roxyProjectService, Sandbox sandbox,
                                BuildToolService buildToolService, JavaAnalysisService javaContextService, @Named("toml") ObjectMapper objectMapper) {
        this.roxyProjectService = roxyProjectService;
        this.sandbox = sandbox;
        this.buildToolService = buildToolService;
        this.javaContextService = javaContextService;
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
        List<NamedContent> contents = new ArrayList<>();
        NamedContent systemPrompt = new NamedContent("system_prompt", roxyProjectService.getStaticSystemPrompt());
        writer.write(convertContentToToml(systemPrompt));
        String javaSkeleton = javaContextService.generateSkeletonToString(rootPath);
        NamedContent javaSkeletonContent = new NamedContent("java_skeleton", javaSkeleton);
        writer.write(convertContentToToml(javaSkeletonContent));
    }

    public String convertContentToToml(NamedContent content) {
        StringBuilder sb = new StringBuilder();
        sb.append("[[content]]\n");
        sb.append("name = \"").append(content.getName()).append("\"\n");

        // Use triple-quotes for code content so it's readable for the LLM
        sb.append("content = '''\n");
        sb.append(content.getContent());
        sb.append("\n'''\n\n");
        return sb.toString();
    }

    /**
     * Utility to generate consistent cache keys.
     * Kept public as it is used by other services (e.g. push service).
     */
    public String getCacheKey(Path root, String user, String geminiModel) {
        return "roxycode_cache_" + user + "_" + root.getFileName() + "_" + geminiModel;
    }

}
