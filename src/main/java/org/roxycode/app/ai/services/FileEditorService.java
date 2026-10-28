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

@AgentService(value = "fileEditorService", phases = {"CODE"})
@AgentDoc("Provides safe, ergonomic file editing capabilities for the AI agent with phase protection and security sandboxing.")
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class FileEditorService {

    private static final Logger log = LoggerFactory.getLogger(FileEditorService.class);
    private static final int MAX_DIAGNOSTIC_LINES = 3;
    private static final int SINGLE_MATCH_COUNT = 1;
    private static final double MIN_LINE_SIMILARITY = 0.6;
    private static final JaroWinklerSimilarity JARO_WINKLER = new JaroWinklerSimilarity();
    private static final char LF = (char) 10;
    private static final String NL = String.valueOf(LF);
    private static final String CRLF = String.valueOf((char) 13) + NL;
    private static final String CR = String.valueOf((char) 13);
    private static final String TAB_SPACE = " " + (char) 9;
    private static final String WHITESPACE_REGEX = String.valueOf((char) 92) + "s+";

    private final ProjectService projectService;
    private final ApplicationEventPublisher eventPublisher;

    public FileEditorService(ProjectService projectService, ApplicationEventPublisher eventPublisher) {
        this.projectService = projectService;
        this.eventPublisher = eventPublisher;
    }

    @AgentDoc(value = "Writes content to a file, overwriting it if it exists.", examples = "fileEditorService.writeFile('src/main/resources/config.properties', 'key=value')")
    public EditorResult writeFile(@AgentDoc("The file path relative to the project root") String relativePath,
            @AgentDoc("The full content to write to the file") String content) throws IOException {
        Path path = projectService.resolvePath(relativePath);
        if (Files.exists(path) && Files.isDirectory(path)) {
            return new EditorResult(false, "", "Path is a directory: " + relativePath);
        }
        PathUtils.createParentDirectories(path);
        Files.writeString(path, content);
        return completeFileMutation(path, relativePath, "File written successfully: " + relativePath);
    }

    @AgentDoc(value = "Replaces a unique block of text with new content.", examples = "fileEditorService.replaceBlock('src/Main.java', '// TODO: implement', 'System.out.println('Hello');')")
    public EditorResult replaceBlock(@AgentDoc("The file path relative to the project root") String relativePath,
            @AgentDoc("The existing text block to find and replace") String targetBlock,
            @AgentDoc("The replacement text block") String replacementBlock) throws IOException {
        Path path = projectService.resolvePath(relativePath);
        EditorResult validationError = validateRegularFile(path, relativePath);
        if (validationError != null)
            return validationError;

        String content = Files.readString(path);
        if (targetBlock == null || targetBlock.isBlank()) {
            return new EditorResult(false, "", buildTargetNotFoundHint(relativePath, content, targetBlock));
        }

        String lineSeparator = detectLineSeparator(content, replacementBlock);
        String normalizedTarget = normalizeLineEndings(targetBlock, lineSeparator);
        String normalizedReplacement = replacementBlock == null
                ? ""
                : normalizeLineEndings(replacementBlock, lineSeparator);

        List<Integer> exactMatchOffsets = findExactMatches(content, normalizedTarget);
        if (exactMatchOffsets.size() == SINGLE_MATCH_COUNT) {
            int start = exactMatchOffsets.get(0);
            return applyReplacement(path, relativePath, content, start, start + normalizedTarget.length(),
                    normalizedReplacement);
        }
        if (exactMatchOffsets.size() > SINGLE_MATCH_COUNT) {
            return nonUniqueMatchResult(relativePath, content, exactMatchOffsets);
        }

        String[] parts = normalizedTarget.trim().split(WHITESPACE_REGEX);
        StringBuilder sb = new StringBuilder("(?s)");
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(Pattern.quote(parts[i]));
                if (i < parts.length - 1)
                    sb.append(WHITESPACE_REGEX);
            }
        }
        Matcher matcher = Pattern.compile(sb.toString()).matcher(content);
        List<int[]> regexMatches = new ArrayList<>();
        while (matcher.find()) {
            regexMatches.add(new int[]{matcher.start(), matcher.end()});
        }

        if (regexMatches.isEmpty()) {
            return new EditorResult(false, "", buildTargetNotFoundHint(relativePath, content, targetBlock));
        }
        if (regexMatches.size() > SINGLE_MATCH_COUNT) {
            return nonUniqueMatchResult(relativePath, content, regexMatches.stream().map(m -> m[0]).toList());
        }

        int start = regexMatches.get(0)[0];
        int end = regexMatches.get(0)[1];
        int lineStart = start == 0 ? 0 : content.lastIndexOf(LF, start - 1) + 1;
        String linePrefix = content.substring(lineStart, start);
        if (isHorizontalWhitespaceOnly(linePrefix)
                && (startsWithHorizontalWhitespace(stripLeadingNewlines(normalizedTarget))
                        || startsWithHorizontalWhitespace(normalizedReplacement))) {
            start = lineStart;
        }
        return applyReplacement(path, relativePath, content, start, end, normalizedReplacement);
    }

    @AgentDoc(value = "Replaces a range of lines with new content using 1-based indexing.", examples = """
            fileEditorService.replaceLines('pom.xml', 10, 15, '    <dependencies>
                ...
            </dependencies>')""")
    public EditorResult replaceLines(@AgentDoc("The file path relative to the project root") String relativePath,
            @AgentDoc("The 1-based start line number (inclusive)") int startLine,
            @AgentDoc("The 1-based end line number (inclusive)") int endLine,
            @AgentDoc("The replacement content for the line range") String replacementText) throws IOException {
        return mutateLines(relativePath, replacementText, false, lines -> {
            if (startLine < 1 || startLine > lines.size() || endLine < startLine || endLine > lines.size()) {
                return "Invalid line range: " + startLine + " to " + endLine + " (valid range: 1 to " + lines.size()
                        + ", file has " + lines.size() + " lines)";
            }
            lines.subList(startLine - 1, endLine).clear();
            lines.addAll(startLine - 1, replacementText.isEmpty() ? List.of("") : replacementText.lines().toList());
            return null;
        }, "Lines replaced successfully in: " + relativePath);
    }

    @AgentDoc(value = "Inserts content at a specific line number using 1-based indexing.", examples = "fileEditorService.insertAtLine('src/Main.java', 5, 'import java.util.List;')")
    public EditorResult insertAtLine(@AgentDoc("The file path relative to the project root") String relativePath,
            @AgentDoc("The 1-based line number at which to insert") int lineNumber,
            @AgentDoc("The content to insert") String content) throws IOException {
        return mutateLines(relativePath, content, true, lines -> {
            int maxInsertLine = lines.size() + 1;
            if (lineNumber < 1 || lineNumber > maxInsertLine) {
                return "Invalid line number: " + lineNumber + " (valid range: 1 to " + maxInsertLine + ", file has "
                        + lines.size() + " lines)";
            }
            lines.addAll(lineNumber - 1, content.isEmpty() ? List.of("") : content.lines().toList());
            return null;
        }, "Content inserted successfully in: " + relativePath);
    }

    @FunctionalInterface
    private interface LineMutator {
        String mutate(List<String> mutableLines);
    }

    private EditorResult mutateLines(String relativePath, String inputContent, boolean fallbackTrailingToInputWhenEmpty,
            LineMutator mutator, String successMessage) throws IOException {
        Path path = projectService.resolvePath(relativePath);
        EditorResult validationError = validateRegularFile(path, relativePath);
        if (validationError != null)
            return validationError;

        String rawContent = Files.readString(path);
        String lineSeparator = detectLineSeparator(rawContent, inputContent);
        boolean hasTrailingNewline = (fallbackTrailingToInputWhenEmpty && rawContent.isEmpty())
                ? hasTrailingLineSeparator(inputContent)
                : hasTrailingLineSeparator(rawContent);
        List<String> newLines = new ArrayList<>(rawContent.lines().toList());
        String error = mutator.mutate(newLines);
        if (error != null) {
            return new EditorResult(false, "", error);
        }
        writeLinesPreservingFormat(path, newLines, lineSeparator, hasTrailingNewline);
        return completeFileMutation(path, relativePath, successMessage);
    }

    @AgentDoc(value = "Deletes a file at the given path relative to the project root.", examples = "fileEditorService.deleteFile('src/main/java/OldClass.java')")
    public EditorResult deleteFile(@AgentDoc("The file path relative to the project root") String relativePath)
            throws IOException {
        Path path = projectService.resolvePath(relativePath);
        EditorResult validationError = validateRegularFile(path, relativePath, "Cannot delete directory: ");
        if (validationError != null)
            return validationError;
        Files.delete(path);
        log.info("Deleted file: {} at {}", relativePath, path.toAbsolutePath());
        eventPublisher.publishEvent(new FileChangedEvent(relativePath));
        return new EditorResult(true, "File deleted successfully: " + relativePath, null);
    }

    @AgentDoc(value = "Moves or renames a file within the project sandbox.", examples = "fileEditorService.moveFile('src/main/java/OldName.java', 'src/main/java/NewName.java')")
    public EditorResult moveFile(@AgentDoc("The source path relative to the project root") String sourcePath,
            @AgentDoc("The target path relative to the project root") String targetPath) throws IOException {
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
        return new EditorResult(true, "File moved successfully from " + sourcePath + " to " + targetPath, null);
    }

    @AgentDoc(value = "Copies a file to a target path within the project sandbox.", examples = "fileEditorService.copyFile('src/main/resources/template.toml', 'src/main/resources/custom.toml')")
    public EditorResult copyFile(@AgentDoc("The source file path relative to the project root") String sourcePath,
            @AgentDoc("The target file path relative to the project root") String targetPath) throws IOException {
        Path source = projectService.resolvePath(sourcePath);
        Path target = projectService.resolvePath(targetPath);
        EditorResult validationError = validateRegularFile(source, sourcePath, "Cannot copy directory: ");
        if (validationError != null)
            return validationError;
        PathUtils.createParentDirectories(target);
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        log.info("Copied file from {} to {}", sourcePath, targetPath);
        eventPublisher.publishEvent(new FileChangedEvent(targetPath));
        return new EditorResult(true, "File copied successfully from " + sourcePath + " to " + targetPath, null);
    }

    @AgentDoc(value = "Creates a directory and any nonexistent parent directories within the project sandbox.", examples = "fileEditorService.mkdirs('src/main/java/org/roxycode/newpkg')")
    public EditorResult mkdirs(@AgentDoc("The directory path relative to the project root") String relativePath)
            throws IOException {
        Path path = projectService.resolvePath(relativePath);
        if (Files.exists(path) && !Files.isDirectory(path)) {
            return new EditorResult(false, "", "A regular file already exists at path: " + relativePath);
        }
        Files.createDirectories(path);
        log.info("Created directory: {} at {}", relativePath, path.toAbsolutePath());
        eventPublisher.publishEvent(new FileChangedEvent(relativePath));
        return new EditorResult(true, "Directory created successfully: " + relativePath, null);
    }

    private static EditorResult validateRegularFile(Path path, String relativePath) {
        return validateRegularFile(path, relativePath, "Path is a directory: ");
    }

    private static EditorResult validateRegularFile(Path path, String relativePath, String directoryErrorPrefix) {
        if (!Files.exists(path)) {
            return new EditorResult(false, "", "File does not exist: " + relativePath);
        }
        return Files.isDirectory(path) ? new EditorResult(false, "", directoryErrorPrefix + relativePath) : null;
    }

    private EditorResult applyReplacement(Path path, String relativePath, String content, int start, int end,
            String replacementBlock) throws IOException {
        Files.writeString(path, content.substring(0, start) + replacementBlock + content.substring(end));
        return completeFileMutation(path, relativePath, "Block replaced successfully in: " + relativePath);
    }

    private EditorResult completeFileMutation(Path path, String relativePath, String message) throws IOException {
        log.info("{} ({} bytes) at {}", message, Files.size(path), path.toAbsolutePath());
        eventPublisher.publishEvent(new FileChangedEvent(relativePath));
        return new EditorResult(true, message, null);
    }

    private static EditorResult nonUniqueMatchResult(String relativePath, String content, List<Integer> matchOffsets) {
        List<Integer> matchLines = offsetsToLineNumbers(content, matchOffsets);
        return new EditorResult(false, "", "TargetBlock is not unique in file: " + relativePath + " (found "
                + matchLines.size() + " matches at lines: " + matchLines + ")");
    }

    private static List<Integer> findExactMatches(String content, String target) {
        List<Integer> offsets = new ArrayList<>();
        if (target.isEmpty())
            return offsets;
        for (int index = content.indexOf(target, 0); index != -1; index = content.indexOf(target,
                index + target.length())) {
            offsets.add(index);
        }
        return offsets;
    }

    private static List<Integer> offsetsToLineNumbers(String content, List<Integer> offsets) {
        return offsets.stream().map(offset -> lineNumberAtOffset(content, offset)).toList();
    }

    private static int lineNumberAtOffset(String content, int offset) {
        int limit = Math.max(0, Math.min(offset, content.length()));
        return 1 + StringUtils.countMatches(content.subSequence(0, limit), LF);
    }

    private String buildTargetNotFoundHint(String relativePath, String content, String targetBlock) {
        StringBuilder hint = new StringBuilder("TargetBlock not found in file: ").append(relativePath);
        List<String> fileLines = content.lines().toList();
        if (fileLines.isEmpty() || targetBlock == null || targetBlock.isBlank()) {
            return hint.append(" (file has ").append(fileLines.size()).append(" lines)").toString();
        }

        String firstTargetLine = targetBlock.lines().map(String::trim).filter(s -> !s.isEmpty()).findFirst().orElse("")
                .toLowerCase(Locale.ROOT);

        record ScoredLine(int lineNum, double score) {
        }
        List<ScoredLine> scoredLines = new ArrayList<>();
        if (!firstTargetLine.isEmpty()) {
            for (int i = 0; i < fileLines.size(); i++) {
                String lineTrimmed = fileLines.get(i).trim().toLowerCase(Locale.ROOT);
                if (lineTrimmed.isEmpty())
                    continue;
                double similarity = lineTrimmed.contains(firstTargetLine)
                        ? 1.0
                        : JARO_WINKLER.apply(firstTargetLine, lineTrimmed);
                if (similarity >= MIN_LINE_SIMILARITY) {
                    scoredLines.add(new ScoredLine(i + 1, similarity));
                }
            }
        }

        scoredLines.sort((a, b) -> Double.compare(b.score(), a.score()));
        if (!scoredLines.isEmpty()) {
            hint.append(". Closest matching lines (file has ").append(fileLines.size()).append(" lines):");
            int count = Math.min(MAX_DIAGNOSTIC_LINES, scoredLines.size());
            for (int i = 0; i < count; i++) {
                int lineNum = scoredLines.get(i).lineNum();
                hint.append(NL).append("Line ").append(lineNum).append(": ").append(fileLines.get(lineNum - 1));
            }
        } else {
            hint.append(" (no similar lines found; file has ").append(fileLines.size()).append(" lines)");
        }
        return hint.toString();
    }

    private static String normalizeLineEndings(String text, String lineSeparator) {
        return text.replace(CRLF, NL).replace(CR, NL).replace(NL, lineSeparator);
    }

    private static String stripLeadingNewlines(String text) {
        return StringUtils.stripStart(text, CRLF);
    }

    private static boolean isHorizontalWhitespaceOnly(String text) {
        return !text.isEmpty() && StringUtils.containsOnly(text, TAB_SPACE);
    }

    private static boolean startsWithHorizontalWhitespace(String text) {
        return text != null && !text.isEmpty() && (text.charAt(0) == ' ' || text.charAt(0) == (char) 9);
    }

    private static String detectLineSeparator(String rawContent, String fallbackText) {
        if (rawContent.contains(CRLF))
            return CRLF;
        if (rawContent.contains(NL))
            return NL;
        return (fallbackText != null && fallbackText.contains(CRLF)) ? CRLF : NL;
    }

    private static boolean hasTrailingLineSeparator(String text) {
        return text != null && (text.endsWith(NL) || text.endsWith(CR));
    }

    private static void writeLinesPreservingFormat(Path path, List<String> lines, String lineSeparator,
            boolean hasTrailingNewline) throws IOException {
        if (lines.isEmpty()) {
            Files.writeString(path, "");
            return;
        }
        String joined = String.join(lineSeparator, lines);
        Files.writeString(path, hasTrailingNewline ? joined + lineSeparator : joined);
    }
}
