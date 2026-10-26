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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.commons.text.StringSubstitutor;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.FileReadService;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
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

    private static final String NEWLINE_DOUBLE = "\n\n";
    private static final String HEADER_JEXL_CONTEXT = "\n\n## JEXL CONTEXT\n";
    private static final String HEADER_REPO_MAP = "\n\n## REPOSITORY MAP\n";
    private static final String HEADER_SESSION_CONTEXT = "\n\n## SESSION CONTEXT\n";
    private static final String HEADER_GIT_STATUS = "## GIT STATUS\n";
    private static final String HEADER_CURRENT_PLAN_PROGRESS = "## CURRENT PLAN PROGRESS\n";
    private static final String HEADER_ADDITIONAL_INSTRUCTIONS = "## ADDITIONAL INSTRUCTIONS\n";

    /** Represents a discovered top-level text file within a configured context folder. */
    public record ContextFileItem(String folderPath, String fileName, Path filePath) {
        @Override
        public String toString() {
            return fileName;
        }
    }

    /** TOML configuration model for per-project context folders. */
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

    public PromptService(
            ProjectService projectService,
            SettingsService settingsService,
            PlanService planService,
            JexlServiceRegistry jexlServiceRegistry,
            GitService gitService,
            ProjectAnalysisService projectAnalysisService,
            RepoMapPackerService repoMapPackerService) {
        this.projectService = projectService;
        this.settingsService = settingsService;
        this.planService = planService;
        this.jexlServiceRegistry = jexlServiceRegistry;
        this.gitService = gitService;
        this.projectAnalysisService = projectAnalysisService;
        this.repoMapPackerService = repoMapPackerService;
    }

    public String getStableContext() {
        StringBuilder sb = new StringBuilder(readClasspathResource(CLASSPATH_PROMPT));
        sb.append(HEADER_JEXL_CONTEXT).append(loadJexlContext()).append(NEWLINE_DOUBLE);
        sb.append(loadGlobalContext());
        sb.append(loadProjectContext());
        return sb.toString();
    }

    public String getSessionContext() {
        return getSessionContext(planService.getCurrentPhase());
    }

    public String getSessionContext(WorkflowPhase phase) {
        WorkflowPhase currentPhase = phase != null ? phase : planService.getCurrentPhase();

        StringBuilder sb = new StringBuilder(HEADER_SESSION_CONTEXT);
        sb.append("DOMINANT LANGUAGE: ")
                .append(projectAnalysisService.getDominantLanguage())
                .append("\n");
        sb.append("CURRENT PHASE: ").append(currentPhase.name()).append("\n");

        String persona =
                String.format(
                        "You are the %s. %s",
                        currentPhase.getPersonaTitle(), currentPhase.getDescription());
        sb.append(persona).append(NEWLINE_DOUBLE);
        sb.append("You have access to the following JEXL tools:\n")
                .append(jexlServiceRegistry.getDocumentation(currentPhase));

        String gitStatus = gitService.getStatus();
        if (gitStatus != null && !gitStatus.isEmpty() && !gitStatus.startsWith("Error")) {
            sb.append(HEADER_GIT_STATUS).append(gitStatus).append(NEWLINE_DOUBLE);
        }

        ImplementationPlan currentPlan = planService.getCurrentPlan();
        if (currentPlan != null) {
            sb.append(HEADER_CURRENT_PLAN_PROGRESS)
                    .append(currentPlan.formatStepsMarkdown())
                    .append("\n");
        }
        return sb.toString();
    }

    public String buildSystemPrompt(WorkflowPhase phase, String additionalInstructions) {
        WorkflowPhase currentPhase = phase != null ? phase : planService.getCurrentPhase();

        StringBuilder systemPrompt = new StringBuilder(getStableContext());
        systemPrompt.append(NEWLINE_DOUBLE).append(loadPhasePrompt(currentPhase));

        EditorResult repoMap = repoMapPackerService.generateRepoMap();
        if (repoMap != null && repoMap.success()) {
            systemPrompt.append(HEADER_REPO_MAP).append(repoMap.content()).append("\n");
        }

        systemPrompt.append(getSessionContext(currentPhase));

        if (additionalInstructions != null) {
            systemPrompt
                    .append(HEADER_ADDITIONAL_INSTRUCTIONS)
                    .append(additionalInstructions)
                    .append(NEWLINE_DOUBLE);
        }

        return systemPrompt.toString();
    }

    public String loadPhasePrompt(WorkflowPhase phase) {
        String path =
                switch (phase) {
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
        if (settingsService == null) {
            return List.of();
        }
        AppSettings settings = settingsService.getSettings();
        if (settings == null) {
            return List.of();
        }
        return normalizeFolderList(settings.getGlobalContextFolders());
    }

    public void setGlobalContextFolders(List<String> folders) {
        if (settingsService == null) {
            return;
        }
        AppSettings settings = settingsService.getSettings();
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
            ProjectContextConfig config =
                    AiConfig.SHARED_TOML_MAPPER.readValue(
                            configFile.toFile(), ProjectContextConfig.class);
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
        List<String> normalized = normalizeFolderList(folders);
        Path configFile = projectService.resolveProjectDataPath(PROJECT_CONTEXT_CONFIG_FILE);
        try {
            PathUtils.createParentDirectories(configFile);
            AiConfig.SHARED_TOML_MAPPER.writeValue(
                    configFile.toFile(), new ProjectContextConfig(normalized));
        } catch (IOException e) {
            log.error("Failed to save project context config {}: {}", configFile, e.getMessage());
            throw new UncheckedIOException("Failed to save project context config", e);
        }
    }

    public List<ContextFileItem> listGlobalContextFiles() {
        return listContextFilesInFolders(getGlobalContextFolders(), false);
    }

    public List<ContextFileItem> listProjectContextFiles() {
        if (projectService == null || !projectService.hasActiveProject()) {
            return List.of();
        }
        return listContextFilesInFolders(getProjectContextFolders(), true);
    }

    List<ContextFileItem> listContextFilesInFolders(
            List<String> folders, boolean resolveRelativeToProject) {
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
                List<Path> entries =
                        stream.filter(Files::isRegularFile)
                                .filter(Files::isReadable)
                                .filter(p -> !p.getFileName().toString().startsWith("."))
                                .filter(PromptService::isNonBinaryFile)
                                .sorted(
                                        Comparator.comparing(
                                                p -> p.getFileName().toString(),
                                                String.CASE_INSENSITIVE_ORDER))
                                .toList();
                for (Path entry : entries) {
                    Path normalizedPath = entry.toAbsolutePath().normalize();
                    discovered.putIfAbsent(
                            normalizedPath,
                            new ContextFileItem(
                                    folderStr, entry.getFileName().toString(), normalizedPath));
                }
            } catch (IOException e) {
                log.warn("Failed to list context files in folder {}: {}", dir, e.getMessage());
            }
        }
        List<ContextFileItem> result = new ArrayList<>(discovered.values());
        result.sort(
                Comparator.comparing(ContextFileItem::fileName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(item -> item.filePath().toString()));
        return List.copyOf(result);
    }

    public String readContextFile(Path filePath) {
        if (filePath == null) {
            throw new IllegalArgumentException("Context file path must not be null");
        }
        Path normalized = filePath.toAbsolutePath().normalize();
        if (!Files.exists(normalized) || !Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException("Context file does not exist: " + filePath);
        }
        if (normalized.getFileName().toString().startsWith(".")) {
            throw new IllegalArgumentException(
                    "Hidden files are not allowed as context: " + filePath);
        }
        if (!isInsideConfiguredContextFolder(normalized)) {
            throw new IllegalArgumentException(
                    "File is not in a configured context folder: " + filePath);
        }
        try {
            if (FileReadService.isBinaryFile(normalized)) {
                throw new IllegalArgumentException(
                        "Binary files are not allowed as context: " + filePath);
            }
            return Files.readString(normalized, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to read context file {}: {}", filePath, e.getMessage());
            throw new UncheckedIOException("Failed to read context file", e);
        }
    }

    public String loadGlobalContext() {
        List<ContextFileItem> files = listGlobalContextFiles();
        if (files.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContextFileItem item : files) {
            try {
                String content = Files.readString(item.filePath(), StandardCharsets.UTF_8);
                sb.append("\n\n## Global Context: ")
                        .append(item.fileName())
                        .append("\n")
                        .append(content);
            } catch (Exception e) {
                log.error(
                        "Failed to read global context file {}: {}",
                        item.filePath(),
                        e.getMessage());
            }
        }
        return sb.toString();
    }

    public String loadProjectContext() {
        List<ContextFileItem> files = listProjectContextFiles();
        if (files.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContextFileItem item : files) {
            try {
                String content = Files.readString(item.filePath(), StandardCharsets.UTF_8);
                sb.append("\n\n## Project Context: ")
                        .append(item.fileName())
                        .append("\n")
                        .append(content);
            } catch (Exception e) {
                log.error(
                        "Failed to read project context file {}: {}",
                        item.filePath(),
                        e.getMessage());
            }
        }
        return sb.toString();
    }

    private boolean isInsideConfiguredContextFolder(Path normalizedFile) {
        Path parent = normalizedFile.getParent();
        if (parent == null) {
            return false;
        }
        for (String folder : getGlobalContextFolders()) {
            Path resolved = resolveFolderPath(folder, false);
            if (parent.equals(resolved)) {
                return true;
            }
        }
        if (projectService != null && projectService.hasActiveProject()) {
            for (String folder : getProjectContextFolders()) {
                Path resolved = resolveFolderPath(folder, true);
                if (parent.equals(resolved)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Path resolveFolderPath(String folderStr, boolean resolveRelativeToProject) {
        try {
            Path path = Path.of(folderStr);
            if (!path.isAbsolute()
                    && resolveRelativeToProject
                    && projectService != null
                    && projectService.hasActiveProject()
                    && projectService.getCurrentProjectRoot() != null) {
                return projectService
                        .getCurrentProjectRoot()
                        .resolve(path)
                        .toAbsolutePath()
                        .normalize();
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
        if (folders == null || folders.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String folder : folders) {
            if (folder != null && !folder.isBlank()) {
                unique.add(folder.trim());
            }
        }
        return List.copyOf(unique);
    }

    private String readClasspathResource(String path) {
        try {
            Resource resource = new ClassPathResource(path);
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load resource {} from classpath: {}", path, e.getMessage());
            return "";
        }
    }
}
