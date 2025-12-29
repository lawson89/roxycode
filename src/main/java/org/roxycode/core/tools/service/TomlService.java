package org.roxycode.core.tools.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.*;

@Singleton
public class TomlService {

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


    public TomlFileSummary analyzeFile(Path path) throws Exception {
        ObjectMapper mapper = new TomlMapper();
        JsonNode root = mapper.readTree(path.toFile());
        List<ElementSummary> elements = new ArrayList<>();
        summarizeRecursive(root, "", 0, elements);
        return new TomlFileSummary(elements);
    }

    private void summarizeRecursive(JsonNode node, String parentPath, int depth, List<ElementSummary> elements) {
        if (node.isObject()) {
            int index = 1;
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                String name = field.getKey();
                JsonNode child = field.getValue();
                String currentPath = parentPath + "/" + name;
                elements.add(new ElementSummary(name, currentPath, depth));
                summarizeRecursive(child, currentPath, depth + 1, elements);
                index++;
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

    public JsonNode read(Path path) throws Exception {
        ObjectMapper mapper = new TomlMapper();
        return mapper.readTree(path.toFile());
    }

    public void write(Path path, JsonNode content) throws Exception {
        ObjectMapper mapper = new TomlMapper();
        mapper.writeValue(path.toFile(), content);
    }
}
