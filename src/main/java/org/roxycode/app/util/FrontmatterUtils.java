package org.roxycode.app.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.vladsch.flexmark.ext.yaml.front.matter.AbstractYamlFrontMatterVisitor;
import com.vladsch.flexmark.ext.yaml.front.matter.YamlFrontMatterExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FrontmatterUtils {

    private static final Logger log = LoggerFactory.getLogger(FrontmatterUtils.class);
    public static final String FRONTMATTER_DELIMITER = "---";
    private static final int MAX_FRONTMATTER_LINES = 50;
    private static final char DQUOTE = 34;
    private static final char SQUOTE = 39;
    private static final char BACKSLASH = 92;
    private static final char LF = 10;
    private static final char CR = 13;
    private static final String CRLF = String.valueOf(CR) + LF;
    private static final String DQUOTE_STR = String.valueOf(DQUOTE);
    private static final String ESCAPED_DQUOTE = String.valueOf(BACKSLASH) + DQUOTE;
    private static final Parser FRONTMATTER_PARSER = Parser
            .builder(new MutableDataSet().set(Parser.EXTENSIONS, List.of(YamlFrontMatterExtension.create()))).build();

    private FrontmatterUtils() {
    }

    public static String stripFrontmatter(String rawMarkdown) {
        if (rawMarkdown == null || rawMarkdown.isEmpty()) {
            return "";
        }
        List<String> lines = rawMarkdown.lines().toList();
        int endLine = findFrontmatterEndLine(lines);
        if (endLine <= 0) {
            return rawMarkdown;
        }
        int startIdx = endLine;
        while (startIdx < lines.size() && lines.get(startIdx).isBlank()) {
            startIdx++;
        }
        return startIdx >= lines.size() ? "" : String.join("\n", lines.subList(startIdx, lines.size()));
    }

    public static int findFrontmatterEndLine(List<String> lines) {
        if (lines == null || lines.isEmpty() || !FRONTMATTER_DELIMITER.equals(lines.get(0).trim())) {
            return 0;
        }
        for (int i = 1; i < lines.size(); i++) {
            if (FRONTMATTER_DELIMITER.equals(lines.get(i).trim())) {
                return i + 1;
            }
        }
        return 0;
    }

    public static int findFrontmatterEndLine(Path file) {
        return findFrontmatterEndLine(readFrontmatterLines(file));
    }

    public static List<String> readFrontmatterLines(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String firstLine = reader.readLine();
            if (firstLine == null || !FRONTMATTER_DELIMITER.equals(firstLine.trim())) {
                return List.of();
            }
            List<String> lines = new ArrayList<>();
            lines.add(firstLine.trim());
            String line = reader.readLine();
            while (lines.size() <= MAX_FRONTMATTER_LINES && line != null) {
                lines.add(line);
                if (FRONTMATTER_DELIMITER.equals(line.trim())) {
                    break;
                }
                line = reader.readLine();
            }
            return lines;
        } catch (IOException e) {
            log.debug("Failed to read frontmatter lines from {}: {}", file, e.getMessage());
            return List.of();
        }
    }

    public static Map<String, String> parseFrontmatter(Path file) {
        Map<String, String> map = new LinkedHashMap<>();
        List<String> lines = readFrontmatterLines(file);
        if (lines.isEmpty()) {
            return map;
        }
        String yamlBlock = String.join("\n", lines)
                + (findFrontmatterEndLine(lines) > 0 ? "\n" : "\n" + FRONTMATTER_DELIMITER + "\n");
        Node doc = FRONTMATTER_PARSER.parse(yamlBlock);
        AbstractYamlFrontMatterVisitor visitor = new AbstractYamlFrontMatterVisitor();
        visitor.visit(doc);
        for (Map.Entry<String, List<String>> entry : visitor.getData().entrySet()) {
            List<String> values = entry.getValue();
            String rawVal = (values == null || values.isEmpty()) ? "" : String.join(", ", values).trim();
            map.put(entry.getKey().trim(), normalizeFrontmatterValue(rawVal));
        }
        return map;
    }

    public static String normalizeFrontmatterValue(String val) {
        if (val == null || val.length() < 2) {
            return val != null ? val : "";
        }
        if (val.charAt(0) == DQUOTE && val.charAt(val.length() - 1) == DQUOTE) {
            return StringEscapeUtils.unescapeJava(val.substring(1, val.length() - 1));
        }
        if (val.charAt(0) == SQUOTE && val.charAt(val.length() - 1) == SQUOTE) {
            return val.substring(1, val.length() - 1);
        }
        return val;
    }

    public static String formatFrontmatter(Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append(FRONTMATTER_DELIMITER).append(LF);
        if (fields != null) {
            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                Object val = entry.getValue();
                sb.append(entry.getKey()).append(": ");
                if (val instanceof Boolean || val instanceof Number) {
                    sb.append(val);
                } else {
                    sb.append(DQUOTE).append(escapeFrontmatterValue(val != null ? val.toString() : null))
                            .append(DQUOTE);
                }
                sb.append(LF);
            }
        }
        sb.append(FRONTMATTER_DELIMITER).append(LF).append(LF);
        return sb.toString();
    }

    public static String escapeFrontmatterValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(DQUOTE_STR, ESCAPED_DQUOTE).replace(CRLF, " ").replace(LF, ' ').replace(CR, ' ');
    }
}
