package org.roxycode.app.service;

import java.io.IOException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
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
    private static final String CONTEXT_DIR = ".roxycode/context";

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
        sb.append(loadAllPrompts());
        sb.append(HEADER_JEXL_CONTEXT).append(loadJexlContext()).append(NEWLINE_DOUBLE);
        sb.append(loadAllDocs());
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
            sb.append(HEADER_CURRENT_PLAN_PROGRESS);
            var steps = currentPlan.technicalSteps();
            for (int i = 0; i < steps.size(); i++) {
                ImplementationPlan.TechStep step = steps.get(i);
                sb.append(step.completed() ? "- [x] " : "- [ ] ")
                        .append(i)
                        .append(". ")
                        .append(step.description())
                        .append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public String buildSystemPrompt(WorkflowPhase phase, String additionalInstructions) {
        WorkflowPhase currentPhase = phase != null ? phase : planService.getCurrentPhase();

        StringBuilder systemPrompt = new StringBuilder(getStableContext());

        String phasePrompt =
                currentPhase == WorkflowPhase.EXPLORE
                        ? generateExploreSystemPrompt()
                        : loadPhasePrompt(currentPhase);
        systemPrompt.append(NEWLINE_DOUBLE).append(phasePrompt);

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
        return readClasspathResource(path);
    }

    public String loadJexlContext() {
        return readClasspathResource(JEXL_DOC_PATH);
    }

    public String loadAllPrompts() {
        return loadMarkdownFromClasspath(
                "prompts",
                "core_workflow.md",
                "explore_phase.md",
                "plan_phase.md",
                "code_phase.md",
                "review_phase.md");
    }

    public String loadAllDocs() {
        return loadMarkdownFromClasspath("docs", "jexl.md");
    }

    public String loadProjectContext() {
        if (!projectService.hasActiveProject()) {
            return "";
        }
        Path contextDir = projectService.getCurrentProjectRoot().resolve(CONTEXT_DIR);
        if (!Files.exists(contextDir) || !Files.isDirectory(contextDir)) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        try (Stream<Path> stream = Files.list(contextDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted(
                            (p1, p2) ->
                                    p1.getFileName()
                                            .toString()
                                            .compareToIgnoreCase(p2.getFileName().toString()))
                    .forEach(
                            p -> {
                                try {
                                    String content = Files.readString(p, StandardCharsets.UTF_8);
                                    sb.append("\n\n## Project Context: ")
                                            .append(p.getFileName().toString())
                                            .append("\n");
                                    sb.append(content);
                                } catch (IOException e) {
                                    log.error(
                                            "Failed to read project context file {}: {}",
                                            p,
                                            e.getMessage());
                                }
                            });
        } catch (IOException e) {
            log.error("Failed to list project context files in {}: {}", contextDir, e.getMessage());
        }

        return sb.toString();
    }

    public java.util.List<String> listContextFiles() {
        if (!projectService.hasActiveProject()) {
            return java.util.Collections.emptyList();
        }
        Path contextDir = projectService.getCurrentProjectRoot().resolve(CONTEXT_DIR);
        if (!Files.exists(contextDir) || !Files.isDirectory(contextDir)) {
            return java.util.Collections.emptyList();
        }
        try (Stream<Path> stream = Files.list(contextDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.error("Failed to list context files: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    public String readContextFile(String filename) {
        if (!projectService.hasActiveProject()) {
            return "";
        }
        Path file = projectService.resolvePath(CONTEXT_DIR + "/" + filename);
        if (Files.exists(file)) {
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.error("Failed to read context file {}: {}", filename, e.getMessage());
            }
        }
        return "";
    }

    public void saveContextFile(String filename, String content) {
        if (!projectService.hasActiveProject()) {
            throw new IllegalStateException("No active project");
        }
        Path file = projectService.resolvePath(CONTEXT_DIR + "/" + filename);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to save context file {}: {}", filename, e.getMessage());
            throw new RuntimeException("Failed to save context file", e);
        }
    }

    public void deleteContextFile(String filename) {
        if (!projectService.hasActiveProject()) {
            return;
        }
        Path file = projectService.resolvePath(CONTEXT_DIR + "/" + filename);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.error("Failed to delete context file {}: {}", filename, e.getMessage());
            throw new RuntimeException("Failed to delete context file", e);
        }
    }

    public String generateExploreSystemPrompt() {
        String prompt = loadPhasePrompt(WorkflowPhase.EXPLORE);
        return StringSubstitutor.replace(
                prompt, Map.of("maxTurns", settingsService.getSettings().getMaxAgentToolTurns()));
    }

    private String loadMarkdownFromClasspath(String directory, String... excludeFiles) {
        java.util.List<String> excludes = java.util.Arrays.asList(excludeFiles);
        StringBuilder sb = new StringBuilder();
        try {
            PathMatchingResourcePatternResolver resolver =
                    new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:" + directory + "/*.md");
            for (Resource resource : resources) {
                if (resource.getFilename() != null && !excludes.contains(resource.getFilename())) {
                    sb.append("\n\n## ").append(resource.getFilename()).append("\n");
                    sb.append(resource.getContentAsString(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            log.error("Failed to discover markdown files in {}: {}", directory, e.getMessage());
        }
        return sb.toString();
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
