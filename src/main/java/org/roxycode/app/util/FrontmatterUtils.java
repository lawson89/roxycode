package org.roxycode.app.util;

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
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Utility for parsing and formatting YAML frontmatter in Markdown files using Flexmark and SnakeYAML.
 */
public final class FrontmatterUtils {

    private static final Logger log = LoggerFactory.getLogger(FrontmatterUtils.class);

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
            if (blockText.trim().endsWith("---")) {
                return block;
            }
        }
        return null;
    }

    /**
     * Strips the YAML frontmatter block from the beginning of a Markdown string.
     *
     * @param rawMarkdown
     *            The raw Markdown content
     * @return The Markdown content without the frontmatter block
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
     * Parses the YAML frontmatter from a file.
     *
     * @param file
     *            The path to the Markdown file
     * @return A map of frontmatter keys to their string representation
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
     * Parses the YAML frontmatter from a string.
     *
     * @param content
     *            The Markdown content
     * @return A map of frontmatter keys to their string representation
     */
    public static Map<String, String> parseFrontmatter(String content) {
        YamlFrontMatterBlock block = findClosedFrontmatterBlock(content);
        if (block == null) {
            return Collections.emptyMap();
        }
        String yamlBlock = content.substring(block.getStartOffset(), block.getEndOffset());
        String yamlContent = StringUtils.removeEnd(StringUtils.removeStart(yamlBlock.trim(), "---"), "---").trim();
        if (yamlContent.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> obj = yaml.load(yamlContent);
            if (obj == null) {
                return Collections.emptyMap();
            }
            Map<String, String> result = new LinkedHashMap<>();
            obj.forEach((k, v) -> result.put(k, Objects.toString(v, "")));
            return result;
        } catch (Exception e) {
            log.debug("Failed to parse YAML frontmatter: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Formats a map into a YAML frontmatter block.
     *
     * @param fields
     *            The fields to include in the frontmatter
     * @return The formatted frontmatter block (including delimiters and trailing newlines)
     */
    public static String formatFrontmatter(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return "---\n---\n\n";
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml yaml = new Yaml(options);
        String dumped = yaml.dump(fields);
        return "---\n" + dumped + "---\n\n";
    }
}
