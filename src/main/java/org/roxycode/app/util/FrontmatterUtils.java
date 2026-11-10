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
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.ui.SwingHtmlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for parsing and formatting YAML frontmatter in Markdown files using Flexmark and Jackson YAML.
 */
public final class FrontmatterUtils {

    private static final Logger log = LoggerFactory.getLogger(FrontmatterUtils.class);
    private static final String YAML_DELIMITER = "---";

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
            Map<String, Object> obj = AiConfig.SHARED_YAML_MAPPER.readValue(yamlContent, Map.class);
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

    public static String formatFrontmatter(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return YAML_DELIMITER + "\n" + YAML_DELIMITER + "\n\n";
        }
        try {
            String dumped = AiConfig.SHARED_YAML_MAPPER.writeValueAsString(fields);
            String clean = dumped.trim();
            while (clean.startsWith(YAML_DELIMITER)) {
                clean = clean.substring(3).trim();
            }
            while (clean.endsWith(YAML_DELIMITER)) {
                clean = clean.substring(0, clean.length() - 3).trim();
            }
            return YAML_DELIMITER + "\n" + clean + "\n" + YAML_DELIMITER + "\n\n";
        } catch (Exception e) {
            log.warn("Failed to format YAML frontmatter: {}", e.getMessage());
            return "---\n---\n\n";
        }
    }
}
