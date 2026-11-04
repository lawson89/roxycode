package org.roxycode.app.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
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
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.FileReadService;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.events.ContextChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.ProjectService.ReferenceFolderMount;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

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
    static final String PROJECT_CONTEXT_CONFIG_FILE = "context.toml";
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProjectContextConfig(List<String> folders, List<String> referenceFolders) {
        public static final ProjectContextConfig EMPTY = new ProjectContextConfig(List.of(), List.of());

        public ProjectContextConfig(List<String> folders) {
            this(folders, List.of());
        }

        public ProjectContextConfig {
            folders = List.copyOf(Objects.requireNonNullElse(folders, List.of()));
            referenceFolders = List.copyOf(Objects.requireNonNullElse(referenceFolders, List.of()));
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
    private final ApplicationEventPublisher eventPublisher;

    public PromptService(ProjectService projectService, SettingsService settingsService, PlanService planService,
            JexlServiceRegistry jexlServiceRegistry, GitService gitService,
            ProjectAnalysisService projectAnalysisService, RepoMapPackerService repoMapPackerService,
            MemoryService memoryService, ApplicationEventPublisher eventPublisher) {
        this.projectService = projectService;
        this.settingsService = settingsService;
        this.planService = planService;
        this.jexlServiceRegistry = jexlServiceRegistry;
        this.gitService = gitService;
        this.projectAnalysisService = projectAnalysisService;
        this.repoMapPackerService = repoMapPackerService;
        this.memoryService = memoryService;
        this.eventPublisher = eventPublisher;
    }

    public String getStableContext() {
        return readClasspathResource(CLASSPATH_PROMPT) + HEADER_JEXL_CONTEXT + loadJexlContext()
                + HEADER_USER_GUIDE_CONTEXT + loadUserGuideContext() + NEWLINE_DOUBLE + loadGlobalContext()
                + loadProjectContext() + loadReferenceFoldersContext() + loadAgentMemoryContext();
    }

    public String loadReferenceFoldersContext() {
        List<ReferenceFolderMount> mounts = getReferenceFolderMounts();
        if (mounts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(HEADER_REFERENCE_FOLDERS);
        sb.append("The following external source folders are mounted read-only. Pass ").append((char) 96)
                .append("@<alias>").append((char) 96).append(" or ").append((char) 96).append("@<alias>/subpath")
                .append((char) 96).append(" to ").append((char) 96).append("grepService").append((char) 96).append(", ")
                .append((char) 96).append("fileReadService").append((char) 96).append(", and ").append((char) 96)
                .append("symbolService").append((char) 96).append(" to list files, search, or read code on demand:\n");
        for (ReferenceFolderMount mount : mounts) {
            sb.append("- ").append((char) 96).append(mount.aliasPrefix()).append((char) 96).append(" (")
                    .append((char) 96).append(mount.rootPath()).append((char) 96).append(")\n");
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
        String globalMemory = MemoryService.capPinnedMemoryForPrompt(memoryService.readGlobalMemory());
        List<String> globalNotes = Objects.requireNonNullElse(memoryService.listGlobalNotes(), List.of());
        if (globalMemory.isBlank() && globalNotes.isEmpty()) {
            return;
        }
        sb.append(HEADER_GLOBAL_AGENT_MEMORY);
        boolean hasContent = appendMemoryBlock(sb, globalMemory);
        appendCatalog(sb, "Available Global Notes", globalNotes, 0, hasContent);
        if (!globalNotes.isEmpty()) {
            sb.append("Use memoryService.readGlobalNote(slug) or memoryService.search(pattern) to inspect.\n");
        }
    }

    private void appendProjectAgentMemorySection(StringBuilder sb) {
        String projectMemory = MemoryService.capPinnedMemoryForPrompt(memoryService.readProjectMemory());
        List<String> projectNotes = Objects.requireNonNullElse(memoryService.listProjectNotes(), List.of());
        List<String> archivedPlans = Objects.requireNonNullElse(memoryService.listArchivedPlans(), List.of());
        List<String> archivedTopics = Objects.requireNonNullElse(memoryService.listArchivedTopics(), List.of());
        boolean hasCatalogs = !projectNotes.isEmpty() || !archivedPlans.isEmpty() || !archivedTopics.isEmpty();
        if (projectMemory.isBlank() && !hasCatalogs) {
            return;
        }
        sb.append(HEADER_PROJECT_AGENT_MEMORY);
        boolean hasContent = appendMemoryBlock(sb, projectMemory);
        hasContent = appendCatalog(sb, "Available Project Notes", projectNotes, 0, hasContent);
        hasContent = appendCatalog(sb, "Recent Archived Plans", archivedPlans, MAX_RECENT_ARCHIVED_PLANS, hasContent);
        appendCatalog(sb, "Recent Archived Topics", archivedTopics, MAX_RECENT_ARCHIVED_PLANS, hasContent);
        if (hasCatalogs) {
            sb.append(
                    "Use memoryService.readProjectNote(slug), memoryService.readArchivedPlan(filename), memoryService.readArchivedTopic(filename), or memoryService.search(pattern) to inspect.\n");
        }
    }

    private static boolean appendMemoryBlock(StringBuilder sb, String memory) {
        if (memory.isBlank()) {
            return false;
        }
        sb.append(memory.stripTrailing()).append("\n");
        return true;
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

    public String getSessionContext() {
        return getSessionContext(planService.getCurrentPhase());
    }

    public String getSessionContext(WorkflowPhase phase) {
        WorkflowPhase currentPhase = Objects.requireNonNullElseGet(phase, planService::getCurrentPhase);
        return getStaticPhaseContext(currentPhase) + getVolatileSessionContext();
    }

    public String getStaticPhaseContext(WorkflowPhase phase) {
        WorkflowPhase currentPhase = Objects.requireNonNullElseGet(phase, planService::getCurrentPhase);
        StringBuilder sb = new StringBuilder(HEADER_SESSION_CONTEXT);
        sb.append("DOMINANT LANGUAGE: ").append(projectAnalysisService.getDominantLanguage())
                .append("\nCURRENT PHASE: ").append(currentPhase.name()).append("\nYou are the ")
                .append(currentPhase.getPersonaTitle()).append(". ").append(currentPhase.getDescription())
                .append(NEWLINE_DOUBLE).append("You have access to the following JEXL tools:\n")
                .append(jexlServiceRegistry.getDocumentation(currentPhase));
        return sb.toString();
    }

    public String getVolatileSessionContext() {
        StringBuilder sb = new StringBuilder();
        if (gitService != null && (jexlServiceRegistry == null || jexlServiceRegistry.isGitAvailable())) {
            String gitStatus = gitService.getStatus();
            if (StringUtils.isNotBlank(gitStatus) && !gitStatus.startsWith("Error")
                    && !gitStatus.startsWith("Not a git repository")) {
                sb.append(HEADER_GIT_STATUS).append(gitStatus).append(NEWLINE_DOUBLE);
            }
        }
        ImplementationPlan currentPlan = planService.getCurrentPlan();
        if (currentPlan != null) {
            sb.append(HEADER_CURRENT_PLAN_PROGRESS).append(currentPlan.formatPromptContextMarkdown()).append("\n");
        }
        return sb.toString();
    }

    public String buildSystemPrompt(WorkflowPhase phase) {
        WorkflowPhase currentPhase = Objects.requireNonNullElseGet(phase, planService::getCurrentPhase);
        StringBuilder systemPrompt = new StringBuilder(getStableContext());

        EditorResult repoMap = repoMapPackerService.generateRepoMap();
        if (repoMap != null && repoMap.success()) {
            systemPrompt.append(HEADER_REPO_MAP).append(repoMap.content()).append((char) 10);
        }
        systemPrompt.append(NEWLINE_DOUBLE).append(loadPhasePrompt(currentPhase))
                .append(getStaticPhaseContext(currentPhase)).append(getVolatileSessionContext());
        return systemPrompt.toString();
    }

    public String loadPhasePrompt(WorkflowPhase phase) {
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

    public String loadJexlContext() {
        return readClasspathResource(JEXL_DOC_PATH);
    }

    public String loadUserGuideContext() {
        return readClasspathResource(USER_GUIDE_DOC_PATH);
    }

    public List<String> getGlobalContextFolders() {
        if (settingsService == null || settingsService.getSettings() == null) {
            return List.of();
        }
        return normalizeFolderList(settingsService.getSettings().getGlobalContextFolders());
    }

    public void setGlobalContextFolders(List<String> folders) {
        if (settingsService == null) {
            return;
        }
        AppSettings settings = settingsService.getSettings();
        if (settings != null) {
            settings.setGlobalContextFolders(normalizeFolderList(folders));
            settingsService.saveSettings();
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new ContextChangedEvent(true));
            }
        }
    }

    private ProjectContextConfig readProjectContextConfig() {
        if (!hasActiveProject()) {
            return ProjectContextConfig.EMPTY;
        }
        return PathUtils.readTomlOrDefault(projectService.resolveProjectDataPath(PROJECT_CONTEXT_CONFIG_FILE),
                ProjectContextConfig.class, ProjectContextConfig.EMPTY, AiConfig.SHARED_TOML_MAPPER);
    }

    private void writeProjectContextConfig(ProjectContextConfig config) {
        if (!hasActiveProject()) {
            throw new IllegalStateException("No active project");
        }
        Path configFile = projectService.resolveProjectDataPath(PROJECT_CONTEXT_CONFIG_FILE);
        try {
            PathUtils.writeToml(configFile, config, AiConfig.SHARED_TOML_MAPPER);
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new ContextChangedEvent(false));
            }
        } catch (IOException e) {
            log.error("Failed to save project context config {}: {}", configFile, e.getMessage());
            throw new UncheckedIOException("Failed to save project context config", e);
        }
    }

    public List<String> getProjectContextFolders() {
        return normalizeFolderList(readProjectContextConfig().folders());
    }

    public void setProjectContextFolders(List<String> folders) {
        ProjectContextConfig existing = readProjectContextConfig();
        writeProjectContextConfig(new ProjectContextConfig(normalizeFolderList(folders), existing.referenceFolders()));
    }

    public List<String> getGlobalReferenceFolders() {
        if (settingsService == null || settingsService.getSettings() == null) {
            return List.of();
        }
        return normalizeFolderList(settingsService.getSettings().getGlobalReferenceFolders());
    }

    public void setGlobalReferenceFolders(List<String> folders) {
        if (settingsService == null) {
            return;
        }
        AppSettings settings = settingsService.getSettings();
        if (settings != null) {
            settings.setGlobalReferenceFolders(normalizeFolderList(folders));
            settingsService.saveSettings();
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new ContextChangedEvent(true));
            }
        }
    }

    public List<String> getProjectReferenceFolders() {
        return normalizeFolderList(readProjectContextConfig().referenceFolders());
    }

    public void setProjectReferenceFolders(List<String> folders) {
        ProjectContextConfig existing = readProjectContextConfig();
        writeProjectContextConfig(new ProjectContextConfig(existing.folders(), normalizeFolderList(folders)));
    }

    public List<ReferenceFolderMount> getReferenceFolderMounts() {
        Path projectRoot = null;
        if (hasActiveProject()) {
            projectRoot = projectService.getCurrentProjectRoot();
        }
        return ProjectService.resolveReferenceFolderMounts(getProjectReferenceFolders(), getGlobalReferenceFolders(),
                projectRoot);
    }

    public List<ReferenceFolderMount> listGlobalReferenceFolderMounts() {
        Path projectRoot = null;
        if (hasActiveProject()) {
            projectRoot = projectService.getCurrentProjectRoot();
        }
        List<ReferenceFolderMount> allMounts = ProjectService.resolveReferenceFolderMountsInternal(
                getProjectReferenceFolders(), getGlobalReferenceFolders(), projectRoot, false);
        return allMounts.stream().filter(ReferenceFolderMount::globalScope).toList();
    }

    public List<ReferenceFolderMount> listProjectReferenceFolderMounts() {
        if (!hasActiveProject()) {
            return List.of();
        }
        return ProjectService.resolveReferenceFolderMounts(getProjectReferenceFolders(), List.of(),
                projectService.getCurrentProjectRoot());
    }

    public List<ContextFileItem> listGlobalContextFiles() {
        return listContextFilesInFolders(getGlobalContextFolders(), false);
    }

    public List<ContextFileItem> listProjectContextFiles() {
        if (!hasActiveProject()) {
            return List.of();
        }
        return listContextFilesInFolders(getProjectContextFolders(), true);
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
            Path dir = resolveFolderPath(folderStr, resolveRelativeToProject);
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

    private Path resolveFolderPath(String folderStr, boolean resolveRelativeToProject) {
        try {
            Path path = Path.of(folderStr);
            if (!path.isAbsolute() && resolveRelativeToProject && hasActiveProject()) {
                Path projectRoot = projectService.getCurrentProjectRoot();
                if (projectRoot != null) {
                    path = projectRoot.resolve(path);
                }
            }
            return path.toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            log.warn("Invalid context folder path '{}': {}", folderStr, e.getMessage());
            return null;
        }
    }

    private boolean hasActiveProject() {
        return projectService != null && projectService.hasActiveProject();
    }

    private static boolean isNonBinaryFile(Path path) {
        try {
            return !FileReadService.isBinaryFile(path);
        } catch (IOException e) {
            return false;
        }
    }

    static List<String> normalizeFolderList(List<String> folders) {
        if (folders == null) {
            return List.of();
        }
        return folders.stream().filter(f -> f != null && !f.isBlank()).map(String::trim).distinct().toList();
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
