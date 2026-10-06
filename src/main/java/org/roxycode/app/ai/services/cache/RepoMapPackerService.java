package org.roxycode.app.ai.services.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Service to generate a token-optimized map of the repository.
 * Uses ripgrep for tree generation and ctags for skeletonization.
 */
@Service
@AgentService("repoMapPackerService")
@AgentDoc("Generates a token-optimized map of the repository using ripgrep and universal-ctags.")
public class RepoMapPackerService {
    private static final Logger log = LoggerFactory.getLogger(RepoMapPackerService.class);
    private static final long RAW_CONTENT_THRESHOLD = 2048; // 2KB

    private final ProjectService projectService;
    private final ObjectMapper objectMapper;
    private final GrepService grepService;

    public RepoMapPackerService(ProjectService projectService, ObjectMapper objectMapper, GrepService grepService) {
        this.projectService = projectService;
        this.objectMapper = objectMapper;
        this.grepService = grepService;
    }

    /**
     * Generates a packed representation of the codebase.
     * 
     * @return EditorResult containing the packed repo map.
     */
    @AgentDoc("Generates the repo map string.")
    public EditorResult generateRepoMap() {
        if (!projectService.hasActiveProject()) {
            return new EditorResult(false, "", "No active project");
        }

        Path root = projectService.getCurrentProjectRoot();
        List<String> files = grepService.listFiles(null);
        
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

        String map = sb.toString();
        log.info("Generated repo map. Length: {}, Estimated tokens: {}", map.length(), map.length() / 4);
        
        return new EditorResult(true, map, null);
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