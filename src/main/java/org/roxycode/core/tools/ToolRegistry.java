package org.roxycode.core.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlFactory;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

@Singleton
public class ToolRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(ToolRegistry.class);
    private final Map<String, ToolDefinition> tools = new HashMap<>();
    private final ObjectMapper tomlMapper;

    public ToolRegistry() {
        this.tomlMapper = new ObjectMapper(new TomlFactory());
    }

    /**
     * Scans the specified directory for .toml files and loads them.
     * Use "tools" as the default argument.
     */
    public void loadTools(String directoryPath) {
        Path root = Paths.get(directoryPath);
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            LOG.warn("Tool directory not found: {}", root.toAbsolutePath());
            return;
        }

        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".toml"))
                    .forEach(this::loadTool);
        } catch (IOException e) {
            LOG.error("Failed to scan tools directory", e);
        }
    }

    private void loadTool(Path path) {
        try {
            ToolDefinition tool = tomlMapper.readValue(path.toFile(), ToolDefinition.class);
            tool.setDefinitionLocation(path.getParent());

            // Use filename (minus extension) as the tool name
            String name = path.getFileName().toString().replace(".toml", "");
            tools.put(name, tool);
            LOG.info("Loaded tool: {}", name);
        } catch (IOException e) {
            LOG.error("Failed to parse tool: {}", path, e);
        }
    }

    public Optional<ToolDefinition> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }
}