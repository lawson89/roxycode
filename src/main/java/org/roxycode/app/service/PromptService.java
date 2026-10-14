package org.roxycode.app.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
  private static final String HEADER_ADDITIONAL_INSTRUCTIONS = "## ADDITIONAL INSTRUCTIONS\n";

  private final ProjectService projectService;
  private final SettingsService settingsService;
  private final WorkflowService workflowService;
  private final JexlServiceRegistry jexlServiceRegistry;
  private final GitService gitService;
  private final ProjectAnalysisService projectAnalysisService;
  private final RepoMapPackerService repoMapPackerService;

  public PromptService() {
    this(null, null, null, null, null, null, null);
  }

  public PromptService(ProjectService projectService, SettingsService settingsService) {
    this(projectService, settingsService, null, null, null, null, null);
  }

  @Autowired
  public PromptService(
      ProjectService projectService,
      SettingsService settingsService,
      WorkflowService workflowService,
      JexlServiceRegistry jexlServiceRegistry,
      GitService gitService,
      ProjectAnalysisService projectAnalysisService,
      RepoMapPackerService repoMapPackerService) {
    this.projectService = projectService;
    this.settingsService = settingsService;
    this.workflowService = workflowService;
    this.jexlServiceRegistry = jexlServiceRegistry;
    this.gitService = gitService;
    this.projectAnalysisService = projectAnalysisService;
    this.repoMapPackerService = repoMapPackerService;
  }

  public String getStableContext() {
    StringBuilder sb = new StringBuilder(loadCoreWorkflowPrompt());
    sb.append(loadAllPrompts());
    sb.append(HEADER_JEXL_CONTEXT).append(loadJexlContext()).append(NEWLINE_DOUBLE);
    sb.append(loadAllDocs());
    sb.append(loadProjectContext());
    return sb.toString();
  }

  public String getSessionContext() {
    WorkflowPhase phase =
        workflowService != null ? workflowService.getCurrentPhase() : WorkflowPhase.EXPLORE;
    return getSessionContext(phase);
  }

  public String getSessionContext(WorkflowPhase phase) {
    WorkflowPhase currentPhase =
        phase != null
            ? phase
            : (workflowService != null ? workflowService.getCurrentPhase() : WorkflowPhase.EXPLORE);

    StringBuilder sb = new StringBuilder(HEADER_SESSION_CONTEXT);
    if (projectAnalysisService != null) {
      sb.append("DOMINANT LANGUAGE: ")
          .append(projectAnalysisService.getDominantLanguage())
          .append("\n");
    }
    sb.append("CURRENT PHASE: ").append(currentPhase.name()).append("\n");

    String persona =
        String.format(
            "You are the %s. %s", currentPhase.getPersonaTitle(), currentPhase.getDescription());
    sb.append(persona).append(NEWLINE_DOUBLE);
    if (jexlServiceRegistry != null) {
      sb.append("You have access to the following JEXL tools:\n")
          .append(jexlServiceRegistry.getDocumentation(currentPhase));
    }

    if (gitService != null) {
      String gitStatus = gitService.getStatus();
      if (gitStatus != null && !gitStatus.isEmpty() && !gitStatus.startsWith("Error")) {
        sb.append(HEADER_GIT_STATUS).append(gitStatus).append(NEWLINE_DOUBLE);
      }
    }
    return sb.toString();
  }

  public String buildSystemPrompt(WorkflowPhase phase, String additionalInstructions) {
    WorkflowPhase currentPhase =
        phase != null
            ? phase
            : (workflowService != null ? workflowService.getCurrentPhase() : WorkflowPhase.EXPLORE);

    StringBuilder systemPrompt = new StringBuilder(getStableContext());

    if (currentPhase == WorkflowPhase.EXPLORE) {
      systemPrompt.append(NEWLINE_DOUBLE).append(generateExploreSystemPrompt());
    } else if (currentPhase == WorkflowPhase.REVIEW) {
      systemPrompt.append(NEWLINE_DOUBLE).append(loadReviewPrompt());
    } else if (currentPhase == WorkflowPhase.PLAN) {
      systemPrompt.append(NEWLINE_DOUBLE).append(loadPlanPrompt());
    } else if (currentPhase == WorkflowPhase.CODE) {
      systemPrompt.append(NEWLINE_DOUBLE).append(loadCodePrompt());
    }

    if (repoMapPackerService != null) {
      EditorResult repoMap = repoMapPackerService.generateRepoMap();
      if (repoMap != null && repoMap.success()) {
        systemPrompt.append(HEADER_REPO_MAP).append(repoMap.content()).append("\n");
      }
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

  public String loadCoreWorkflowPrompt() {
    return loadDefaultPrompt();
  }

  public String loadJexlContext() {
    try {
      Resource resource = new ClassPathResource(JEXL_DOC_PATH);
      return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      log.error("Failed to load JEXL context from classpath: {}", e.getMessage());
      return "";
    }
  }

  public String loadExplorePrompt() {
    try {
      Resource resource = new ClassPathResource(EXPLORE_PROMPT_PATH);
      return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      log.error("Failed to load EXPLORE prompt from classpath: {}", e.getMessage());
      return "";
    }
  }

  public String loadReviewPrompt() {
    try {
      Resource resource = new ClassPathResource(REVIEW_PROMPT_PATH);
      return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      log.error("Failed to load REVIEW prompt from classpath: {}", e.getMessage());
      return "";
    }
  }

  public String loadPlanPrompt() {
    try {
      Resource resource = new ClassPathResource(PLAN_PROMPT_PATH);
      return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      log.error("Failed to load PLAN prompt from classpath: {}", e.getMessage());
      return "";
    }
  }

  public String loadCodePrompt() {
    try {
      Resource resource = new ClassPathResource(CODE_PROMPT_PATH);
      return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      log.error("Failed to load CODE prompt from classpath: {}", e.getMessage());
      return "";
    }
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
    if (projectService == null || !projectService.hasActiveProject()) {
      return "";
    }
    Path contextDir = projectService.getCurrentProjectRoot().resolve(CONTEXT_DIR);
    if (!Files.exists(contextDir) || !Files.isDirectory(contextDir)) {
      return "";
    }

    StringBuilder sb = new StringBuilder();
    try (Stream<Path> stream = Files.list(contextDir)) {
      stream
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".md"))
          .sorted(
              (p1, p2) ->
                  p1.getFileName().toString().compareToIgnoreCase(p2.getFileName().toString()))
          .forEach(
              p -> {
                try {
                  String content = Files.readString(p, StandardCharsets.UTF_8);
                  sb.append("\n\n## Project Context: ")
                      .append(p.getFileName().toString())
                      .append("\n");
                  sb.append(content);
                } catch (IOException e) {
                  log.error("Failed to read project context file {}: {}", p, e.getMessage());
                }
              });
    } catch (IOException e) {
      log.error("Failed to list project context files in {}: {}", contextDir, e.getMessage());
    }

    return sb.toString();
  }

  public java.util.List<String> listContextFiles() {
    if (projectService == null || !projectService.hasActiveProject()) {
      return java.util.Collections.emptyList();
    }
    Path contextDir = projectService.getCurrentProjectRoot().resolve(CONTEXT_DIR);
    if (!Files.exists(contextDir) || !Files.isDirectory(contextDir)) {
      return java.util.Collections.emptyList();
    }
    try (Stream<Path> stream = Files.list(contextDir)) {
      return stream
          .filter(Files::isRegularFile)
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
    if (projectService == null || !projectService.hasActiveProject()) {
      return "";
    }
    Path file = projectService.getCurrentProjectRoot().resolve(CONTEXT_DIR).resolve(filename);
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
    if (projectService == null || !projectService.hasActiveProject()) {
      throw new IllegalStateException("No active project");
    }
    Path contextDir = projectService.getCurrentProjectRoot().resolve(CONTEXT_DIR);
    try {
      Files.createDirectories(contextDir);
      Files.writeString(contextDir.resolve(filename), content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      log.error("Failed to save context file {}: {}", filename, e.getMessage());
      throw new RuntimeException("Failed to save context file", e);
    }
  }

  public void deleteContextFile(String filename) {
    if (projectService == null || !projectService.hasActiveProject()) {
      return;
    }
    Path file = projectService.getCurrentProjectRoot().resolve(CONTEXT_DIR).resolve(filename);
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      log.error("Failed to delete context file {}: {}", filename, e.getMessage());
      throw new RuntimeException("Failed to delete context file", e);
    }
  }

  public String generateExploreSystemPrompt() {
    String prompt = loadExplorePrompt();
    if (settingsService != null) {
      return prompt.replace(
          "${maxTurns}", String.valueOf(settingsService.getSettings().getMaxAgentToolTurns()));
    }
    return prompt;
  }

  private String loadMarkdownFromClasspath(String directory, String... excludeFiles) {
    java.util.List<String> excludes = java.util.Arrays.asList(excludeFiles);
    StringBuilder sb = new StringBuilder();
    try {
      PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
      Resource[] resources = resolver.getResources("classpath*:" + directory + "/*.md");
      for (Resource resource : resources) {
        if (resource.getFilename() != null && !excludes.contains(resource.getFilename())) {
          sb.append("\n\n## ").append(resource.getFilename()).append("\n");
          sb.append(new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        }
      }
    } catch (IOException e) {
      log.error("Failed to discover markdown files in {}: {}", directory, e.getMessage());
    }
    return sb.toString();
  }

  private String loadDefaultPrompt() {
    try {
      Resource resource = new ClassPathResource(CLASSPATH_PROMPT);
      return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      log.error("Failed to load default prompt from classpath: {}", e.getMessage());
      return "";
    }
  }
}
