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
    private static final String NL = String.valueOf(LF);
    private static final String CRLF = String.valueOf(CR) + LF;
    private static final String DQUOTE_STR = String.valueOf(DQUOTE);
    private static final String ESCAPED_DQUOTE = String.valueOf(BACKSLASH) + DQUOTE;

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
        if (startIdx >= lines.size()) {
            return "";
        }
        return String.join(NL, lines.subList(startIdx, lines.size()));
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
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (FRONTMATTER_DELIMITER.equals(line.trim())) {
                break;
            }
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                String key = line.substring(0, colonIdx).trim();
                String val = normalizeFrontmatterValue(line.substring(colonIdx + 1).trim());
                if (!key.isEmpty()) {
                    map.put(key, val);
                }
            }
        }
        return map;
    }

    public static String normalizeFrontmatterValue(String val) {
        if (val == null || val.length() < 2) {
            if (val != null) {
                return val;
            }
            return "";
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
                    String rawStr = null;
                    if (val != null) {
                        rawStr = val.toString();
                    }
                    sb.append(DQUOTE).append(escapeFrontmatterValue(rawStr)).append(DQUOTE);
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
