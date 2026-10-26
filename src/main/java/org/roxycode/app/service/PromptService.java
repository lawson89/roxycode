package org.roxycode.app.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.commons.text.StringSubstitutor;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
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
    private static final String CONTEXT_DIR = "context";

    private static final String NEWLINE_DOUBLE = "\n\n";
    private static final String HEADER_JEXL_CONTEXT = "\n\n## JEXL CONTEXT\n";
    private static final String HEADER_REPO_MAP = "\n\n## REPOSITORY MAP\n";
    private static final String HEADER_SESSION_CONTEXT = "\n\n## SESSION CONTEXT\n";
    private static final String HEADER_GIT_STATUS = "## GIT STATUS\n";
    private static final String HEADER_CURRENT_PLAN_PROGRESS = "## CURRENT PLAN PROGRESS\n";
    private static final String HEADER_ADDITIONAL_INSTRUCTIONS = "## ADDITIONAL INSTRUCTIONS\n";

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

    public String loadProjectContext() {
        java.util.List<String> files = listContextFiles();
        if (files.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String filename : files) {
            try {
                String content = readContextFile(filename);
                sb.append("\n\n## Project Context: ").append(filename).append("\n").append(content);
            } catch (Exception e) {
                log.error("Failed to read project context file {}: {}", filename, e.getMessage());
            }
        }
        return sb.toString();
    }

    public java.util.List<String> listContextFiles() {
        if (!projectService.hasActiveProject()) {
            return java.util.Collections.emptyList();
        }
        Path contextDir = projectService.resolveProjectDataPath(CONTEXT_DIR);
        if (!Files.exists(contextDir) || !Files.isDirectory(contextDir)) {
            return java.util.Collections.emptyList();
        }
        try (Stream<Path> stream = Files.list(contextDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .map(p -> p.getFileName().toString())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException e) {
            log.error("Failed to list context files: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    static void validateContextFilename(String filename) {
        if (filename == null
                || filename.isBlank()
                || filename.contains("/")
                || filename.indexOf(92) >= 0
                || filename.contains("..")) {
            throw new IllegalArgumentException("Invalid context filename: " + filename);
        }
    }

    public String readContextFile(String filename) {
        validateContextFilename(filename);
        if (!projectService.hasActiveProject()) {
            return "";
        }
        Path file = projectService.resolveProjectDataPath(CONTEXT_DIR + "/" + filename);
        if (Files.exists(file)) {
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.error("Failed to read context file {}: {}", filename, e.getMessage());
                throw new UncheckedIOException("Failed to read context file", e);
            }
        }
        return "";
    }

    public void saveContextFile(String filename, String content) {
        validateContextFilename(filename);
        if (!projectService.hasActiveProject()) {
            throw new IllegalStateException("No active project");
        }
        Path file = projectService.resolveProjectDataPath(CONTEXT_DIR + "/" + filename);
        try {
            PathUtils.createParentDirectories(file);
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to save context file {}: {}", filename, e.getMessage());
            throw new UncheckedIOException("Failed to save context file", e);
        }
    }

    public void deleteContextFile(String filename) {
        validateContextFilename(filename);
        if (!projectService.hasActiveProject()) {
            return;
        }
        Path file = projectService.resolveProjectDataPath(CONTEXT_DIR + "/" + filename);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.error("Failed to delete context file {}: {}", filename, e.getMessage());
            throw new UncheckedIOException("Failed to delete context file", e);
        }
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
