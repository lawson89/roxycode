package org.roxycode.app.util;

import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.vladsch.flexmark.ext.yaml.front.matter.YamlFrontMatterBlock;
import com.vladsch.flexmark.util.ast.Node;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ui.SwingHtmlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for parsing and formatting YAML frontmatter in Markdown files using Flexmark and Jackson YAML.
 */
public final class FrontmatterUtils {

    private static final Logger log = LoggerFactory.getLogger(FrontmatterUtils.class);
    private static final String YAML_DELIMITER = "---";

    public static final YAMLMapper SHARED_YAML_MAPPER = (YAMLMapper) new YAMLMapper()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER).findAndRegisterModules();

    private FrontmatterUtils() {
    }

    private static YamlFrontMatterBlock findClosedFrontmatterBlock(String content) {
        if (StringUtils.isEmpty(content)) {
            return null;
        }
        Node document = SwingHtmlUtils.getMarkdownParser().parse(content);
        Node firstChild = document.getFirstChild();
        if (firstChild instanceof YamlFrontMatterBlock block) {
            String blockText = content.substring(block.getStartOffset(), block.getEndOffset());
            if (blockText.trim().endsWith(YAML_DELIMITER)) {
                return block;
            }
        }
        return null;
    }

    /**
     * Removes YAML frontmatter from the given Markdown text.
     *
     * @param rawMarkdown
     *            the raw Markdown content
     * @return the Markdown content without frontmatter
     */
    public static String stripFrontmatter(String rawMarkdown) {
        if (rawMarkdown == null || rawMarkdown.isEmpty()) {
            return "";
        }
        YamlFrontMatterBlock block = findClosedFrontmatterBlock(rawMarkdown);
        if (block != null) {
            return StringUtils.stripStart(rawMarkdown.substring(block.getEndOffset()), null);
        }
        return rawMarkdown;
    }

    /**
     * Parses YAML frontmatter from a file.
     *
     * @param file
     *            the path to the Markdown file
     * @return a map of frontmatter fields
     */
    public static Map<String, String> parseFrontmatter(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return Collections.emptyMap();
        }
        try {
            return parseFrontmatter(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.debug("Failed to read frontmatter from {}: {}", file, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Parses YAML frontmatter from a string.
     *
     * @param content
     *            the Markdown content
     * @return a map of frontmatter fields
     */
    public static Map<String, String> parseFrontmatter(String content) {
        YamlFrontMatterBlock block = findClosedFrontmatterBlock(content);
        if (block == null) {
            return Collections.emptyMap();
        }
        String yamlBlock = content.substring(block.getStartOffset(), block.getEndOffset());
        String yamlContent = StringUtils
                .removeEnd(StringUtils.removeStart(yamlBlock.trim(), YAML_DELIMITER), YAML_DELIMITER).trim();
        if (yamlContent.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = SHARED_YAML_MAPPER.readValue(yamlContent, Map.class);
            if (obj == null) {
                return Collections.emptyMap();
            }
            Map<String, String> result = new LinkedHashMap<>();
            obj.forEach((k, v) -> result.put(Objects.toString(k), Objects.toString(v, "")));
            return result;
        } catch (Exception e) {
            log.debug("Failed to parse YAML frontmatter: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Formats the given fields as a YAML frontmatter block.
     *
     * @param fields
     *            the fields to include
     * @return the formatted YAML frontmatter string
     */
    public static String formatFrontmatter(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return YAML_DELIMITER + "\n" + YAML_DELIMITER + "\n\n";
        }
        try {
            String dumped = SHARED_YAML_MAPPER.writeValueAsString(fields).trim();
            return YAML_DELIMITER + "\n" + dumped + "\n" + YAML_DELIMITER + "\n\n";
        } catch (Exception e) {
            log.warn("Failed to format YAML frontmatter: {}", e.getMessage());
            return YAML_DELIMITER + "\n" + YAML_DELIMITER + "\n\n";
        }
    }
}
