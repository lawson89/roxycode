package org.roxycode.app.ai.services.cache;

import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Service to pack the codebase into a structured format for Gemini caching.
 */
@Service
@AgentService("projectPackerService")
@AgentDoc("Packs the repository into a structured mapping for LLM context, respecting .gitignore.")
public class ProjectPackerService {
    private static final Logger log = LoggerFactory.getLogger(ProjectPackerService.class);
    private final ProjectService projectService;
    private final ProjectCacheMetaService metaService;
    private final GrepService grepService;

    public ProjectPackerService(ProjectService projectService, ProjectCacheMetaService metaService, GrepService grepService) {
        this.projectService = projectService;
        this.metaService = metaService;
        this.grepService = grepService;
    }

    @AgentDoc("Generates a packed representation of the codebase.")
    public EditorResult packCodebase() {
        return packCodebase(null);
    }

    /**
     * Packs the codebase with progress reporting.
     */
    public EditorResult packCodebase(ProgressCallback callback) {
        if (!projectService.hasActiveProject()) {
            return new EditorResult(false, "", "No active project");
        }

        Path root = projectService.getCurrentProjectRoot();
        if (callback != null) callback.onProgress(0, 100, "Listing files...");
        List<String> files = grepService.listFiles(null);
        
        int totalFiles = files.size();
        StringBuilder packed = new StringBuilder();
        packed.append("# Codebase Snapshot\n\n");
        packed.append("## Project Tree\n\n```\n");
        packed.append(FileTreeFormatter.format(files));
        packed.append("\n```\n\n");
        
        for (int i = 0; i < totalFiles; i++) {
            String file = files.get(i);
            if (callback != null) {
                callback.onProgress(i + 1, totalFiles, "Packing " + file + " (" + (i + 1) + "/" + totalFiles + ")");
            }
            Path filePath = root.resolve(file);
            if (Files.isRegularFile(filePath)) {
                try {
                    String content = Files.readString(filePath);
                    packed.append("--- ").append(file).append(" ---\n");
                    packed.append(content).append("\n\n");
                } catch (IOException e) {
                    log.warn("Failed to read file {}: {}", file, e.getMessage());
                }
            }
        }
        
        if (callback != null) callback.onProgress(totalFiles, totalFiles, "Finalizing...");
        String packedString = packed.toString();
        metaService.saveRepoCache(packedString);
        
        return new EditorResult(true, packedString, null);
    }
}