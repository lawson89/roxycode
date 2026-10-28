package org.roxycode.app.service;

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
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class PromptService {
    private static final Logger log = LoggerFactory.getLogger(PromptService.class);
    private static final String CLASSPATH_PROMPT = "prompts/core_workflow.md";
    private static final String JEXL_DOC_PATH = "docs/jexl.md";
    private static final String EXPLORE_PROMPT_PATH = "prompts/explore_phase.md";
    private static final String REVIEW_PROMPT_PATH = "prompts/review_phase.md";
    private static final String PLAN_PROMPT_PATH = "prompts/plan_phase.md";
    private static final String CODE_PROMPT_PATH = "prompts/code_phase.md";
    static final String PROJECT_CONTEXT_CONFIG_FILE = "context.toml";
    private static final int MAX_RECENT_ARCHIVED_PLANS = 5;

    private static final String NEWLINE_DOUBLE = "\n\n";
    private static final String HEADER_JEXL_CONTEXT = "\n\n## JEXL CONTEXT\n";
    private static final String HEADER_GLOBAL_AGENT_MEMORY = "\n\n## GLOBAL AGENT MEMORY\n";
    private static final String HEADER_PROJECT_AGENT_MEMORY = "\n\n## PROJECT AGENT MEMORY & ARCHIVE\n";
    private static final String HEADER_REPO_MAP = "\n\n## REPOSITORY MAP\n";
    private static final String HEADER_SESSION_CONTEXT = "\n\n## SESSION CONTEXT\n";
    private static final String HEADER_GIT_STATUS = "## GIT STATUS\n";
    private static final String HEADER_CURRENT_PLAN_PROGRESS = "## CURRENT PLAN PROGRESS\n";
    private static final String HEADER_ADDITIONAL_INSTRUCTIONS = "## ADDITIONAL INSTRUCTIONS\n";

    public record ContextFileItem(String folderPath, String fileName, Path filePath) {
        @Override
        public String toString() {
            return fileName;
        }
    }

    public record ProjectContextConfig(List<String> folders) {
        public ProjectContextConfig {
            folders = folders == null ? List.of() : List.copyOf(folders);
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

    public String getStableContext() {
        return readClasspathResource(CLASSPATH_PROMPT) + HEADER_JEXL_CONTEXT + loadJexlContext() + NEWLINE_DOUBLE
                + loadGlobalContext() + loadProjectContext() + loadAgentMemoryContext();
    }

    public String loadAgentMemoryContext() {
        if (memoryService == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String globalMemory = MemoryService.capPinnedMemoryForPrompt(memoryService.readGlobalMemory());
        List<String> globalNotes = Objects.requireNonNullElse(memoryService.listGlobalNotes(), List.of());

        if (!globalMemory.isBlank() || !globalNotes.isEmpty()) {
            sb.append(HEADER_GLOBAL_AGENT_MEMORY);
            boolean hasContent = appendMemoryBlock(sb, globalMemory);
            appendCatalog(sb, "Available Global Notes", globalNotes, 0, hasContent);
            if (!globalNotes.isEmpty()) {
                sb.append("Use memoryService.readGlobalNote(slug) or memoryService.search(pattern) to inspect.\n");
            }
        }

        String projectMemory = MemoryService.capPinnedMemoryForPrompt(memoryService.readProjectMemory());
        List<String> projectNotes = Objects.requireNonNullElse(memoryService.listProjectNotes(), List.of());
        List<String> archivedPlans = Objects.requireNonNullElse(memoryService.listArchivedPlans(), List.of());
        List<String> archivedTopics = Objects.requireNonNullElse(memoryService.listArchivedTopics(), List.of());
        boolean hasCatalogs = !projectNotes.isEmpty() || !archivedPlans.isEmpty() || !archivedTopics.isEmpty();

        if (!projectMemory.isBlank() || hasCatalogs) {
            sb.append(HEADER_PROJECT_AGENT_MEMORY);
            boolean hasContent = appendMemoryBlock(sb, projectMemory);
            hasContent = appendCatalog(sb, "Available Project Notes", projectNotes, 0, hasContent);
            hasContent = appendCatalog(sb, "Recent Archived Plans", archivedPlans, MAX_RECENT_ARCHIVED_PLANS,
                    hasContent);
            appendCatalog(sb, "Recent Archived Topics", archivedTopics, MAX_RECENT_ARCHIVED_PLANS, hasContent);
            if (hasCatalogs) {
                sb.append(
                        "Use memoryService.readProjectNote(slug), memoryService.readArchivedPlan(filename), memoryService.readArchivedTopic(filename), or memoryService.search(pattern) to inspect.\n");
            }
        }
        return sb.toString();
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
        int count = limit > 0 ? Math.min(limit, items.size()) : items.size();
        for (int i = 0; i < count; i++) {
            sb.append("- ").append(items.get(i)).append("\n");
        }
        return true;
    }

    public String getSessionContext() {
        return getSessionContext(planService.getCurrentPhase());
    }

    public String getSessionContext(WorkflowPhase phase) {
        WorkflowPhase currentPhase = phase != null ? phase : planService.getCurrentPhase();
        StringBuilder sb = new StringBuilder(HEADER_SESSION_CONTEXT);
        sb.append("DOMINANT LANGUAGE: ").append(projectAnalysisService.getDominantLanguage())
                .append("\nCURRENT PHASE: ").append(currentPhase.name()).append("\nYou are the ")
                .append(currentPhase.getPersonaTitle()).append(". ").append(currentPhase.getDescription())
                .append(NEWLINE_DOUBLE).append("You have access to the following JEXL tools:\n")
                .append(jexlServiceRegistry.getDocumentation(currentPhase));

        String gitStatus = gitService.getStatus();
        if (gitStatus != null && !gitStatus.isEmpty() && !gitStatus.startsWith("Error")) {
            sb.append(HEADER_GIT_STATUS).append(gitStatus).append(NEWLINE_DOUBLE);
        }
        ImplementationPlan currentPlan = planService.getCurrentPlan();
        if (currentPlan != null) {
            sb.append(HEADER_CURRENT_PLAN_PROGRESS).append(currentPlan.formatStepsMarkdown()).append("\n");
        }
        return sb.toString();
    }

    public String buildSystemPrompt(WorkflowPhase phase, String additionalInstructions) {
        WorkflowPhase currentPhase = phase != null ? phase : planService.getCurrentPhase();
        StringBuilder systemPrompt = new StringBuilder(getStableContext()).append(NEWLINE_DOUBLE)
                .append(loadPhasePrompt(currentPhase));

        EditorResult repoMap = repoMapPackerService.generateRepoMap();
        if (repoMap != null && repoMap.success()) {
            systemPrompt.append(HEADER_REPO_MAP).append(repoMap.content()).append("\n");
        }
        systemPrompt.append(getSessionContext(currentPhase));
        if (additionalInstructions != null) {
            systemPrompt.append(HEADER_ADDITIONAL_INSTRUCTIONS).append(additionalInstructions).append(NEWLINE_DOUBLE);
        }
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
        if (phase == WorkflowPhase.EXPLORE) {
            var settings = settingsService != null ? settingsService.getSettings() : null;
            int maxTurns = settings != null ? settings.getMaxAgentToolTurns() : 5;
            return StringSubstitutor.replace(raw, Map.of("maxTurns", maxTurns));
        }
        return raw;
    }

    public String loadJexlContext() {
        return readClasspathResource(JEXL_DOC_PATH);
    }

    public List<String> getGlobalContextFolders() {
        AppSettings settings = settingsService != null ? settingsService.getSettings() : null;
        return settings != null ? normalizeFolderList(settings.getGlobalContextFolders()) : List.of();
    }

    public void setGlobalContextFolders(List<String> folders) {
        AppSettings settings = settingsService != null ? settingsService.getSettings() : null;
        if (settings != null) {
            settings.setGlobalContextFolders(normalizeFolderList(folders));
            settingsService.saveSettings();
        }
    }

    public List<String> getProjectContextFolders() {
        if (projectService == null || !projectService.hasActiveProject()) {
            return List.of();
        }
        Path configFile = projectService.resolveProjectDataPath(PROJECT_CONTEXT_CONFIG_FILE);
        if (configFile == null || !Files.exists(configFile)) {
            return List.of();
        }
        try {
            ProjectContextConfig config = AiConfig.SHARED_TOML_MAPPER.readValue(configFile.toFile(),
                    ProjectContextConfig.class);
            return config != null ? normalizeFolderList(config.folders()) : List.of();
        } catch (IOException e) {
            log.warn("Failed to read project context config {}: {}", configFile, e.getMessage());
            return List.of();
        }
    }

    public void setProjectContextFolders(List<String> folders) {
        if (projectService == null || !projectService.hasActiveProject()) {
            throw new IllegalStateException("No active project");
        }
        Path configFile = projectService.resolveProjectDataPath(PROJECT_CONTEXT_CONFIG_FILE);
        try {
            PathUtils.createParentDirectories(configFile);
            AiConfig.SHARED_TOML_MAPPER.writeValue(configFile.toFile(),
                    new ProjectContextConfig(normalizeFolderList(folders)));
        } catch (IOException e) {
            log.error("Failed to save project context config {}: {}", configFile, e.getMessage());
            throw new UncheckedIOException("Failed to save project context config", e);
        }
    }

    public List<ContextFileItem> listGlobalContextFiles() {
        return listContextFilesInFolders(getGlobalContextFolders(), false);
    }

    public List<ContextFileItem> listProjectContextFiles() {
        return projectService != null && projectService.hasActiveProject()
                ? listContextFilesInFolders(getProjectContextFolders(), true)
                : List.of();
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

    public String readContextFile(Path filePath) {
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
        if (!isInsideConfiguredContextFolder(normalized)) {
            throw new IllegalArgumentException("File is not in a configured context folder: " + filePath);
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
                String content = readValidatedContextFile(item.filePath());
                if (content != null && !content.isBlank()) {
                    sb.append("\n\n## ").append(headerTitle).append(": ").append(item.fileName()).append("\n")
                            .append(content);
                }
            } catch (Exception e) {
                log.warn("Failed to read {} context file {}: {}", logScope, item.filePath(), e.getMessage());
            }
        }
        return sb.toString();
    }

    private static String readValidatedContextFile(Path normalizedFile) throws IOException {
        return Files.readString(normalizedFile, StandardCharsets.UTF_8);
    }

    private boolean isInsideConfiguredContextFolder(Path normalizedFile) {
        Path parent = normalizedFile.getParent();
        if (parent == null) {
            return false;
        }
        return matchesFolder(parent, getGlobalContextFolders(), false) || (projectService != null
                && projectService.hasActiveProject() && matchesFolder(parent, getProjectContextFolders(), true));
    }

    private boolean matchesFolder(Path parent, List<String> folders, boolean relativeToProject) {
        return folders.stream().anyMatch(f -> parent.equals(resolveFolderPath(f, relativeToProject)));
    }

    private Path resolveFolderPath(String folderStr, boolean resolveRelativeToProject) {
        try {
            Path path = Path.of(folderStr);
            if (!path.isAbsolute() && resolveRelativeToProject && projectService != null
                    && projectService.hasActiveProject() && projectService.getCurrentProjectRoot() != null) {
                return projectService.getCurrentProjectRoot().resolve(path).toAbsolutePath().normalize();
            }
            return path.toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            log.warn("Invalid context folder path '{}': {}", folderStr, e.getMessage());
            return null;
        }
    }

    private static boolean isNonBinaryFile(Path path) {
        try {
            return !FileReadService.isBinaryFile(path);
        } catch (IOException e) {
            return false;
        }
    }

    private static List<String> normalizeFolderList(List<String> folders) {
        return folders == null
                ? List.of()
                : folders.stream().filter(f -> f != null && !f.isBlank()).map(String::trim).distinct().toList();
    }

    private String readClasspathResource(String path) {
        try {
            return new ClassPathResource(path, PromptService.class.getClassLoader())
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load resource {} from classpath: {}", path, e.getMessage());
            return "";
        }
    }
}
