package org.roxycode.app.ai.services.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.model.ProjectCacheMeta;
import org.roxycode.app.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Service to generate a token-optimized map of the repository.
 * Uses ripgrep for tree generation and ctags for skeletonization.
 * Implements lazy evaluation to avoid redundant processing.
 */
@Service
@AgentService(value = "repoMapPackerService", roles = {"*"})
@AgentDoc("Generates a token-optimized map of the repository using ripgrep and universal-ctags.")
public class RepoMapPackerService {
    private static final Logger log = LoggerFactory.getLogger(RepoMapPackerService.class);
    private static final long RAW_CONTENT_THRESHOLD = 2048; // 2KB

    private final ProjectService projectService;
    private final ObjectMapper objectMapper;
    private final GrepService grepService;
    private final ProjectCacheMetaService metaService;
    private final GeminiCacheService geminiCacheService;

    private String cachedMap = null;
    private boolean needsUpdate = true;
    private List<String> excludePatterns = new ArrayList<>();

    public RepoMapPackerService(ProjectService projectService, ObjectMapper objectMapper, GrepService grepService, 
                               ProjectCacheMetaService metaService, GeminiCacheService geminiCacheService) {
        this.projectService = projectService;
        this.objectMapper = objectMapper;
        this.grepService = grepService;
        this.metaService = metaService;
        this.geminiCacheService = geminiCacheService;

        // Listen for project changes
        this.excludePatterns = List.of("src/main/resources/prompts", "src/main/resources/docs");
        this.projectService.addProjectListener(p -> {
            this.needsUpdate = true;
            log.debug("Repo map invalidated due to project change.");
        });
    }

    @EventListener
    public void handleFileChanged(FileChangedEvent event) {
        this.needsUpdate = true;
        log.debug("Repo map invalidated due to file change: {}", event.path());
    }

    /**
     * Generates a packed representation of the codebase.
     * 
     * @return EditorResult containing the packed repo map.
     */
    @AgentDoc("Generates the repo map string and saves it to the implicit cache.")
    public EditorResult generateRepoMap() {
        if (!projectService.hasActiveProject()) {
            return new EditorResult(false, "", "No active project");
        }

        if (!needsUpdate && cachedMap != null) {
            log.debug("Returning cached repo map.");
            return new EditorResult(true, cachedMap, null);
        }

        Path root = projectService.getCurrentProjectRoot();
        List<String> files = grepService.listFiles(null);
        files = files.stream()
            .filter(f -> excludePatterns.stream().noneMatch(p -> f.startsWith(p)))
            .toList();
        
        StringBuilder sb = new StringBuilder();
        sb.append("# Repo Map Snapshot\n\n");
        sb.append("## Project Tree\n\n```\n");
        sb.append(FileTreeFormatter.format(files));
        sb.append("\n```\n\n");
        
        for (String relPath : files) {
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
                    String skeleton = getSkeleton(filePath, root);
                    if (skeleton == null || skeleton.isBlank()) {
                        sb.append("[Full content omitted due to size - no skeleton available]\n");
                    } else {
                        sb.append(skeleton);
                    }
                }
            } catch (Exception e) {
                sb.append("[Error processing file: ").append(e.getMessage()).append("]\n");
            }
            sb.append("\n\n");
        }

        this.cachedMap = sb.toString();
        this.needsUpdate = false;
        
        long tokens = geminiCacheService.estimateTokens(cachedMap);
        log.info("Generated repo map. Length: {}, Estimated tokens: {}", cachedMap.length(), tokens);
        
        // Save to disk for persistence and UI display
        metaService.saveRepoCache(cachedMap);
        metaService.saveMeta(new ProjectCacheMeta(
            projectService.getProjectName(),
            LocalDateTime.now(),
            tokens
        ));
        
        return new EditorResult(true, cachedMap, null);
    }

    private String getSkeleton(Path filePath, Path root) {
        try {
            Process process = new ProcessBuilder("ctags", "-f", "-", "--output-format=json", "--fields=+n", filePath.toAbsolutePath().toString())
                    .start();
            
            List<Tag> tags = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        JsonNode node = objectMapper.readTree(line);
                        JsonNode nameNode = node.get("name");
                        JsonNode lineNode = node.get("line");
                        if (nameNode != null && lineNode != null) {
                            tags.add(new Tag(
                                nameNode.asText(),
                                lineNode.asInt()
                            ));
                        }
                    } catch (Exception e) {
                        // Skip unparseable lines
                    }
                }
            }
            process.waitFor();

            if (tags.isEmpty()) {
                return null;
            }

            tags.sort(Comparator.comparingInt(t -> t.line));

            List<String> lines = Files.readAllLines(filePath);
            StringBuilder skeleton = new StringBuilder();
            int lastLine = -1;
            for (Tag tag : tags) {
                if (tag.line > 0 && tag.line <= lines.size() && tag.line != lastLine) {
                    skeleton.append(tag.line).append(": ").append(lines.get(tag.line - 1).trim()).append("\n");
                    lastLine = tag.line;
                }
            }
            return skeleton.toString();

        } catch (Exception e) {
            log.warn("Ctags failed for {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    private record Tag(String name, int line) {}
}
