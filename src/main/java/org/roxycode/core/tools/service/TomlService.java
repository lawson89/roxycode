package org.roxycode.core.tools.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.roxycode.core.Sandbox;
import org.roxycode.core.tools.LLMDoc;
import org.roxycode.core.tools.ScriptService;

import java.nio.file.Path;
import java.util.*;

/**
 * Service for reading, writing, and analyzing TOML files.
 */
@ScriptService("tomlService")
@Singleton
public class TomlService {

    private final Sandbox sandbox;

    @Inject
    public TomlService(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    /**
     * Structural summary of a TOML file.
     */
    public static class TomlFileSummary {
        private final List<ElementSummary> elements;

        public TomlFileSummary(List<ElementSummary> elements) {
            this.elements = elements;
        }

        public List<ElementSummary> getElements() {
            return elements;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TomlFileSummary that = (TomlFileSummary) o;
            return Objects.equals(elements, that.elements);
        }

        @Override
        public int hashCode() {
            return Objects.hash(elements);
        }
    }

    /**
     * Summary of an element within a TOML file.
     */
    public static class ElementSummary {
        private final String name;
        private final String xpath;
        private final int depth;

        public ElementSummary(String name, String xpath, int depth) {
            this.name = name;
            this.xpath = xpath;
            this.depth = depth;
        }

        public String getName() {
            return name;
        }

        public String getXpath() {
            return xpath;
        }

        public int getDepth() {
            return depth;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ElementSummary that = (ElementSummary) o;
            return depth == that.depth &&
                   Objects.equals(name, that.name) &&
                   Objects.equals(xpath, that.xpath);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, xpath, depth);
        }
    }

    /**
     * Analyzes a TOML file and returns a structural summary of its elements.
     *
     * @param pathStr The path to the TOML file.
     * @return A TomlFileSummary of the file's contents.
     * @throws Exception If an error occurs during reading or parsing.
     */
    @LLMDoc("Analyzes a TOML file and returns a structural summary of its elements")
    public TomlFileSummary analyzeFile(String pathStr) throws Exception {
        Path path = sandbox.resolve(pathStr);
        ObjectMapper mapper = new TomlMapper();
        JsonNode root = mapper.readTree(path.toFile());
        List<ElementSummary> elements = new ArrayList<>();
        summarizeRecursive(root, "", 0, elements);
        return new TomlFileSummary(elements);
    }

    private void summarizeRecursive(JsonNode node, String parentPath, int depth, List<ElementSummary> elements) {
        if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                String name = field.getKey();
                JsonNode child = field.getValue();
                String currentPath = parentPath + "/" + name;
                elements.add(new ElementSummary(name, currentPath, depth));
                summarizeRecursive(child, currentPath, depth + 1, elements);
            }
        } else if (node.isArray()) {
            int index = 1;
            for (JsonNode child : node) {
                String currentPath = parentPath + "[" + index + "]";
                summarizeRecursive(child, currentPath, depth + 1, elements);
                index++;
            }
        }
    }

    /**
     * Reads a TOML file and returns its content as a JsonNode.
     *
     * @param pathStr The path to the TOML file.
     * @return The parsed contents as a JsonNode.
     * @throws Exception If an error occurs during reading or parsing.
     */
    @LLMDoc("Reads a TOML file and returns its content as a JsonNode")
    public JsonNode read(String pathStr) throws Exception {
        Path path = sandbox.resolve(pathStr);
        ObjectMapper mapper = new TomlMapper();
        return mapper.readTree(path.toFile());
    }

    /**
     * Writes a JsonNode to a file in TOML format.
     *
     * @param pathStr    The path to the output TOML file.
     * @param content The JsonNode content to write.
     * @throws Exception If an error occurs during writing.
     */
    @LLMDoc("Writes a JsonNode to a file in TOML format")
    public void write(String pathStr, JsonNode content) throws Exception {
        Path path = sandbox.resolve(pathStr);
        ObjectMapper mapper = new TomlMapper();
        mapper.writeValue(path.toFile(), content);
    }
}
