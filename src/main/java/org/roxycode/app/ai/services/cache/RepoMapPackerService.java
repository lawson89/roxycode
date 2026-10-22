package org.roxycode.app.ai.services.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Service to generate a token-optimized map of the repository. Uses ripgrep for tree generation and
 * ctags for skeletonization. Implements lazy evaluation to avoid redundant processing.
 */
@Service
public class RepoMapPackerService {
    private static final Logger log = LoggerFactory.getLogger(RepoMapPackerService.class);
    private static final long RAW_CONTENT_THRESHOLD = 2048; // 2KB
    private static final String CANCELLED_MSG = "Repo map operation cancelled";

    private final ProjectService projectService;
    private final ObjectMapper objectMapper;
    private final GrepService grepService;
    private final ProjectCacheMetaService metaService;

    private String cachedMap = null;
    private boolean needsUpdate = true;
    private List<String> excludePatterns;

    public RepoMapPackerService(
            ProjectService projectService,
            ObjectMapper objectMapper,
            GrepService grepService,
            ProjectCacheMetaService metaService) {
        this.projectService = projectService;
        this.objectMapper = objectMapper;
        this.grepService = grepService;
        this.metaService = metaService;

        this.excludePatterns = List.of("src/main/resources/prompts", "src/main/resources/docs");
    }

    @EventListener
    public void handleProjectChanged(ProjectChangedEvent event) {
        this.needsUpdate = true;
        log.debug("Repo map invalidated due to project change.");
    }

    @EventListener
    public void handleFileChanged(FileChangedEvent event) {
        this.needsUpdate = true;
        log.debug("Repo map invalidated due to file change: {}", event.path());
    }

    public EditorResult generateRepoMap() {
        ExceptionUtils.checkInterrupted("Repo map generation cancelled");
        if (!projectService.hasActiveProject()) {
            return new EditorResult(false, "", "No active project");
        }

        if (!needsUpdate && cachedMap != null) {
            return new EditorResult(true, cachedMap, null);
        }

        Path root = projectService.getCurrentProjectRoot();
        List<String> rawFiles = grepService.listFiles(null);
        List<String> files = new ArrayList<>();
        for (String f : rawFiles) {
            if (!isExcluded(f) && !isBinaryFile(root.resolve(f))) {
                files.add(f);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Repo Map Snapshot\n\n");
        sb.append("## Project Tree\n\n```\n");
        sb.append(FileTreeFormatter.format(files));
        sb.append("\n```\n\n");

        for (String relPath : files) {
            ExceptionUtils.checkInterrupted("Repo map generation cancelled");
            Path filePath = root.resolve(relPath);
            if (!Files.isRegularFile(filePath)) {
                continue;
            }
            sb.append("--- ").append(relPath).append(" ---\n");
            try {
                long size = Files.size(filePath);
                if (size < RAW_CONTENT_THRESHOLD) {
                    sb.append(Files.readString(filePath));
                } else {
                    String skeleton = getSkeleton(filePath);
                    if (skeleton == null || skeleton.isBlank()) {
                        sb.append("[Full content omitted due to size - no skeleton available]\n");
                    } else {
                        sb.append(skeleton);
                    }
                }
            } catch (Exception e) {
                ExceptionUtils.rethrowIfCancelled(e, CANCELLED_MSG);
                sb.append("[Error processing file: ").append(e.getMessage()).append("]\n");
            }
            sb.append("\n\n");
        }

        this.cachedMap = sb.toString();
        this.needsUpdate = false;
        metaService.saveRepoCache(cachedMap);
        return new EditorResult(true, cachedMap, null);
    }

    private String getSkeleton(Path filePath) {
        try {
            List<String> command =
                    List.of(
                            "ctags",
                            "-f",
                            "-",
                            "--output-format=json",
                            "--fields=+n",
                            filePath.toAbsolutePath().toString());
            ProcessResult result = ProcessRunner.run(command, null, false);
            if (result.timedOut()) {
                log.warn("Ctags timed out for {}", filePath);
                return null;
            }

            List<Tag> tags = new ArrayList<>();
            String output = result.output();
            if (output != null && !output.isEmpty()) {
                for (String line : output.split("\\R")) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        JsonNode node = objectMapper.readTree(line);
                        JsonNode nameNode = node.get("name");
                        JsonNode lineNode = node.get("line");
                        if (nameNode != null && lineNode != null) {
                            tags.add(new Tag(nameNode.asText(), lineNode.asInt()));
                        }
                    } catch (Exception e) {
                        ExceptionUtils.rethrowIfCancelled(e, CANCELLED_MSG);
                        log.trace("Skip unparseable line: {}", line);
                    }
                }
            }
            if (tags.isEmpty()) return null;
            tags.sort(Comparator.comparingInt(t -> t.line));
            List<String> lines = Files.readAllLines(filePath);
            StringBuilder skeleton = new StringBuilder();
            int lastLine = -1;
            for (Tag tag : tags) {
                if (tag.line > 0 && tag.line <= lines.size() && tag.line != lastLine) {
                    skeleton.append(tag.line)
                            .append(": ")
                            .append(lines.get(tag.line - 1).trim())
                            .append('\n');
                    lastLine = tag.line;
                }
            }
            return skeleton.toString();
        } catch (Exception e) {
            ExceptionUtils.rethrowIfCancelled(e, CANCELLED_MSG);
            log.warn("Ctags failed for {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    private boolean isExcluded(String filePath) {
        for (String pattern : excludePatterns) {
            if (filePath.startsWith(pattern)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBinaryFile(Path path) {
        try {
            if (Files.exists(path)) {
                try (java.io.InputStream is = Files.newInputStream(path)) {
                    byte[] buffer = new byte[1024];
                    int bytesRead = is.read(buffer);
                    for (int i = 0; i < bytesRead; i++) {
                        if (buffer[i] == 0) return true;
                    }
                }
            }
        } catch (Exception e) {
            ExceptionUtils.rethrowIfCancelled(e, CANCELLED_MSG);
            log.warn("Could not determine if file is binary: {}", path, e);
        }
        return false;
    }

    private record Tag(String name, int line) {}
}
