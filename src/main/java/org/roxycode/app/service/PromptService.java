package org.roxycode.app.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringSubstitutor;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.FileReadService;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.MemoryService.MemoryMetadata;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.ProjectService.ReferenceFolderMount;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the construction of system and session prompts for the AI agent.
 */
@Service
public class PromptService {
    private static final Logger log = LoggerFactory.getLogger(PromptService.class);
    private static final String CLASSPATH_PROMPT = "prompts/core_workflow.md";
    private static final String JEXL_DOC_PATH = "docs/jexl.md";
    private static final String USER_GUIDE_DOC_PATH = "docs/roxycode_user_guide.md";
    private static final String EXPLORE_PROMPT_PATH = "prompts/explore_phase.md";
    private static final String REVIEW_PROMPT_PATH = "prompts/review_phase.md";
    private static final String PLAN_PROMPT_PATH = "prompts/plan_phase.md";
    private static final String CODE_PROMPT_PATH = "prompts/code_phase.md";
    private static final int MAX_RECENT_ARCHIVED_PLANS = 5;

    private static final String NEWLINE_DOUBLE = "\n\n";
    private static final String HEADER_JEXL_CONTEXT = "\n\n## JEXL CONTEXT\n";
    private static final String HEADER_USER_GUIDE_CONTEXT = "\n\n## ROXYCODE USER GUIDE\n";
    private static final String HEADER_REFERENCE_FOLDERS = "\n\n## READ-ONLY REFERENCE FOLDERS\n";
    private static final String HEADER_GLOBAL_AGENT_MEMORY = "\n\n## GLOBAL AGENT MEMORY\n";
    private static final String HEADER_PROJECT_AGENT_MEMORY = "\n\n## PROJECT AGENT MEMORY & ARCHIVE\n";
    private static final String HEADER_REPO_MAP = "\n\n## REPOSITORY MAP\n";
    private static final String HEADER_SESSION_CONTEXT = "\n\n## SESSION CONTEXT\n";
    private static final String HEADER_GIT_STATUS = "## GIT STATUS\n";
    private static final String HEADER_CURRENT_PLAN_PROGRESS = "## CURRENT PLAN PROGRESS\n";

    public record ContextFileItem(String folderPath, String fileName, Path filePath) {
        @Override
        public String toString() {
            return fileName;
        }
    }

    private final ProjectService projectService;
    private final SettingsService settingsService;
    private final PlanService planService;
    private final JexlServiceRegistry jexlServiceRegistry;
    private final GitService gitService;
    private final ProjectAnalysisService projectAnalysisService;
    private final RepoMapPackerService repoMapPackerService;
    private final MemoryService memoryService;

    public PromptService(ProjectService projectService, SettingsService settingsService, PlanService planService,
            JexlServiceRegistry jexlServiceRegistry, GitService gitService,
            ProjectAnalysisService projectAnalysisService, RepoMapPackerService repoMapPackerService,
            MemoryService memoryService) {
        this.projectService = projectService;
        this.settingsService = settingsService;
        this.planService = planService;
        this.jexlServiceRegistry = jexlServiceRegistry;
        this.gitService = gitService;
        this.projectAnalysisService = projectAnalysisService;
        this.repoMapPackerService = repoMapPackerService;
        this.memoryService = memoryService;
    }

    /**
     * Generates the stable system context including user guide, JEXL documentation, reference folders, and agent
     * memory.
     * 
     * @return the stable context string
     */
    public String getStableContext() {
        return readClasspathResource(CLASSPATH_PROMPT) + HEADER_JEXL_CONTEXT + readClasspathResource(JEXL_DOC_PATH)
                + HEADER_USER_GUIDE_CONTEXT + readClasspathResource(USER_GUIDE_DOC_PATH) + NEWLINE_DOUBLE
                + loadGlobalContext() + loadProjectContext() + loadReferenceFoldersContext() + loadAgentMemoryContext();
    }

    String loadReferenceFoldersContext() {
        List<ReferenceFolderMount> mounts = projectService.getReferenceFolderMounts();
        if (mounts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(HEADER_REFERENCE_FOLDERS);
        sb.append("The following external source folders are mounted read-only. Pass ").append('`').append("@<alias>")
                .append('`').append(" or ").append('`').append("@<alias>/subpath").append('`').append(" to ")
                .append('`').append("grepService").append('`').append(", ").append('`').append("fileReadService")
                .append('`').append(", and ").append('`').append("symbolService").append('`')
                .append(" to list files, search, or read code on demand:\n");
        for (ReferenceFolderMount mount : mounts) {
            sb.append("- ").append('`').append(mount.aliasPrefix()).append('`').append(" (").append('`')
                    .append(mount.rootPath()).append('`').append(")\n");
        }
        return sb.toString();
    }

    public String loadAgentMemoryContext() {
        if (memoryService == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendGlobalAgentMemorySection(sb);
        appendProjectAgentMemorySection(sb);
        return sb.toString();
    }

    private void appendGlobalAgentMemorySection(StringBuilder sb) {
        List<MemoryMetadata> globalMemories = Objects.requireNonNullElse(memoryService.list(MemoryService.SCOPE_GLOBAL),
                List.of());
        if (globalMemories.isEmpty()) {
            return;
        }
        sb.append(HEADER_GLOBAL_AGENT_MEMORY);
        boolean hasDetails = appendMemoryCatalog(sb, "Global Memories", globalMemories, false);
        if (hasDetails) {
            sb.append(
                    "Call memoryService.read('global', id) or memoryService.readAll('global') when entries marked (+details) are relevant.\n");
        }
    }

    private void appendProjectAgentMemorySection(StringBuilder sb) {
        List<MemoryMetadata> projectMemories = Objects
                .requireNonNullElse(memoryService.list(MemoryService.SCOPE_PROJECT), List.of());
        List<String> archivedPlans = Objects.requireNonNullElse(memoryService.listArchivedPlans(), List.of());
        List<String> archivedTopics = Objects.requireNonNullElse(memoryService.listArchivedTopics(), List.of());
        boolean hasArchives = !archivedPlans.isEmpty() || !archivedTopics.isEmpty();
        if (projectMemories.isEmpty() && !hasArchives) {
            return;
        }
        sb.append(HEADER_PROJECT_AGENT_MEMORY);
        boolean hasPreceding = !projectMemories.isEmpty();
        boolean hasDetails = appendMemoryCatalog(sb, "Project Memories", projectMemories, false);
        hasPreceding = appendCatalog(sb, "Recent Archived Plans", archivedPlans, MAX_RECENT_ARCHIVED_PLANS,
                hasPreceding);
        appendCatalog(sb, "Recent Archived Topics", archivedTopics, MAX_RECENT_ARCHIVED_PLANS, hasPreceding);
        if (hasDetails) {
            sb.append(
                    "Call memoryService.read('project', id) or memoryService.readAll('project') when entries marked (+details) are relevant.\n");
        }
        if (hasArchives) {
            sb.append(
                    "Use memoryService.readArchivedPlan(filename), memoryService.readArchivedTopic(filename), or memoryService.search(pattern) to inspect archives.\n");
        }
    }

    private static boolean appendMemoryCatalog(StringBuilder sb, String title, List<MemoryMetadata> entries,
            boolean hasPreceding) {
        if (entries.isEmpty()) {
            return hasPreceding;
        }
        if (hasPreceding) {
            sb.append("\n");
        }
        sb.append("### ").append(title).append("\n");
        boolean anyHasDetails = false;
        for (MemoryMetadata entry : entries) {
            sb.append(formatMemoryCatalogLine(entry)).append("\n");
            if (entry.hasDetails()) {
                anyHasDetails = true;
            }
        }
        return anyHasDetails;
    }

    static String formatMemoryCatalogLine(MemoryMetadata entry) {
        StringBuilder line = new StringBuilder("- `").append(entry.id()).append("`");
        if (entry.tags() != null && !entry.tags().isEmpty()) {
            line.append(" [").append(String.join(", ", entry.tags())).append("]");
        }
        if (entry.hasDetails()) {
            line.append(" (+details)");
        }
        line.append(": ").append(entry.summary());
        return line.toString();
    }

    private static boolean appendCatalog(StringBuilder sb, String title, List<String> items, int limit,
            boolean hasPreceding) {
        if (items.isEmpty()) {
            return hasPreceding;
        }
        if (hasPreceding) {
            sb.append("\n");
        }
        sb.append("### ").append(title).append("\n");
        int count = items.size();
        if (limit > 0) {
            count = Math.min(limit, items.size());
        }
        for (int i = 0; i < count; i++) {
            sb.append("- ").append(items.get(i)).append("\n");
        }
        return true;
    }

    /**
     * Generates the session context for the current workflow phase.
     * 
     * @return the session context string
     */
    public String getSessionContext() {
        return getSessionContext(planService.getCurrentPhase());
    }

    String getSessionContext(WorkflowPhase phase) {
        WorkflowPhase currentPhase = Objects.requireNonNullElseGet(phase, planService::getCurrentPhase);
        return getStaticPhaseContext(currentPhase) + getVolatileSessionContext();
    }

    String getStaticPhaseContext(WorkflowPhase phase) {
        WorkflowPhase currentPhase = Objects.requireNonNullElseGet(phase, planService::getCurrentPhase);
        StringBuilder sb = new StringBuilder(HEADER_SESSION_CONTEXT);
        sb.append("DOMINANT LANGUAGE: ").append(projectAnalysisService.getDominantLanguage())
                .append("\nCURRENT PHASE: ").append(currentPhase.name()).append("\nYou are the ")
                .append(currentPhase.getPersonaTitle()).append(". ").append(currentPhase.getDescription())
                .append(NEWLINE_DOUBLE).append("You have access to the following JEXL tools:\n")
                .append(jexlServiceRegistry.getDocumentation(currentPhase));
        return sb.toString();
    }

    String getVolatileSessionContext() {
        StringBuilder sb = new StringBuilder();
        if (gitService != null && (jexlServiceRegistry == null || jexlServiceRegistry.isGitAvailable())) {
            String branch = gitService.getCurrentBranch();
            String gitStatus = gitService.getStatus();
            boolean validBranch = GitService.isValidOutput(branch);
            boolean validStatus = GitService.isValidOutput(gitStatus);
            if (validBranch || validStatus) {
                sb.append(HEADER_GIT_STATUS);
                if (validBranch) {
                    sb.append("Branch: ").append(branch.trim());
                    if (validStatus) {
                        sb.append(StringUtils.LF).append(gitStatus);
                    }
                } else {
                    sb.append(gitStatus);
                }
                sb.append(NEWLINE_DOUBLE);
            }
        }
        ImplementationPlan currentPlan = planService.getCurrentPlan();
        if (currentPlan != null) {
            sb.append(HEADER_CURRENT_PLAN_PROGRESS).append(currentPlan.formatPromptContextMarkdown())
                    .append(StringUtils.LF);
        }
        return sb.toString();
    }

    public String buildSystemPrompt(WorkflowPhase phase) {
        WorkflowPhase currentPhase = Objects.requireNonNullElseGet(phase, planService::getCurrentPhase);
        StringBuilder systemPrompt = new StringBuilder(getStableContext());

        EditorResult repoMap = repoMapPackerService.generateRepoMap();
        if (repoMap != null && repoMap.success()) {
            systemPrompt.append(HEADER_REPO_MAP).append(repoMap.content()).append('\n');
        }
        systemPrompt.append(NEWLINE_DOUBLE).append(loadPhasePrompt(currentPhase))
                .append(getStaticPhaseContext(currentPhase)).append(getVolatileSessionContext());
        return systemPrompt.toString();
    }

    String loadPhasePrompt(WorkflowPhase phase) {
        String path = switch (phase) {
            case EXPLORE -> EXPLORE_PROMPT_PATH;
            case PLAN -> PLAN_PROMPT_PATH;
            case CODE -> CODE_PROMPT_PATH;
            case REVIEW -> REVIEW_PROMPT_PATH;
        };
        String raw = readClasspathResource(path);
        int maxTurns = AppSettings.DEFAULT_MAX_AGENT_TOOL_TURNS;
        if (settingsService != null && settingsService.getSettings() != null) {
            maxTurns = settingsService.getSettings().getMaxAgentToolTurns();
        }
        return StringSubstitutor.replace(raw, Map.of("maxTurns", maxTurns));
    }

    public List<ContextFileItem> listGlobalContextFiles() {
        return listContextFilesInFolders(projectService.getGlobalContextFolders(), false);
    }

    public List<ContextFileItem> listProjectContextFiles() {
        if (projectService == null || !projectService.hasActiveProject()) {
            return List.of();
        }
        return listContextFilesInFolders(projectService.getProjectContextFolders(), true);
    }

    List<ContextFileItem> listContextFilesInFolders(List<String> folders, boolean resolveRelativeToProject) {
        if (folders == null || folders.isEmpty()) {
            return List.of();
        }
        Map<Path, ContextFileItem> discovered = new LinkedHashMap<>();
        for (String rawFolder : folders) {
            if (rawFolder == null || rawFolder.isBlank()) {
                continue;
            }
            String folderStr = rawFolder.trim();
            Path dir = ProjectService.resolveMountFolderPath(folderStr, resolveRelativeToProject,
                    projectService.getCurrentProjectRoot());
            if (dir == null || !Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile).filter(Files::isReadable)
                        .filter(p -> !p.getFileName().toString().startsWith(".")).filter(PromptService::isNonBinaryFile)
                        .sorted(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                        .forEach(entry -> {
                            Path norm = entry.toAbsolutePath().normalize();
                            discovered.putIfAbsent(norm,
                                    new ContextFileItem(folderStr, entry.getFileName().toString(), norm));
                        });
            } catch (IOException e) {
                log.warn("Failed to list context files in folder {}: {}", dir, e.getMessage());
            }
        }
        List<ContextFileItem> result = new ArrayList<>(discovered.values());
        result.sort(Comparator.comparing(ContextFileItem::fileName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(item -> item.filePath().toString()));
        return List.copyOf(result);
    }

    private String readContextFile(Path filePath) {
        if (filePath == null) {
            throw new IllegalArgumentException("Context file path must not be null");
        }
        Path normalized = filePath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException("Context file does not exist: " + filePath);
        }
        if (normalized.getFileName().toString().startsWith(".")) {
            throw new IllegalArgumentException("Hidden files are not allowed as context: " + filePath);
        }
        try {
            if (FileReadService.isBinaryFile(normalized)) {
                throw new IllegalArgumentException("Binary files are not allowed as context: " + filePath);
            }
            return Files.readString(normalized, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to read context file {}: {}", filePath, e.getMessage());
            throw new UncheckedIOException("Failed to read context file", e);
        }
    }

    public String loadGlobalContext() {
        return formatContextSection(listGlobalContextFiles(), "Global Context", "global");
    }

    public String loadProjectContext() {
        return formatContextSection(listProjectContextFiles(), "Project Context", "project");
    }

    private String formatContextSection(List<ContextFileItem> files, String headerTitle, String logScope) {
        if (files.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContextFileItem item : files) {
            try {
                String content = readContextFile(item.filePath());
                if (!content.isBlank()) {
                    sb.append(NEWLINE_DOUBLE).append("## ").append(headerTitle).append(": ").append(item.fileName())
                            .append("\n").append(content);
                }
            } catch (Exception e) {
                log.warn("Failed to read {} context file {}: {}", logScope, item.filePath(), e.getMessage());
            }
        }
        return sb.toString();
    }

    private static boolean isNonBinaryFile(Path path) {
        try {
            return !FileReadService.isBinaryFile(path);
        } catch (IOException e) {
            return false;
        }
    }

    private String readClasspathResource(String path) {
        try {
            return PathUtils.readClasspathResourceUtf8(path, PromptService.class);
        } catch (IOException e) {
            log.error("Failed to load resource {} from classpath: {}", path, e.getMessage());
            return "";
        }
    }
}
