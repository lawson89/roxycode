package org.roxycode.app.ai.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/** Service providing safe file editing capabilities within the project sandbox. */
@AgentService(
        value = "fileEditorService",
        phases = {"CODE"})
@AgentDoc(
        "Provides safe, ergonomic file editing capabilities for the AI agent with phase protection and security sandboxing.")
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class FileEditorService {

    private static final Logger log = LoggerFactory.getLogger(FileEditorService.class);
    private static final int MAX_DIAGNOSTIC_LINES = 3;
    private static final int SINGLE_MATCH_COUNT = 1;
    private static final double MIN_LINE_SIMILARITY = 0.6;
    private static final JaroWinklerSimilarity JARO_WINKLER = new JaroWinklerSimilarity();
    private static final char LF = '\n';
    private static final String WHITESPACE_REGEX = "\\s+";

    private final ProjectService projectService;
    private final ApplicationEventPublisher eventPublisher;

    public FileEditorService(
            ProjectService projectService, ApplicationEventPublisher eventPublisher) {
        this.projectService = projectService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Writes content to a file, overwriting it if it exists.
     *
     * @param relativePath the path relative to the project root
     * @param content the content to write
     * @return EditorResult indicating success
     * @throws IOException if an I/O error occurs
     */
    @AgentDoc(
            value = "Writes content to a file, overwriting it if it exists.",
            examples =
                    "fileEditorService.writeFile('src/main/resources/config.properties', 'key=value')")
    public EditorResult writeFile(
            @AgentDoc("The file path relative to the project root") String relativePath,
            @AgentDoc("The full content to write to the file") String content)
            throws IOException {
        Path path = projectService.resolvePath(relativePath);
        if (Files.exists(path) && Files.isDirectory(path)) {
            return new EditorResult(false, "", "Path is a directory: " + relativePath);
        }
        PathUtils.createParentDirectories(path);
        Files.writeString(path, content);

        long size = Files.size(path);
        log.info("Wrote file: {} ({} bytes) at {}", relativePath, size, path.toAbsolutePath());

        eventPublisher.publishEvent(new FileChangedEvent(relativePath));
        return new EditorResult(true, "File written successfully: " + relativePath, null);
    }

    /**
     * Replaces a unique block of text with new content.
     *
     * @param relativePath the path relative to the project root
     * @param targetBlock the text block to replace
     * @param replacementBlock the new content
     * @return EditorResult indicating success or diagnostic error hint
     * @throws IOException if an I/O error occurs
     */
    @AgentDoc(
            value = "Replaces a unique block of text with new content.",
            examples =
                    "fileEditorService.replaceBlock('src/Main.java', '// TODO: implement', 'System.out.println('Hello');')")
    public EditorResult replaceBlock(
            @AgentDoc("The file path relative to the project root") String relativePath,
            @AgentDoc("The existing text block to find and replace") String targetBlock,
            @AgentDoc("The replacement text block") String replacementBlock)
            throws IOException {
        Path path = projectService.resolvePath(relativePath);
        EditorResult validationError = validateRegularFile(path, relativePath);
        if (validationError != null) {
            return validationError;
        }

        String content = Files.readString(path);
        if (targetBlock == null || targetBlock.isBlank()) {
            return new EditorResult(
                    false, "", buildTargetNotFoundHint(relativePath, content, targetBlock));
        }

        String lineSeparator = detectLineSeparator(content, replacementBlock);
        String normalizedTarget = normalizeLineEndings(targetBlock, lineSeparator);
        String normalizedReplacement =
                replacementBlock == null
                        ? ""
                        : normalizeLineEndings(replacementBlock, lineSeparator);

        // Pass 1: Exact match with normalized line endings
        List<Integer> exactMatchOffsets = findExactMatches(content, normalizedTarget);
        if (exactMatchOffsets.size() == SINGLE_MATCH_COUNT) {
            int start = exactMatchOffsets.get(0);
            int end = start + normalizedTarget.length();
            return applyReplacement(path, relativePath, content, start, end, normalizedReplacement);
        }
        if (exactMatchOffsets.size() > SINGLE_MATCH_COUNT) {
            List<Integer> matchLines = offsetsToLineNumbers(content, exactMatchOffsets);
            return new EditorResult(
                    false,
                    "",
                    "TargetBlock is not unique in file: "
                            + relativePath
                            + " (found "
                            + matchLines.size()
                            + " matches at lines: "
                            + matchLines
                            + ")");
        }

        // Pass 2: Whitespace-agnostic regex fallback with leading indentation preservation
        String[] parts = normalizedTarget.trim().split(WHITESPACE_REGEX);
        StringBuilder sb = new StringBuilder("(?s)");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            sb.append(Pattern.quote(parts[i]));
            if (i < parts.length - 1) {
                sb.append(WHITESPACE_REGEX);
            }
        }
        Pattern pattern = Pattern.compile(sb.toString());
        Matcher matcher = pattern.matcher(content);

        List<int[]> regexMatches = new ArrayList<>();
        while (matcher.find()) {
            regexMatches.add(new int[] {matcher.start(), matcher.end()});
        }

        if (regexMatches.isEmpty()) {
            return new EditorResult(
                    false, "", buildTargetNotFoundHint(relativePath, content, targetBlock));
        }
        if (regexMatches.size() > SINGLE_MATCH_COUNT) {
            List<Integer> offsets = new ArrayList<>(regexMatches.size());
            for (int[] match : regexMatches) {
                offsets.add(match[0]);
            }
            List<Integer> matchLines = offsetsToLineNumbers(content, offsets);
            return new EditorResult(
                    false,
                    "",
                    "TargetBlock is not unique in file: "
                            + relativePath
                            + " (found "
                            + matchLines.size()
                            + " matches at lines: "
                            + matchLines
                            + ")");
        }

        int start = regexMatches.get(0)[0];
        int end = regexMatches.get(0)[1];

        int lineStart = start == 0 ? 0 : content.lastIndexOf('\n', start - 1) + 1;
        String linePrefix = content.substring(lineStart, start);
        if (isHorizontalWhitespaceOnly(linePrefix)
                && (startsWithHorizontalWhitespace(stripLeadingNewlines(normalizedTarget))
                        || startsWithHorizontalWhitespace(normalizedReplacement))) {
            start = lineStart;
        }

        return applyReplacement(path, relativePath, content, start, end, normalizedReplacement);
    }

    /**
     * Replaces a range of lines with new content.
     *
     * @param relativePath the path relative to the project root
     * @param startLine the start line number (1-based)
     * @param endLine the end line number (1-based, inclusive)
     * @param replacementText the new content for the range
     * @return EditorResult indicating success or diagnostic error hint
     * @throws IOException if an I/O error occurs
     */
    @AgentDoc(
            value = "Replaces a range of lines with new content using 1-based indexing.",
            examples =
                    "fileEditorService.replaceLines('pom.xml', 10, 15, '    <dependencies>\n        ...\n    </dependencies>')")
    public EditorResult replaceLines(
            @AgentDoc("The file path relative to the project root") String relativePath,
            @AgentDoc("The 1-based start line number (inclusive)") int startLine,
            @AgentDoc("The 1-based end line number (inclusive)") int endLine,
            @AgentDoc("The replacement content for the line range") String replacementText)
            throws IOException {
        Path path = projectService.resolvePath(relativePath);
        EditorResult validationError = validateRegularFile(path, relativePath);
        if (validationError != null) {
            return validationError;
        }

        String rawContent = Files.readString(path);
        String lineSeparator = detectLineSeparator(rawContent, replacementText);
        boolean hasTrailingNewline = hasTrailingLineSeparator(rawContent);
        List<String> lines = rawContent.lines().toList();

        if (startLine < 1
                || startLine > lines.size()
                || endLine < startLine
                || endLine > lines.size()) {
            return new EditorResult(
                    false,
                    "",
                    "Invalid line range: "
                            + startLine
                            + " to "
                            + endLine
                            + " (valid range: 1 to "
                            + lines.size()
                            + ", file has "
                            + lines.size()
                            + " lines)");
        }

        List<String> newLines = new ArrayList<>(lines);
        newLines.subList(startLine - 1, endLine).clear();
        newLines.addAll(
                startLine - 1,
                replacementText.isEmpty() ? List.of("") : replacementText.lines().toList());

        writeLinesPreservingFormat(path, newLines, lineSeparator, hasTrailingNewline);

        long size = Files.size(path);
        log.info(
                "Replaced lines {}-{} in file: {} ({} bytes) at {}",
                startLine,
                endLine,
                relativePath,
                size,
                path.toAbsolutePath());

        eventPublisher.publishEvent(new FileChangedEvent(relativePath));
        return new EditorResult(true, "Lines replaced successfully in: " + relativePath, null);
    }

    /**
     * Inserts content at a specific line number.
     *
     * @param relativePath the path relative to the project root
     * @param lineNumber the line number to insert at (1-based)
     * @param content the content to insert
     * @return EditorResult indicating success or diagnostic error hint
     * @throws IOException if an I/O error occurs
     */
    @AgentDoc(
            value = "Inserts content at a specific line number using 1-based indexing.",
            examples =
                    "fileEditorService.insertAtLine('src/Main.java', 5, 'import java.util.List;')")
    public EditorResult insertAtLine(
            @AgentDoc("The file path relative to the project root") String relativePath,
            @AgentDoc("The 1-based line number at which to insert") int lineNumber,
            @AgentDoc("The content to insert") String content)
            throws IOException {
        Path path = projectService.resolvePath(relativePath);
        EditorResult validationError = validateRegularFile(path, relativePath);
        if (validationError != null) {
            return validationError;
        }

        String rawContent = Files.readString(path);
        String lineSeparator = detectLineSeparator(rawContent, content);
        boolean hasTrailingNewline =
                rawContent.isEmpty()
                        ? hasTrailingLineSeparator(content)
                        : hasTrailingLineSeparator(rawContent);
        List<String> lines = rawContent.lines().toList();

        int maxInsertLine = lines.size() + 1;
        if (lineNumber < 1 || lineNumber > maxInsertLine) {
            return new EditorResult(
                    false,
                    "",
                    "Invalid line number: "
                            + lineNumber
                            + " (valid range: 1 to "
                            + maxInsertLine
                            + ", file has "
                            + lines.size()
                            + " lines)");
        }

        List<String> newLines = new ArrayList<>(lines);
        newLines.addAll(lineNumber - 1, content.isEmpty() ? List.of("") : content.lines().toList());

        writeLinesPreservingFormat(path, newLines, lineSeparator, hasTrailingNewline);

        long size = Files.size(path);
        log.info(
                "Inserted content at line {} in file: {} ({} bytes) at {}",
                lineNumber,
                relativePath,
                size,
                path.toAbsolutePath());

        eventPublisher.publishEvent(new FileChangedEvent(relativePath));
        return new EditorResult(true, "Content inserted successfully in: " + relativePath, null);
    }

    /**
     * Deletes a file at the given path relative to the project root.
     *
     * @param relativePath the path relative to the project root
     * @return EditorResult indicating success or diagnostic error hint
     * @throws IOException if an I/O error occurs
     */
    @AgentDoc(
            value = "Deletes a file at the given path relative to the project root.",
            examples = "fileEditorService.deleteFile('src/main/java/OldClass.java')")
    public EditorResult deleteFile(
            @AgentDoc("The file path relative to the project root") String relativePath)
            throws IOException {
        Path path = projectService.resolvePath(relativePath);
        EditorResult validationError =
                validateRegularFile(path, relativePath, "Cannot delete directory: ");
        if (validationError != null) {
            return validationError;
        }
        Files.delete(path);
        log.info("Deleted file: {} at {}", relativePath, path.toAbsolutePath());

        eventPublisher.publishEvent(new FileChangedEvent(relativePath));
        return new EditorResult(true, "File deleted successfully: " + relativePath, null);
    }

    /**
     * Moves or renames a file within the project sandbox.
     *
     * @param sourcePath the source path relative to the project root
     * @param targetPath the target path relative to the project root
     * @return EditorResult indicating success or diagnostic error hint
     * @throws IOException if an I/O error occurs
     */
    @AgentDoc(
            value = "Moves or renames a file within the project sandbox.",
            examples =
                    "fileEditorService.moveFile('src/main/java/OldName.java', 'src/main/java/NewName.java')")
    public EditorResult moveFile(
            @AgentDoc("The source path relative to the project root") String sourcePath,
            @AgentDoc("The target path relative to the project root") String targetPath)
            throws IOException {
        Path source = projectService.resolvePath(sourcePath);
        Path target = projectService.resolvePath(targetPath);
        if (!Files.exists(source)) {
            return new EditorResult(false, "", "File does not exist: " + sourcePath);
        }
        PathUtils.createParentDirectories(target);
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        log.info("Moved file from {} to {}", sourcePath, targetPath);

        eventPublisher.publishEvent(new FileChangedEvent(sourcePath));
        eventPublisher.publishEvent(new FileChangedEvent(targetPath));
        return new EditorResult(
                true, "File moved successfully from " + sourcePath + " to " + targetPath, null);
    }

    /**
     * Copies a file to a target path within the project sandbox.
     *
     * @param sourcePath the source file path relative to the project root
     * @param targetPath the target file path relative to the project root
     * @return EditorResult indicating success or diagnostic error hint
     * @throws IOException if an I/O error occurs
     */
    @AgentDoc(
            value = "Copies a file to a target path within the project sandbox.",
            examples =
                    "fileEditorService.copyFile('src/main/resources/template.toml', 'src/main/resources/custom.toml')")
    public EditorResult copyFile(
            @AgentDoc("The source file path relative to the project root") String sourcePath,
            @AgentDoc("The target file path relative to the project root") String targetPath)
            throws IOException {
        Path source = projectService.resolvePath(sourcePath);
        Path target = projectService.resolvePath(targetPath);
        EditorResult validationError =
                validateRegularFile(source, sourcePath, "Cannot copy directory: ");
        if (validationError != null) {
            return validationError;
        }
        PathUtils.createParentDirectories(target);
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        log.info("Copied file from {} to {}", sourcePath, targetPath);

        eventPublisher.publishEvent(new FileChangedEvent(targetPath));
        return new EditorResult(
                true, "File copied successfully from " + sourcePath + " to " + targetPath, null);
    }

    /**
     * Creates a directory and any nonexistent parent directories within the project sandbox.
     *
     * @param relativePath the directory path relative to the project root
     * @return EditorResult indicating success or diagnostic error hint
     * @throws IOException if an I/O error occurs
     */
    @AgentDoc(
            value =
                    "Creates a directory and any nonexistent parent directories within the project sandbox.",
            examples = "fileEditorService.mkdirs('src/main/java/org/roxycode/newpkg')")
    public EditorResult mkdirs(
            @AgentDoc("The directory path relative to the project root") String relativePath)
            throws IOException {
        Path path = projectService.resolvePath(relativePath);
        if (Files.exists(path) && !Files.isDirectory(path)) {
            return new EditorResult(
                    false, "", "A regular file already exists at path: " + relativePath);
        }
        Files.createDirectories(path);
        log.info("Created directory: {} at {}", relativePath, path.toAbsolutePath());

        eventPublisher.publishEvent(new FileChangedEvent(relativePath));
        return new EditorResult(true, "Directory created successfully: " + relativePath, null);
    }

    private static EditorResult validateRegularFile(Path path, String relativePath) {
        return validateRegularFile(path, relativePath, "Path is a directory: ");
    }

    private static EditorResult validateRegularFile(
            Path path, String relativePath, String directoryErrorPrefix) {
        if (!Files.exists(path)) {
            return new EditorResult(false, "", "File does not exist: " + relativePath);
        }
        if (Files.isDirectory(path)) {
            return new EditorResult(false, "", directoryErrorPrefix + relativePath);
        }
        return null;
    }

    private EditorResult applyReplacement(
            Path path,
            String relativePath,
            String content,
            int start,
            int end,
            String replacementBlock)
            throws IOException {
        String newContent = content.substring(0, start) + replacementBlock + content.substring(end);
        Files.writeString(path, newContent);

        long size = Files.size(path);
        log.info(
                "Replaced block in file: {} ({} bytes) at {}",
                relativePath,
                size,
                path.toAbsolutePath());

        eventPublisher.publishEvent(new FileChangedEvent(relativePath));
        return new EditorResult(true, "Block replaced successfully in: " + relativePath, null);
    }

    private static List<Integer> findExactMatches(String content, String target) {
        List<Integer> offsets = new ArrayList<>();
        if (target.isEmpty()) {
            return offsets;
        }
        int index = content.indexOf(target, 0);
        while (index != -1) {
            offsets.add(index);
            index = content.indexOf(target, index + target.length());
        }
        return offsets;
    }

    private static List<Integer> offsetsToLineNumbers(String content, List<Integer> offsets) {
        List<Integer> lineNumbers = new ArrayList<>(offsets.size());
        for (int offset : offsets) {
            lineNumbers.add(lineNumberAtOffset(content, offset));
        }
        return lineNumbers;
    }

    private static int lineNumberAtOffset(String content, int offset) {
        int limit = Math.max(0, Math.min(offset, content.length()));
        return 1 + StringUtils.countMatches(content.subSequence(0, limit), LF);
    }

    private String buildTargetNotFoundHint(
            String relativePath, String content, String targetBlock) {
        StringBuilder hint =
                new StringBuilder("TargetBlock not found in file: ").append(relativePath);
        List<String> fileLines = content.lines().toList();
        if (fileLines.isEmpty() || targetBlock == null || targetBlock.isBlank()) {
            hint.append(" (file has ").append(fileLines.size()).append(" lines)");
            return hint.toString();
        }

        String firstTargetLine =
                targetBlock
                        .lines()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .findFirst()
                        .orElse("")
                        .toLowerCase(Locale.ROOT);

        record ScoredLine(int lineNum, double score) {}
        List<ScoredLine> scoredLines = new ArrayList<>();
        if (!firstTargetLine.isEmpty()) {
            for (int i = 0; i < fileLines.size(); i++) {
                String lineTrimmed = fileLines.get(i).trim().toLowerCase(Locale.ROOT);
                if (lineTrimmed.isEmpty()) {
                    continue;
                }
                double similarity =
                        lineTrimmed.contains(firstTargetLine)
                                ? 1.0
                                : JARO_WINKLER.apply(firstTargetLine, lineTrimmed);
                if (similarity >= MIN_LINE_SIMILARITY) {
                    scoredLines.add(new ScoredLine(i + 1, similarity));
                }
            }
        }

        scoredLines.sort((a, b) -> Double.compare(b.score(), a.score()));
        if (!scoredLines.isEmpty()) {
            hint.append(". Closest matching lines (file has ")
                    .append(fileLines.size())
                    .append(" lines):");
            int count = Math.min(MAX_DIAGNOSTIC_LINES, scoredLines.size());
            for (int i = 0; i < count; i++) {
                int lineNum = scoredLines.get(i).lineNum();
                hint.append("\nLine ")
                        .append(lineNum)
                        .append(": ")
                        .append(fileLines.get(lineNum - 1));
            }
        } else {
            hint.append(" (no similar lines found; file has ")
                    .append(fileLines.size())
                    .append(" lines)");
        }
        return hint.toString();
    }

    private static String normalizeLineEndings(String text, String lineSeparator) {
        return text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", lineSeparator);
    }

    private static String stripLeadingNewlines(String text) {
        return StringUtils.stripStart(text, "\r\n");
    }

    private static boolean isHorizontalWhitespaceOnly(String text) {
        return !text.isEmpty() && StringUtils.containsOnly(text, " \t");
    }

    private static boolean startsWithHorizontalWhitespace(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        char first = text.charAt(0);
        return first == ' ' || first == '\t';
    }

    private static String detectLineSeparator(String rawContent, String fallbackText) {
        if (rawContent.contains("\r\n")) {
            return "\r\n";
        }
        if (rawContent.contains("\n")) {
            return "\n";
        }
        if (fallbackText != null && fallbackText.contains("\r\n")) {
            return "\r\n";
        }
        return "\n";
    }

    private static boolean hasTrailingLineSeparator(String text) {
        return text != null && (text.endsWith("\n") || text.endsWith("\r"));
    }

    private static void writeLinesPreservingFormat(
            Path path, List<String> lines, String lineSeparator, boolean hasTrailingNewline)
            throws IOException {
        if (lines.isEmpty()) {
            Files.writeString(path, "");
            return;
        }
        String joined = String.join(lineSeparator, lines);
        if (hasTrailingNewline) {
            joined += lineSeparator;
        }
        Files.writeString(path, joined);
    }
}
