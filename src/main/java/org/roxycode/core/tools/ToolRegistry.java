package org.roxycode.core.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Tool;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Singleton
public class ToolRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(ToolRegistry.class);
    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();
    private final Map<String, Tool> geminiTools = new ConcurrentHashMap<>();
    private final ObjectMapper tomlMapper;

    public ToolRegistry(@Named("toml") ObjectMapper tomlMapper) {
        this.tomlMapper = tomlMapper;
    }

    @PostConstruct
    void init() {
        try {
            // Force the classloader to resolve this class now,
            // effectively "pre-warming" the cache to see if it fails on startup.
            Class.forName("com.google.genai.types.Schema");
        } catch (ClassNotFoundException e) {
            LOG.error("GenAI classes missing from classpath", e);
        }
    }

    /**
     * Scans the specified directory for .toml files and loads them.
     * Use "tools" as the default argument.
     */
    public void loadTools(Path roxyHomeTools) {
        LOG.info("Loading tools from directory: {}", roxyHomeTools.toAbsolutePath());
        if (!Files.exists(roxyHomeTools) || !Files.isDirectory(roxyHomeTools)) {
            LOG.warn("Tool directory not found: {}", roxyHomeTools.toAbsolutePath());
            return;
        }

        try (Stream<Path> paths = Files.walk(roxyHomeTools)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".toml"))
                    .forEach(this::loadTool);
        } catch (IOException e) {
            LOG.error("Failed to scan tools directory", e);
        }
        LOG.info("Loaded {} tools.", tools.size());
    }

    protected void loadTool(Path path) {
        try {
            ToolDefinition toolDefinition = tomlMapper.readValue(path.toFile(), ToolDefinition.class);
            toolDefinition.setDefinitionLocation(path.getParent());

            // Use filename (minus extension) as the tool name
            String toolName = path.getFileName().toString().replace(".toml", "");
            tools.put(toolName, toolDefinition);
            LOG.info("Loaded tool: {}", toolName);
            try {
                loadGeminiTool(toolName, toolDefinition);
            } catch (Exception e) {
                LOG.error("Failed to load gemini tool for: {}", toolName, e);
            }
        } catch (IOException e) {
            LOG.error("Failed to parse tool: {}", path, e);
        }
    }

    private void loadGeminiTool(String toolName, ToolDefinition toolDefinition) {
        LOG.info("Loaded gemini tool: {}", toolName);
        Map<String, Schema> properties = new HashMap<>();
        if (toolDefinition.getParameters() != null) {
            toolDefinition.getParameters().forEach(p -> properties.put(p.getName(), Schema.builder().type("STRING").description(p.getDescription()).build()));
        }
        Schema schema = Schema.builder().type("OBJECT").properties(properties).build();
        FunctionDeclaration functionDeclaration = FunctionDeclaration.builder().name(toolName).description(toolDefinition.getDescription()).parameters(schema).build();
        Tool tool = Tool.builder()
                .functionDeclarations(List.of(functionDeclaration))
                .build();
        geminiTools.put(toolName, tool);
        LOG.info("Loaded gemini tool: {}", toolName);
    }

    public Optional<ToolDefinition> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<String> getAllToolNames() {
        return new ArrayList<>(tools.keySet());
    }

    public List<ToolDefinition> getAllTools() {
        return new ArrayList<>(tools.values());
    }

    public List<Tool> getAllGeminiTools() {
        return new ArrayList<>(geminiTools.values());
    }
}