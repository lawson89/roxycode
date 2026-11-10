package org.roxycode.app.ai.services;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.roxycode.app.util.PathUtils;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Stateless build output, TOML escape, RipGrep error block, and JUnit XML failure parser used by
 * {@link GenericBuildToolService}.
 */
final class BuildOutputParser {

    private static final Logger log = LoggerFactory.getLogger(BuildOutputParser.class);

    private static final char ESC_CHAR = '\u001b';
    private static final Pattern ANSI_ESCAPE_PATTERN = Pattern.compile(ESC_CHAR + "\\[[;\\d]*[ -/]*[@-~]");
    private static final String RG_BLOCK_SEPARATOR = "--";
    private static final String TAG_FAILURE = "failure";
    private static final String TAG_ERROR = "error";
    private static final int MAX_ENRICHED_ERROR_LOCATIONS = 3;
    private static final int ERROR_CONTEXT_PADDING = 5;
    private static final Pattern ERROR_FILE_LINE_PATTERN = Pattern.compile(
            "([A-Za-z0-9_./\\\\-]+\\.[A-Za-z0-9_]+)(?::\\[(\\d+)(?:,\\d+)?\\]|:(\\d+)(?::\\d+)?|\\((\\d+)(?:,\\d+)?\\))");

    private static final char COMMENT_CHAR = '#';
    private static final char DOUBLE_QUOTE_CHAR = '"';
    private static final char SINGLE_QUOTE_CHAR = '\'';
    private static final char BACKSLASH_CHAR = '\\';
    private static final char UNICODE_ESCAPE_LOWER = 'u';
    private static final char UNICODE_ESCAPE_UPPER = 'U';
    private static final String TRIPLE_DOUBLE_QUOTE = "\"\"\"";
    private static final String DOUBLE_BACKSLASH = "\\\\";
    private static final String VALID_SIMPLE_TOML_ESCAPES = "btnfr\"\\";

    private enum TomlLexerState {
        NORMAL, COMMENT, BASIC_STRING, MULTILINE_BASIC_STRING, LITERAL_STRING, MULTILINE_LITERAL_STRING
    }

    private BuildOutputParser() {
    }

    /**
     * Escapes unescaped single backslashes inside TOML basic strings (such as regex patterns like {@code \d} or
     * {@code \s} in {@code build.toml}) while preserving valid TOML escape sequences and literal strings.
     *
     * <p>
     * This is necessary because Jackson's {@code TomlMapper} strictly follows the TOML specification, which treats
     * backslashes in basic strings as the start of an escape sequence. If the character following the backslash is not
     * a valid escape character (like 'd' in {@code \d}), the parser will fail. By doubling the backslash to {@code \d},
     * we ensure the regex is parsed correctly as a literal backslash followed by 'd'.
     */
    static String sanitizeTomlEscapes(String toml) {
        if (toml == null || toml.indexOf(BACKSLASH_CHAR) < 0) {
            return StringUtils.defaultString(toml);
        }
        StringBuilder sb = new StringBuilder(toml.length() + 16);
        int len = toml.length();
        TomlLexerState state = TomlLexerState.NORMAL;
        int i = 0;
        while (i < len) {
            char c = toml.charAt(i);
            switch (state) {
                case COMMENT :
                    sb.append(c);
                    if (isLineBreak(c)) {
                        state = TomlLexerState.NORMAL;
                    }
                    i++;
                    break;
                case LITERAL_STRING :
                    sb.append(c);
                    if (c == SINGLE_QUOTE_CHAR || isLineBreak(c)) {
                        state = TomlLexerState.NORMAL;
                    }
                    i++;
                    break;
                case MULTILINE_LITERAL_STRING :
                    if (startsWithTripleQuote(toml, i, SINGLE_QUOTE_CHAR)) {
                        sb.append("'''");
                        state = TomlLexerState.NORMAL;
                        i += 3;
                    } else {
                        sb.append(c);
                        i++;
                    }
                    break;
                case BASIC_STRING :
                case MULTILINE_BASIC_STRING :
                    boolean multiline = state == TomlLexerState.MULTILINE_BASIC_STRING;
                    if (multiline && startsWithTripleQuote(toml, i, DOUBLE_QUOTE_CHAR)) {
                        sb.append(TRIPLE_DOUBLE_QUOTE);
                        state = TomlLexerState.NORMAL;
                        i += 3;
                    } else if (!multiline && (c == DOUBLE_QUOTE_CHAR || isLineBreak(c))) {
                        sb.append(c);
                        state = TomlLexerState.NORMAL;
                        i++;
                    } else if (c == BACKSLASH_CHAR && i + 1 < len) {
                        if (isValidTomlEscape(toml, i + 1, multiline)) {
                            sb.append(BACKSLASH_CHAR).append(toml.charAt(i + 1));
                            i += 2;
                        } else {
                            sb.append(DOUBLE_BACKSLASH);
                            i++;
                        }
                    } else {
                        sb.append(c);
                        i++;
                    }
                    break;
                default :
                    if (c == COMMENT_CHAR) {
                        state = TomlLexerState.COMMENT;
                        sb.append(c);
                        i++;
                    } else if (startsWithTripleQuote(toml, i, DOUBLE_QUOTE_CHAR)) {
                        state = TomlLexerState.MULTILINE_BASIC_STRING;
                        sb.append(TRIPLE_DOUBLE_QUOTE);
                        i += 3;
                    } else if (startsWithTripleQuote(toml, i, SINGLE_QUOTE_CHAR)) {
                        state = TomlLexerState.MULTILINE_LITERAL_STRING;
                        sb.append("'''");
                        i += 3;
                    } else if (c == DOUBLE_QUOTE_CHAR) {
                        state = TomlLexerState.BASIC_STRING;
                        sb.append(c);
                        i++;
                    } else if (c == SINGLE_QUOTE_CHAR) {
                        state = TomlLexerState.LITERAL_STRING;
                        sb.append(c);
                        i++;
                    } else {
                        sb.append(c);
                        i++;
                    }
                    break;
            }
        }
        return sb.toString();
    }

    private static boolean isLineBreak(char c) {
        return c == '\n' || c == '\r';
    }

    private static boolean startsWithTripleQuote(String text, int index, char quoteChar) {
        return index + 2 < text.length() && text.charAt(index) == quoteChar && text.charAt(index + 1) == quoteChar
                && text.charAt(index + 2) == quoteChar;
    }

    private static boolean isValidTomlEscape(String text, int escapeCharIndex, boolean multilineBasic) {
        char next = text.charAt(escapeCharIndex);
        if (VALID_SIMPLE_TOML_ESCAPES.indexOf(next) >= 0) {
            return true;
        }
        if (next == UNICODE_ESCAPE_LOWER) {
            return hasHexDigits(text, escapeCharIndex + 1, 4);
        }
        if (next == UNICODE_ESCAPE_UPPER) {
            return hasHexDigits(text, escapeCharIndex + 1, 8);
        }
        return multilineBasic && (Character.isWhitespace(next) || isLineBreak(next));
    }

    private static boolean hasHexDigits(String text, int start, int count) {
        if (start + count > text.length()) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            if (!HexFormat.isHexDigit(text.charAt(start + i))) {
                return false;
            }
        }
        return true;
    }

    static String stripAnsiCodes(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.indexOf(ESC_CHAR) < 0) {
            return text;
        }
        return ANSI_ESCAPE_PATTERN.matcher(text).replaceAll("");
    }

    static String getLogTail(String fullOutput) {
        String sanitized = stripAnsiCodes(fullOutput);
        if (sanitized.isEmpty()) {
            return "";
        }
        if (sanitized.length() <= GenericBuildToolService.MAX_LOG_TAIL_BYTES) {
            return sanitized;
        }
        return GenericBuildToolService.LOG_TRUNCATED_PREFIX
                + sanitized.substring(sanitized.length() - GenericBuildToolService.MAX_LOG_TAIL_BYTES);
    }

    static List<String> extractWithRipGrep(String regex, Path logFile, int contextLines) {
        if (regex == null || regex.isBlank() || logFile == null || !Files.exists(logFile)) {
            return List.of();
        }
        List<String> command = new ArrayList<>();
        command.add("rg");
        command.add("--color=never");
        command.add("-N");
        if (contextLines > 0) {
            command.add("-A");
            command.add(String.valueOf(contextLines));
        }
        command.add("-e");
        command.add(regex);
        command.add(logFile.toString());

        ProcessResult result = ProcessRunner.run(command, null, false);
        if (result.timedOut() || !result.isSuccess()) {
            return List.of();
        }
        return parseRipGrepOutput(result.output(), contextLines);
    }

    static List<String> parseRipGrepOutput(String rawOutput, int contextLines) {
        String sanitized = stripAnsiCodes(rawOutput);
        if (sanitized.isBlank()) {
            return List.of();
        }
        List<String> blocks = new ArrayList<>();
        if (contextLines <= 0) {
            for (String line : sanitized.lines().toList()) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !blocks.contains(trimmed)) {
                    blocks.add(trimmed);
                    if (blocks.size() >= GenericBuildToolService.MAX_EXTRACTED_BLOCKS) {
                        break;
                    }
                }
            }
            return Collections.unmodifiableList(blocks);
        }

        List<String> currentBlockLines = new ArrayList<>();
        for (String line : sanitized.lines().toList()) {
            if (RG_BLOCK_SEPARATOR.equals(StringUtils.stripEnd(line, null))) {
                addFinalizedBlock(blocks, currentBlockLines);
                currentBlockLines.clear();
                if (blocks.size() >= GenericBuildToolService.MAX_EXTRACTED_BLOCKS) {
                    return Collections.unmodifiableList(blocks);
                }
            } else {
                currentBlockLines.add(line);
            }
        }
        if (blocks.size() < GenericBuildToolService.MAX_EXTRACTED_BLOCKS) {
            addFinalizedBlock(blocks, currentBlockLines);
        }
        return Collections.unmodifiableList(blocks);
    }

    private static void addFinalizedBlock(List<String> blocks, List<String> rawBlockLines) {
        int start = 0;
        int end = rawBlockLines.size();
        while (start < end && rawBlockLines.get(start).isBlank()) {
            start++;
        }
        while (end > start && rawBlockLines.get(end - 1).isBlank()) {
            end--;
        }
        if (start >= end) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (i > start) {
                sb.append('\n');
            }
            sb.append(StringUtils.stripEnd(rawBlockLines.get(i), null));
        }
        String block = sb.toString();
        if (!block.isEmpty() && !blocks.contains(block)) {
            blocks.add(block);
        }
    }

    static List<String> enrichErrorsWithSourceContext(List<String> errors, Path projectRoot) {
        if (errors == null || errors.isEmpty() || projectRoot == null || !Files.isDirectory(projectRoot)) {
            if (errors == null) {
                return List.of();
            }
            return errors;
        }
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        Set<String> enrichedLocations = new LinkedHashSet<>();
        List<String> enriched = new ArrayList<>(errors.size());

        for (String errorBlock : errors) {
            if (errorBlock == null || errorBlock.isBlank()
                    || enrichedLocations.size() >= MAX_ENRICHED_ERROR_LOCATIONS) {
                enriched.add(errorBlock);
                continue;
            }
            enriched.add(enrichSingleErrorBlock(errorBlock, normalizedRoot, enrichedLocations));
        }
        return enriched;
    }

    private static String enrichSingleErrorBlock(String errorBlock, Path normalizedRoot,
            Set<String> enrichedLocations) {
        StringBuilder updatedBlock = new StringBuilder(errorBlock);
        Matcher matcher = ERROR_FILE_LINE_PATTERN.matcher(errorBlock);
        while (matcher.find() && enrichedLocations.size() < MAX_ENRICHED_ERROR_LOCATIONS) {
            String rawPath = matcher.group(1);
            int lineNumber = parseMatchedLineNumber(matcher);
            if (lineNumber <= 0) {
                continue;
            }
            Path resolvedFile = resolveProjectSourceFile(normalizedRoot, rawPath);
            if (resolvedFile == null) {
                continue;
            }
            String relPath = PathUtils.normalizeRelativePath(normalizedRoot.relativize(resolvedFile).toString());
            String locationKey = relPath + ":" + lineNumber;
            if (enrichedLocations.contains(locationKey)) {
                continue;
            }
            String contextBlock = buildSourceContextAppendBlock(resolvedFile, relPath, lineNumber, updatedBlock);
            if (contextBlock != null) {
                updatedBlock.append(contextBlock);
                enrichedLocations.add(locationKey);
            }
        }
        return updatedBlock.toString();
    }

    private static int parseMatchedLineNumber(Matcher matcher) {
        for (int groupIdx = 2; groupIdx <= 4; groupIdx++) {
            String rawLine = matcher.group(groupIdx);
            if (rawLine != null && !rawLine.isEmpty()) {
                try {
                    return Integer.parseInt(rawLine);
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static Path resolveProjectSourceFile(Path normalizedRoot, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        String cleaned = rawPath.trim().replace(BACKSLASH_CHAR, '/');
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        try {
            Path directCandidate = Path.of(cleaned);
            if (directCandidate.isAbsolute()) {
                Path normalizedCandidate = directCandidate.normalize();
                if (normalizedCandidate.startsWith(normalizedRoot) && isReadableSourceFile(normalizedCandidate)) {
                    return normalizedCandidate;
                }
                return resolveSuffixUnderRoot(normalizedRoot, cleaned);
            }
            Path relativeCandidate = normalizedRoot.resolve(cleaned).normalize();
            if (relativeCandidate.startsWith(normalizedRoot) && isReadableSourceFile(relativeCandidate)) {
                return relativeCandidate;
            }
            return resolveSuffixUnderRoot(normalizedRoot, cleaned);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Path resolveSuffixUnderRoot(Path normalizedRoot, String slashPath) {
        int slashIdx = slashPath.indexOf('/');
        while (slashIdx >= 0 && slashIdx + 1 < slashPath.length()) {
            String subPath = slashPath.substring(slashIdx + 1);
            if (!subPath.isEmpty() && !subPath.startsWith("/")) {
                try {
                    Path candidate = normalizedRoot.resolve(subPath).normalize();
                    if (candidate.startsWith(normalizedRoot) && isReadableSourceFile(candidate)) {
                        return candidate;
                    }
                } catch (Exception ignored) {
                    // Try next path segment
                }
            }
            slashIdx = slashPath.indexOf('/', slashIdx + 1);
        }
        return null;
    }

    private static boolean isReadableSourceFile(Path file) {
        try {
            return Files.isRegularFile(file) && !FileReadService.isBinaryFile(file);
        } catch (IOException e) {
            return false;
        }
    }

    private static String buildSourceContextAppendBlock(Path resolvedFile, String relPath, int lineNumber,
            CharSequence existingErrorBlock) {
        try {
            List<String> lines = Files.readAllLines(resolvedFile);
            if (lines.isEmpty() || lineNumber > lines.size()) {
                return null;
            }
            int startLine = Math.max(1, lineNumber - ERROR_CONTEXT_PADDING);
            int endLine = Math.min(lines.size(), lineNumber + ERROR_CONTEXT_PADDING);
            String header = "--- Source Context (" + relPath + ":" + startLine + "-" + endLine + ") ---";
            if (existingErrorBlock.toString().contains(header)) {
                return null;
            }
            String snippet = FileReadService.formatNumberedLines(lines, startLine, endLine);
            return "\n" + header + "\n" + snippet;
        } catch (Exception e) {
            return null;
        }
    }

    static List<String> extractJunitXmlFailures(Path projectRoot, List<String> configuredPaths,
            long commandStartTimeMillis) {
        if (projectRoot == null || configuredPaths == null || configuredPaths.isEmpty() || !Files.exists(projectRoot)) {
            return List.of();
        }
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        long thresholdMillis = commandStartTimeMillis - GenericBuildToolService.JUNIT_XML_CLOCK_SKEW_MS;
        List<Path> xmlFiles = discoverRecentJunitXmlFiles(normalizedRoot, configuredPaths, thresholdMillis);
        if (xmlFiles.isEmpty()) {
            return List.of();
        }

        List<String> failures = new ArrayList<>();
        try {
            DocumentBuilder builder = createSafeDocumentBuilder();
            for (Path xmlFile : xmlFiles) {
                parseJunitXmlFile(builder, xmlFile, failures);
                if (failures.size() >= GenericBuildToolService.MAX_EXTRACTED_BLOCKS) {
                    break;
                }
            }
        } catch (ParserConfigurationException e) {
            log.warn("Failed to configure JUnit XML parser: {}", e.getMessage());
        }
        return Collections.unmodifiableList(failures);
    }

    private static List<Path> discoverRecentJunitXmlFiles(Path normalizedRoot, List<String> configuredPaths,
            long thresholdMillis) {
        List<Path> discovered = new ArrayList<>();
        for (String rawPath : configuredPaths) {
            if (rawPath == null || rawPath.isBlank()) {
                continue;
            }
            Path candidate = normalizedRoot.resolve(rawPath.trim()).normalize();
            if (!candidate.startsWith(normalizedRoot) || !Files.exists(candidate)) {
                continue;
            }
            if (Files.isRegularFile(candidate)) {
                if (isRecentXmlFile(candidate, thresholdMillis) && !discovered.contains(candidate)) {
                    discovered.add(candidate);
                }
                continue;
            }
            if (Files.isDirectory(candidate)) {
                collectRecentXmlFilesInDirectory(candidate, thresholdMillis, discovered);
            }
        }
        return discovered;
    }

    private static void collectRecentXmlFilesInDirectory(Path directory, long thresholdMillis, List<Path> target) {
        try (Stream<Path> walk = Files.walk(directory)) {
            List<Path> matched = walk.filter(Files::isRegularFile)
                    .filter(path -> isRecentXmlFile(path, thresholdMillis)).sorted().toList();
            for (Path path : matched) {
                if (!target.contains(path)) {
                    target.add(path);
                }
            }
        } catch (IOException e) {
            log.debug("Failed to scan JUnit XML directory {}: {}", directory, e.getMessage());
        }
    }

    private static boolean isRecentXmlFile(Path file, long thresholdMillis) {
        Path fileName = file.getFileName();
        if (fileName == null) {
            return false;
        }
        String nameLower = fileName.toString().toLowerCase(Locale.ROOT);
        if (!nameLower.endsWith(".xml")) {
            return false;
        }
        try {
            return Files.getLastModifiedTime(file).toMillis() >= thresholdMillis;
        } catch (IOException e) {
            return false;
        }
    }

    private static DocumentBuilder createSafeDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    private static void parseJunitXmlFile(DocumentBuilder builder, Path xmlFile, List<String> failures) {
        try (InputStream in = Files.newInputStream(xmlFile)) {
            Document doc = builder.parse(in);
            NodeList testCases = doc.getElementsByTagName("testcase");
            for (int i = 0; i < testCases.getLength(); i++) {
                Node node = testCases.item(i);
                if (node instanceof Element testCase) {
                    extractTestCaseFailures(testCase, failures);
                    if (failures.size() >= GenericBuildToolService.MAX_EXTRACTED_BLOCKS) {
                        return;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Skipping unreadable JUnit XML file {}: {}", xmlFile, e.getMessage());
        }
    }

    private static void extractTestCaseFailures(Element testCase, List<String> failures) {
        String className = StringUtils.trimToEmpty(testCase.getAttribute("classname"));
        String testName = StringUtils.trimToEmpty(testCase.getAttribute("name"));
        String testId = formatTestCaseIdentifier(className, testName);
        NodeList children = testCase.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element childElem) {
                String tag = childElem.getTagName();
                if (TAG_FAILURE.equals(tag) || TAG_ERROR.equals(tag)) {
                    String formatted = formatJunitFailureElement(testId, tag, childElem);
                    if (!formatted.isEmpty() && !failures.contains(formatted)) {
                        failures.add(formatted);
                        if (failures.size() >= GenericBuildToolService.MAX_EXTRACTED_BLOCKS) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private static String formatTestCaseIdentifier(String className, String testName) {
        if (!className.isEmpty() && !testName.isEmpty()) {
            return className + "#" + testName;
        }
        if (!testName.isEmpty()) {
            return testName;
        }
        if (!className.isEmpty()) {
            return className;
        }
        return "unknown";
    }

    private static String formatJunitFailureElement(String testId, String tag, Element element) {
        String type = stripAnsiCodes(element.getAttribute("type")).trim();
        String message = stripAnsiCodes(element.getAttribute("message")).replace("\r\n", "\n").trim();
        String body = stripAnsiCodes(element.getTextContent());

        StringBuilder sb = new StringBuilder();
        sb.append(testId).append(" [").append(tag.toUpperCase(Locale.ROOT)).append(']');
        String headerDetail = formatFailureHeaderDetail(type, message);
        if (!headerDetail.isEmpty()) {
            sb.append(": ").append(headerDetail);
        }

        if (!body.isBlank()) {
            int appendedLines = 0;
            for (String rawLine : body.lines().toList()) {
                String trimmedLine = rawLine.trim();
                if (trimmedLine.isEmpty()) {
                    continue;
                }
                if (appendedLines == 0 && (trimmedLine.equals(headerDetail) || trimmedLine.equals(message))) {
                    continue;
                }
                sb.append('\n').append("  ").append(trimmedLine);
                appendedLines++;
                if (appendedLines >= GenericBuildToolService.MAX_JUNIT_FAILURE_LINES) {
                    break;
                }
            }
        }
        return sb.toString();
    }

    private static String formatFailureHeaderDetail(String type, String message) {
        if (!type.isEmpty() && !message.isEmpty()) {
            if (message.startsWith(type)) {
                return message;
            }
            return type + ": " + message;
        }
        if (!type.isEmpty()) {
            return type;
        }
        return message;
    }
}
