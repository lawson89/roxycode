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
        Node document = SwingHtmlUtils.getMarkdownParser().parse(rawMarkdown);
        Node firstChild = document.getFirstChild();
        if (firstChild instanceof YamlFrontMatterBlock) {
            String blockText = rawMarkdown.substring(firstChild.getStartOffset(), firstChild.getEndOffset());
            if (blockText.trim().endsWith("---")) {
                int endOffset = firstChild.getEndOffset();
                String body = rawMarkdown.substring(endOffset);
                return body.replaceFirst("^\\s+", "");
            }
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
        if (content == null || content.isEmpty()) {
            return Collections.emptyMap();
        }
        Node document = SwingHtmlUtils.getMarkdownParser().parse(content);
        Node firstChild = document.getFirstChild();
        if (firstChild instanceof YamlFrontMatterBlock) {
            String yamlBlock = content.substring(firstChild.getStartOffset(), firstChild.getEndOffset());
            if (!yamlBlock.trim().endsWith("---")) {
                return Collections.emptyMap();
            }
            // Strip the --- delimiters
            String yamlContent = yamlBlock.replaceAll("(?m)^---\\s*$", "").trim();
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
                obj.forEach((k, v) -> result.put(k, v != null ? v.toString() : ""));
                return result;
            } catch (Exception e) {
                log.debug("Failed to parse YAML frontmatter: {}", e.getMessage());
                return Collections.emptyMap();
            }
        }
        return Collections.emptyMap();
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
