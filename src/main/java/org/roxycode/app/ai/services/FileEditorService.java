package org.roxycode.app.ai.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
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
    private static final int SINGLE_MATCH_COUNT = 1;
    private static final char LF = '\n';
    private static final String NL = "\n";
    private static final String CRLF = "\r\n";
    private static final String CR = "\r";
    private static final String TAB_SPACE = " \t";
    private static final String WHITESPACE_REGEX = "\\s+";

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
            return new EditorResult(false, "", buildTargetNotFoundHint(relativePath, content));
        }

        BlockMatch match = findUniqueBlockMatch(SINGLE_MATCH_COUNT, relativePath, content, targetBlock,
                replacementBlock);
        if (match.errorHint() != null) {
            return new EditorResult(false, "", match.errorHint());
        }
        return applyReplacement(path, relativePath, content, match.start(), match.end(), match.replacement());
    }

    @AgentDoc(value = "Replaces multiple non-overlapping target blocks in a file atomically in a single write operation.", examples = "fileEditorService.replaceBlocks('src/Main.java', [{'target': 'old1', 'replacement': 'new1'}, {'target': 'old2', 'replacement': 'new2'}])")
    public EditorResult replaceBlocks(@AgentDoc("The file path relative to the project root") String relativePath,
            @AgentDoc("Array of maps with 'target' and 'replacement' keys") Object... edits) throws IOException {
        List<?> editList;
        if (edits != null && edits.length == 1 && edits[0] instanceof List<?> list) {
            editList = list;
        } else if (edits != null) {
            editList = Arrays.asList(edits);
        } else {
            editList = null;
        }
        return replaceBlocksInternal(relativePath, editList);
    }

    private EditorResult replaceBlocksInternal(String relativePath, List<?> edits) throws IOException {
        Path path = projectService.resolvePath(relativePath);
        EditorResult validationError = validateRegularFile(path, relativePath);
        if (validationError != null) {
            return validationError;
        }
        if (edits == null || edits.isEmpty()) {
            return new EditorResult(false, "", "No edits provided for file: " + relativePath);
        }

        String content = Files.readString(path);
        List<BlockMatch> matches = new ArrayList<>(edits.size());

        for (int i = 0; i < edits.size(); i++) {
            int editNum = i + 1;
            Object rawEdit = edits.get(i);
            if (!(rawEdit instanceof Map<?, ?> editMap)) {
                return new EditorResult(false, "",
                        "Edit #" + editNum + " failed: Edit entry must be a map with 'target' and 'replacement' keys");
            }
            Object targetObj = editMap.get("target");
            Object replacementObj = editMap.get("replacement");
            String targetBlock = targetObj instanceof String s ? s : (targetObj != null ? targetObj.toString() : null);
            String replacementBlock = replacementObj instanceof String s
                    ? s
                    : (replacementObj != null ? replacementObj.toString() : null);

            if (targetBlock == null || targetBlock.isBlank()) {
                return new EditorResult(false, "",
                        "Edit #" + editNum + " failed: " + buildTargetNotFoundHint(relativePath, content));
            }
            if (replacementBlock == null) {
                return new EditorResult(false, "",
                        "Edit #" + editNum + " failed: 'replacement' must not be null in file: " + relativePath);
            }

            BlockMatch match = findUniqueBlockMatch(editNum, relativePath, content, targetBlock, replacementBlock);
            if (match.errorHint() != null) {
                return new EditorResult(false, "", "Edit #" + editNum + " failed: " + match.errorHint());
            }

            for (BlockMatch existing : matches) {
                if (match.start() < existing.end() && existing.start() < match.end()) {
                    return new EditorResult(false, "", "Edit #" + editNum + " failed: Target block overlaps with Edit #"
                            + existing.editIndex() + " in file: " + relativePath);
                }
            }
            matches.add(match);
        }

        matches.sort(Comparator.comparingInt(BlockMatch::start).reversed());
        StringBuilder updated = new StringBuilder(content);
        for (BlockMatch match : matches) {
            updated.replace(match.start(), match.end(), match.replacement());
        }
        Files.writeString(path, updated.toString());
        return completeFileMutation(path, relativePath,
                "Replaced " + matches.size() + " blocks successfully in: " + relativePath);
    }

    private record BlockMatch(int editIndex, int start, int end, String replacement, String errorHint) {
    }

    private BlockMatch findUniqueBlockMatch(int editIndex, String relativePath, String content, String targetBlock,
            String replacementBlock) {
        String lineSeparator = detectLineSeparator(content, replacementBlock);
        String normalizedTarget = normalizeLineEndings(targetBlock, lineSeparator);
        String normalizedReplacement = replacementBlock == null
                ? ""
                : normalizeLineEndings(replacementBlock, lineSeparator);

        List<Integer> exactMatchOffsets = findExactMatches(content, normalizedTarget);
        if (exactMatchOffsets.size() == SINGLE_MATCH_COUNT) {
            int start = exactMatchOffsets.get(0);
            return new BlockMatch(editIndex, start, start + normalizedTarget.length(), normalizedReplacement, null);
        }
        if (exactMatchOffsets.size() > SINGLE_MATCH_COUNT) {
            return new BlockMatch(editIndex, -1, -1, null,
                    formatNonUniqueMatchError(relativePath, content, exactMatchOffsets));
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
            return new BlockMatch(editIndex, -1, -1, null, buildTargetNotFoundHint(relativePath, content));
        }
        if (regexMatches.size() > SINGLE_MATCH_COUNT) {
            return new BlockMatch(editIndex, -1, -1, null,
                    formatNonUniqueMatchError(relativePath, content, regexMatches.stream().map(m -> m[0]).toList()));
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
        return new BlockMatch(editIndex, start, end, normalizedReplacement, null);
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
            lines.addAll(startLine - 1, splitLines(replacementText));
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
            lines.addAll(lineNumber - 1, splitLines(content));
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

    private static String formatNonUniqueMatchError(String relativePath, String content, List<Integer> matchOffsets) {
        List<Integer> matchLines = offsetsToLineNumbers(content, matchOffsets);
        return "TargetBlock is not unique in file: " + relativePath + " (found " + matchLines.size()
                + " matches at lines: " + matchLines + ")";
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

    private static String buildTargetNotFoundHint(String relativePath, String content) {
        long lineCount = 0;
        if (!content.isEmpty()) {
            lineCount = content.lines().count();
        }
        return "TargetBlock not found in file: " + relativePath + " (file has " + lineCount + " lines)";
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
        return text != null && !text.isEmpty() && (text.charAt(0) == ' ' || text.charAt(0) == '\t');
    }

    private static String detectLineSeparator(String rawContent, String fallbackText) {
        if (rawContent.contains(CRLF))
            return CRLF;
        if (rawContent.contains(NL))
            return NL;
        return (fallbackText != null && fallbackText.contains(CRLF)) ? CRLF : NL;
    }

    static List<String> splitLines(String content) {
        if (content == null || content.isEmpty()) {
            return List.of("");
        }
        List<String> lines = content.lines().toList();
        return lines.stream().allMatch(String::isEmpty) ? List.of(content.split("\\R", -1)) : lines;
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
