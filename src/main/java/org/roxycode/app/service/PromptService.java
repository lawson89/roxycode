package org.roxycode.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

@Service
public class PromptService {
    private static final Logger log = LoggerFactory.getLogger(PromptService.class);
    private static final String CLASSPATH_PROMPT = "prompts/core_workflow.md";
    private static final String JEXL_DOC_PATH = "docs/jexl.md";
    private static final String EXPLORE_PROMPT_PATH = "prompts/explore_phase.md";
    private static final String VERIFICATION_PROMPT_PATH = "prompts/verification_phase.md";
    private static final String CONTEXT_DIR = ".roxycode/context";

    private final ProjectService projectService;

    public PromptService() {
        this.projectService = null;
    }

    @Autowired
    public PromptService(ProjectService projectService) {
        this.projectService = projectService;
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

    public String loadVerificationPrompt() {
        try {
            Resource resource = new ClassPathResource(VERIFICATION_PROMPT_PATH);
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load VERIFICATION prompt from classpath: {}", e.getMessage());
            return "";
        }
    }

    public String loadAllPrompts() {
        return loadMarkdownFromClasspath("prompts", "core_workflow.md", "explore_phase.md", "verification_phase.md");
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
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.getFileName().toString().endsWith(".md"))
                  .sorted((p1, p2) -> p1.getFileName().toString().compareToIgnoreCase(p2.getFileName().toString()))
                  .forEach(p -> {
                      try {
                          String content = Files.readString(p, StandardCharsets.UTF_8);
                          sb.append("\n\n## Project Context: ").append(p.getFileName().toString()).append("\n");
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