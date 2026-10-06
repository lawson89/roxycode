package org.roxycode.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class PromptService {
    private static final Logger log = LoggerFactory.getLogger(PromptService.class);
    private static final String PROMPT_DIR = ".roxycode/prompts";
    private static final String PROMPT_FILE = "core_workflow.md";
    private static final String CLASSPATH_PROMPT = "prompts/core_workflow.md";
    private static final String JEXL_DOC_PATH = "docs/jexl.md";

    public String loadCoreWorkflowPrompt() {
        Path overridePath = getOverridePath();
        if (Files.exists(overridePath)) {
            try {
                return Files.readString(overridePath, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.error("Failed to read override prompt from {}: {}", overridePath, e.getMessage());
            }
        }
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

    public void saveCoreWorkflowPrompt(String content) {
        try {
            Path overridePath = getOverridePath();
            if (overridePath.getParent() != null) {
                Files.createDirectories(overridePath.getParent());
            }
            Files.writeString(overridePath, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to save prompt: {}", e.getMessage());
            throw new RuntimeException("Failed to save prompt", e);
        }
    }

    public void resetCoreWorkflowPrompt() {
        try {
            Path overridePath = getOverridePath();
            Files.deleteIfExists(overridePath);
        } catch (IOException e) {
            log.error("Failed to reset prompt: {}", e.getMessage());
            throw new RuntimeException("Failed to reset prompt", e);
        }
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

    protected Path getOverridePath() {
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, PROMPT_DIR, PROMPT_FILE);
    }
}
