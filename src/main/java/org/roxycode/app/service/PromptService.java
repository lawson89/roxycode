package org.roxycode.app.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
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
import org.roxycode.app.service.DatabaseConfigService.ResolvedDatabaseConnection;
import org.roxycode.app.service.ProjectService.ReferenceFolderMount;
import org.roxycode.app.util.FrontmatterUtils;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/** Orchestrates the construction of system and session prompts for the AI agent. */
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
    private static final String HEADER_CONFIGURED_DATABASES = "\n\n## CONFIGURED DATABASES\n";
    private static final String HEADER_GLOBAL_AGENT_MEMORY = "\n\n## GLOBAL AGENT MEMORY\n";
    private static final String HEADER_PROJECT_AGENT_MEMORY = "\n\n## PROJECT AGENT MEMORY & ARCHIVE\n";
    private static final String HEADER_REPO_MAP = "\n\n## REPOSITORY MAP\n";
    private static final String HEADER_SESSION_CONTEXT = "\n\n## SESSION CONTEXT\n";
    private static final String HEADER_GIT_STATUS = "## GIT STATUS\n";
    private static final String HEADER_CURRENT_PLAN_PROGRESS = "## CURRENT PLAN PROGRESS\n";

    public record ContextFileItem(String folderPath, String fileName, Path filePath, Map<String, String> frontmatter,
            String searchPath) {
        public ContextFileItem {
            frontmatter = Map.copyOf(Objects.requireNonNullElse(frontmatter, Map.of()));
            searchPath = StringUtils.trimToEmpty(searchPath);
        }

        public ContextFileItem(String folderPath, String fileName, Path filePath) {
            this(folderPath, fileName, filePath, Map.of(), fileName);
        }

        public boolean hasFrontmatter() {
            return !frontmatter.isEmpty();
        }

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
    private final @Nullable DatabaseConfigService databaseConfigService;

    public PromptService(ProjectService projectService, SettingsService settingsService, PlanService planService,
            JexlServiceRegistry jexlServiceRegistry, GitService gitService,
            ProjectAnalysisService projectAnalysisService, RepoMapPackerService repoMapPackerService,
            MemoryService memoryService) {
        this(projectService, settingsService, planService, jexlServiceRegistry, gitService, projectAnalysisService,
                repoMapPackerService, memoryService, null);
    }

    @Autowired
    public PromptService(ProjectService projectService, SettingsService settingsService, PlanService planService,
            JexlServiceRegistry jexlServiceRegistry, GitService gitService,
            ProjectAnalysisService projectAnalysisService, RepoMapPackerService repoMapPackerService,
            MemoryService memoryService, @Nullable DatabaseConfigService databaseConfigService) {
        this.projectService = projectService;
        this.settingsService = settingsService;
        this.planService = planService;
        this.jexlServiceRegistry = jexlServiceRegistry;
        this.gitService = gitService;
        this.projectAnalysisService = projectAnalysisService;
        this.repoMapPackerService = repoMapPackerService;
        this.memoryService = memoryService;
        this.databaseConfigService = databaseConfigService;
    }

    /** Generates the stable system context including user guide, JEXL docs, refs, databases, and memory. */
    public String getStableContext() {
        return readClasspathResource(CLASSPATH_PROMPT) + HEADER_JEXL_CONTEXT + readClasspathResource(JEXL_DOC_PATH)
                + HEADER_USER_GUIDE_CONTEXT + readClasspathResource(USER_GUIDE_DOC_PATH) + NEWLINE_DOUBLE
                + loadGlobalContext() + loadProjectContext() + loadReferenceFoldersContext()
                + loadConfiguredDatabasesContext() + loadAgentMemoryContext();
    }

    String loadConfiguredDatabasesContext() {
        if (databaseConfigService == null) {
            return "";
        }
        List<ResolvedDatabaseConnection> active = databaseConfigService.listActiveProjectConnections();
        if (active.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(HEADER_CONFIGURED_DATABASES).append(
                "The following read-only database connections are granted to this project. Use `dbService` in `executeJexl` to inspect schemas/tables (`listSchemas`, `listTables`, `describeTable`) and run read-only queries (`query`, `explain`):\n");
        for (ResolvedDatabaseConnection conn : active) {
            sb.append("- `").append(conn.config().id()).append("` (**").append(conn.config().displayName())
                    .append("**, type: ").append(conn.config().type());
            if (!conn.effectiveDefaultSchema().isBlank()) {
                sb.append(", default_schema: `").append(conn.effectiveDefaultSchema()).append('`');
            }
            sb.append(')');
            String description = conn.config().description();
            String notes = conn.notes();
            if (!description.isBlank() && !notes.isBlank()) {
                if (description.endsWith(".")) {
                    sb.append(" — ").append(description).append(" Project notes: ").append(notes);
                } else {
                    sb.append(" — ").append(description).append(" (Project notes: ").append(notes).append(')');
                }
            } else if (!description.isBlank()) {
                sb.append(" — ").append(description);
            } else if (!notes.isBlank()) {
                sb.append(" — Project notes: ").append(notes);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    String loadReferenceFoldersContext() {
        List<ReferenceFolderMount> mounts = projectService.getReferenceFolderMounts();
        if (mounts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(HEADER_REFERENCE_FOLDERS).append(
                "The following external source folders are mounted read-only. Pass `@<alias>` or `@<alias>/subpath` to `grepService`, `fileReadService`, and `symbolService` to list files, search, or read code on demand:\n");
        for (ReferenceFolderMount mount : mounts) {
            sb.append("- `").append(mount.aliasPrefix()).append("` (`").append(mount.rootPath()).append("`)\n");
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
        if (appendMemoryCatalog(sb, "Global Memories", globalMemories)) {
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
        boolean hasDetails = appendMemoryCatalog(sb, "Project Memories", projectMemories);
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

    private static boolean appendMemoryCatalog(StringBuilder sb, String title, List<MemoryMetadata> entries) {
        if (entries.isEmpty()) {
            return false;
        }
        sb.append("### ").append(title).append('\n');
        boolean anyHasDetails = false;
        for (MemoryMetadata entry : entries) {
            sb.append(MemoryService.formatCatalogLine(entry)).append('\n');
            anyHasDetails |= entry.hasDetails();
        }
        return anyHasDetails;
    }

    private static boolean appendCatalog(StringBuilder sb, String title, List<String> items, int limit,
            boolean hasPreceding) {
        if (items.isEmpty()) {
            return hasPreceding;
        }
        if (hasPreceding) {
            sb.append('\n');
        }
        sb.append("### ").append(title).append('\n');
        int count = items.size();
        if (limit > 0) {
            count = Math.min(limit, items.size());
        }
        for (int i = 0; i < count; i++) {
            sb.append("- ").append(items.get(i)).append('\n');
        }
        return true;
    }

    /** Generates the session context for the current workflow phase. */
    public String getSessionContext() {
        return getSessionContext(planService.getCurrentPhase());
    }

    String getSessionContext(WorkflowPhase phase) {
        WorkflowPhase currentPhase = Objects.requireNonNullElseGet(phase, planService::getCurrentPhase);
        return getStaticPhaseContext(currentPhase) + getVolatileSessionContext();
    }

    String getStaticPhaseContext(WorkflowPhase phase) {
        WorkflowPhase currentPhase = Objects.requireNonNullElseGet(phase, planService::getCurrentPhase);
        return HEADER_SESSION_CONTEXT + "DOMINANT LANGUAGE: " + projectAnalysisService.getDominantLanguage()
                + "\nCURRENT PHASE: " + currentPhase.name() + "\nYou are the " + currentPhase.getPersonaTitle() + ". "
                + currentPhase.getDescription() + NEWLINE_DOUBLE + "You have access to the following JEXL tools:\n"
                + jexlServiceRegistry.getDocumentation(currentPhase);
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
        return systemPrompt.append(NEWLINE_DOUBLE).append(loadPhasePrompt(currentPhase))
                .append(getStaticPhaseContext(currentPhase)).append(getVolatileSessionContext()).toString();
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
        if (projectService == null) {
            return List.of();
        }
        return listContextFilesInDir(projectService.getGlobalContextDir(),
                "@" + ProjectService.GLOBAL_CONTEXT_ALIAS + "/");
    }

    public List<ContextFileItem> listProjectContextFiles() {
        if (projectService == null || !projectService.hasActiveProject()) {
            return List.of();
        }
        return listContextFilesInDir(projectService.getProjectContextDir(),
                ProjectService.CONVENTION_CONTEXT_DIR + "/");
    }

    List<ContextFileItem> listContextFilesInDir(Path dir, String searchPathPrefix) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        Map<Path, ContextFileItem> discovered = new LinkedHashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            List<Path> folderFiles = new ArrayList<>();
            for (Path entry : stream) {
                if (!Files.isRegularFile(entry) || !Files.isReadable(entry)
                        || entry.getFileName().toString().startsWith(".")) {
                    continue;
                }
                try {
                    if (!FileReadService.isBinaryFile(entry)) {
                        folderFiles.add(entry);
                    }
                } catch (IOException e) {
                    log.debug("Skipping unreadable context file {}: {}", entry, e.getMessage());
                }
            }
            folderFiles.sort(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
            for (Path entry : folderFiles) {
                Path norm = entry.toAbsolutePath().normalize();
                String fileName = entry.getFileName().toString();
                discovered.putIfAbsent(norm, new ContextFileItem(dir.toString(), fileName, norm,
                        parseMarkdownFrontmatter(norm, fileName), searchPathPrefix + fileName));
            }
        } catch (IOException e) {
            log.warn("Failed to list context files in folder {}: {}", dir, e.getMessage());
        }
        return List.copyOf(discovered.values());
    }

    private Map<String, String> parseMarkdownFrontmatter(Path filePath, String fileName) {
        String ext = PathUtils.getLowerExtension(fileName, false);
        if (!"md".equals(ext) && !"markdown".equals(ext)) {
            return Map.of();
        }
        try {
            return FrontmatterUtils.parseFrontmatter(Files.readString(filePath, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.debug("Failed to read markdown frontmatter from {}: {}", filePath, e.getMessage());
            return Map.of();
        }
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
        List<ContextFileItem> fullFiles = new ArrayList<>();
        List<ContextFileItem> indexedFiles = new ArrayList<>();
        for (ContextFileItem item : files) {
            if (item.hasFrontmatter()) {
                indexedFiles.add(item);
            } else {
                fullFiles.add(item);
            }
        }
        StringBuilder sb = new StringBuilder(NEWLINE_DOUBLE).append("## ").append(headerTitle).append('\n');
        if (!fullFiles.isEmpty()) {
            sb.append("Full-Injected Files: ")
                    .append(fullFiles.stream().map(f -> "`" + f.searchPath() + "`").collect(Collectors.joining(", ")))
                    .append('\n');
        }
        if (!indexedFiles.isEmpty()) {
            sb.append(
                    "Frontmatter-Indexed Files (search or read body on demand via `grepService` / `fileReadService`):\n");
            for (ContextFileItem item : indexedFiles) {
                sb.append("- `").append(item.searchPath()).append('`');
                if (!item.frontmatter().isEmpty()) {
                    sb.append(": ").append(item.frontmatter().entrySet().stream()
                            .map(e -> e.getKey() + "=\"" + e.getValue() + '"').collect(Collectors.joining(", ")));
                }
                sb.append('\n');
            }
        }
        for (ContextFileItem item : fullFiles) {
            try {
                String content = readContextFile(item.filePath());
                if (!content.isBlank()) {
                    sb.append(NEWLINE_DOUBLE).append("## ").append(headerTitle).append(": ").append(item.fileName())
                            .append('\n').append(content);
                }
            } catch (Exception e) {
                log.warn("Failed to read {} context file {}: {}", logScope, item.filePath(), e.getMessage());
            }
        }
        return sb.toString();
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