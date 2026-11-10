/**
 * File Editor Service.
 */
package org.roxycode.app.ai.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;

@AgentService(value = "fileEditorService", phases = {"CODE"})
@AgentDoc("Provides safe, ergonomic file editing capabilities for the AI agent with phase protection and security sandboxing.")
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class FileEditorService {

    private static final Logger log = LoggerFactory.getLogger(FileEditorService.class);
    private static final int SINGLE_MATCH_COUNT = 1;
    private static final int MAX_OUTLINE_HINT_SYMBOLS = 25;
    private static final int NEAREST_CONTEXT_PADDING = 5;
    private static final int MAX_NEAREST_SNIPPET_LINES = 15;
    private static final int MIN_TOKEN_LENGTH = 3;
    private static final int EXACT_LINE_MATCH_WEIGHT = 10;
    private static final int CONTAINED_LINE_MATCH_WEIGHT = 5;
    private static final int TOKEN_MATCH_WEIGHT = 2;
    private static final int MIN_ANCHOR_SCORE = 2;
    private static final Pattern IDENTIFIER_TOKEN_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{2,}");
    private static final char LF = '\n';
    private static final String NL = "\n";
    private static final String CRLF = "\r\n";
    private static final String CR = "\r";
    private static final String TAB_SPACE = " \t";
    private static final String WHITESPACE_REGEX = "\s+";

    private final ProjectService projectService;
    private final ApplicationEventPublisher eventPublisher;
    private final SymbolService symbolService;

    public FileEditorService(ProjectService projectService, ApplicationEventPublisher eventPublisher) {
        this(projectService, eventPublisher, null);
    }

    @Autowired
    public FileEditorService(ProjectService projectService, ApplicationEventPublisher eventPublisher,
            @Nullable SymbolService symbolService) {
        this.projectService = projectService;
        this.eventPublisher = eventPublisher;
        this.symbolService = symbolService;
    }

    @AgentDoc(value = "Writes content to a file, overwriting it if it exists.", examples = "fileEditorService.writeFile('src/main/resources/config.properties', 'key=value')")
    /**
     * Writes the given content to a file. Overwrites the file if it already exists.
     * 
     * @param relativePath
     *            the file path relative to the project root
     * @param content
     *            the content to write
     * @return the editor result
     */
    public EditorResult writeFile(@AgentDoc("The file path relative to the project root") String relativePath,
            @AgentDoc("The full content to write to the file") String content) throws IOException {
        Path path = projectService.resolvePath(relativePath);
        boolean existed = Files.exists(path);
        if (existed && Files.isDirectory(path)) {
            return new EditorResult(false, "", "Path is a directory: " + relativePath);
        }
        PathUtils.createParentDirectories(path);
        Files.writeString(path, content);
        return completeFileMutation(path, relativePath, "File written successfully: " + relativePath,
                FileChangedEvent.ChangeType.ofExisted(existed));
    }

    @AgentDoc(value = "Replaces a unique block of text with new content.", examples = "fileEditorService.replaceBlock('src/Main.java', '// TODO: implement', 'System.out.println('Hello');')")
    /**
     * Replaces a unique block of text in a file.
     * 
     * @param relativePath
     *            the file path relative to the project root
     * @param targetBlock
     *            the exact block of text to replace
     * @param replacementBlock
     *            the new content
     * @return the editor result
     */
    public EditorResult replaceBlock(@AgentDoc("The file path relative to the project root") String relativePath,
            @AgentDoc("The existing text block to find and replace") String targetBlock,
            @AgentDoc("The replacement text block") String replacementBlock) throws IOException {
        Path path = projectService.resolvePath(relativePath);
        EditorResult validationError = validateRegularFile(path, relativePath);
        if (validationError != null) {
            return validationError;
        }

        String content = Files.readString(path);
        if (targetBlock == null || targetBlock.isBlank()) {
            return new EditorResult(false, "", buildTargetNotFoundHint(relativePath, content, targetBlock));
        }

        BlockMatch match = findUniqueBlockMatch(SINGLE_MATCH_COUNT, relativePath, content, targetBlock,
                replacementBlock);
        if (match.errorHint() != null) {
            return new EditorResult(false, "", match.errorHint());
        }
        Files.writeString(path,
                content.substring(0, match.start()) + match.replacement() + content.substring(match.end()));
        return completeFileMutation(path, relativePath, "Block replaced successfully in: " + relativePath);
    }

    @AgentDoc(value = "Replaces multiple non-overlapping target blocks in a file atomically in a single write operation.", examples = "fileEditorService.replaceBlocks('src/Main.java', [{'target': 'old1', 'replacement': 'new1'}, {'target': 'old2', 'replacement': 'new2'}])")
    public EditorResult replaceBlocks(@AgentDoc("The file path relative to the project root") String relativePath,
            @AgentDoc("Array of maps with 'target' and 'replacement' keys") Object... edits) throws IOException {
        List<?> editList = List.of();
        if (edits != null && edits.length == 1 && edits[0] instanceof List<?> list) {
            editList = list;
        } else if (edits != null) {
            editList = Arrays.asList(edits);
        }
        return replaceBlocksInternal(relativePath, editList);
    }

    @AgentDoc(value = "Replaces a unique symbol definition (method, function, class, etc.) in a file using Universal Ctags outline boundaries.", examples = {
            "fileEditorService.replaceSymbol('src/Main.java', 'calculate', '    public int calculate(int x) {\n        return x * 2;\n    }')",
            "fileEditorService.replaceSymbol('src/Main.java', 'Main.calculate', '    public int calculate(int x) {\n        return x * 2;\n    }')"})
    public EditorResult replaceSymbol(@AgentDoc("The file path relative to the project root") String relativePath,
            @AgentDoc("The symbol name to replace (e.g., 'methodName' or 'ClassName.methodName')") String symbolName,
            @AgentDoc("The replacement symbol implementation") String replacement) throws IOException {
        Path path = projectService.resolvePath(relativePath);
        EditorResult validationError = validateRegularFile(path, relativePath);
        if (validationError != null) {
            return validationError;
        }
        if (symbolName == null || symbolName.isBlank()) {
            return new EditorResult(false, "", "Symbol name must not be blank in file: " + relativePath);
        }
        if (replacement == null) {
            return new EditorResult(false, "", "Replacement must not be null in file: " + relativePath);
        }
        if (symbolService == null) {
            return new EditorResult(false, "", "SymbolService is not available for file: " + relativePath);
        }

        List<SymbolInfo> outline = symbolService.getFileOutline(relativePath);
        List<SymbolInfo> matching = SymbolService.filterMatchingSymbols(outline, symbolName);
        if (matching.isEmpty()) {
            return new EditorResult(false, "", formatSymbolNotFoundHint(relativePath, symbolName, outline));
        }
        if (matching.size() > SINGLE_MATCH_COUNT) {
            return new EditorResult(false, "", formatAmbiguousSymbolHint(relativePath, symbolName, matching));
        }

        SymbolInfo targetSymbol = matching.get(0);
        return mutateLines(relativePath, replacement, false, lines -> {
            int startLine = targetSymbol.startLine();
            if (replacementStartsWithAnnotationOrComment(replacement)) {
                startLine = SymbolService.adjustStartLineForPrecedingContext(lines, startLine);
            }
            int endLine = Math.max(startLine, Math.min(targetSymbol.endLine(), lines.size()));
            if (startLine < 1 || startLine > lines.size() || endLine < startLine) {
                return "Invalid symbol line range: " + startLine + " to " + endLine + " in file: " + relativePath;
            }
            lines.subList(startLine - 1, endLine).clear();
            lines.addAll(startLine - 1, splitLines(replacement));
            return null;
        }, "Symbol '" + symbolName + "' replaced successfully in: " + relativePath);
    }

    @AgentDoc(value = "Reverts a file to its git HEAD state if tracked, or deletes it if it is an untracked newly created file.", examples = "fileEditorService.revertFile('src/Main.java')")
    public EditorResult revertFile(@AgentDoc("The file path relative to the project root") String relativePath)
            throws IOException {
        Path path = projectService.resolvePath(relativePath);
        if (Files.exists(path) && Files.isDirectory(path)) {
            return new EditorResult(false, "", "Cannot revert directory: " + relativePath);
        }

        String normalizedPath = PathUtils.normalizeRelativePath(relativePath);
        Path projectRoot = projectService.getCurrentProjectRoot();
        if (projectRoot != null && Files.isDirectory(projectRoot)) {
            ProcessResult lsResult = ProcessRunner.run(List.of("git", "ls-files", "--error-unmatch", normalizedPath),
                    projectRoot, true);
            if (lsResult.isSuccess()) {
                ProcessResult checkoutResult = ProcessRunner
                        .run(List.of("git", "checkout", "HEAD", "--", normalizedPath), projectRoot, true);
                if (checkoutResult.isSuccess()) {
                    log.info("Reverted tracked file to HEAD: {}", relativePath);
                    eventPublisher
                            .publishEvent(new FileChangedEvent(relativePath, FileChangedEvent.ChangeType.MODIFIED));
                    return new EditorResult(true, "File reverted to HEAD: " + relativePath, null);
                }
                return new EditorResult(false, "",
                        "Failed to revert tracked file " + relativePath + ": " + checkoutResult.output().trim());
            }
        }

        if (Files.exists(path)) {
            Files.deleteIfExists(path);
            log.info("Deleted untracked file on revert: {}", relativePath);
            eventPublisher.publishEvent(new FileChangedEvent(relativePath, FileChangedEvent.ChangeType.DELETED));
            return new EditorResult(true, "Untracked file deleted: " + relativePath, null);
        }

        return new EditorResult(false, "", "File does not exist on disk or in git HEAD: " + relativePath);
    }

    private static boolean replacementStartsWithAnnotationOrComment(String replacement) {
        if (replacement == null || replacement.isBlank()) {
            return false;
        }
        for (String line : replacement.lines().toList()) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return SymbolService.isAnnotationOrCommentLine(trimmed);
            }
        }
        return false;
    }

    private static String formatSymbolNotFoundHint(String relativePath, String symbolName, List<SymbolInfo> outline) {
        StringBuilder sb = new StringBuilder();
        sb.append("Symbol '").append(symbolName).append("' not found in file: ").append(relativePath);
        if (outline == null || outline.isEmpty()) {
            sb.append(" (no symbols detected in file outline)");
            return sb.toString();
        }
        sb.append(". Available symbols in ").append(relativePath).append(": ");
        int limit = Math.min(outline.size(), MAX_OUTLINE_HINT_SYMBOLS);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            SymbolInfo sym = outline.get(i);
            if (sym.scope() != null && !sym.scope().isEmpty()) {
                sb.append(sym.scope()).append('.');
            }
            sb.append(sym.name()).append(" (").append(sym.kind()).append(" L").append(sym.startLine()).append('-')
                    .append(sym.endLine()).append(')');
        }
        if (outline.size() > limit) {
            sb.append(", ... (").append(outline.size() - limit).append(" more)");
        }
        return sb.toString();
    }

    private static String formatAmbiguousSymbolHint(String relativePath, String symbolName, List<SymbolInfo> matching) {
        StringBuilder sb = new StringBuilder();
        sb.append("Symbol '").append(symbolName).append("' is ambiguous in file: ").append(relativePath)
                .append(" (found ").append(matching.size())
                .append(" matches). Use replaceBlock or replaceLines for the specific overload: ");
        for (int i = 0; i < matching.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            SymbolInfo sym = matching.get(i);
            sb.append("L").append(sym.startLine()).append('-').append(sym.endLine());
            if (sym.signature() != null && !sym.signature().isBlank()) {
                sb.append(' ').append(sym.signature().trim());
            } else {
                sb.append(' ').append(sym.name()).append(" (").append(sym.kind()).append(')');
            }
        }
        return sb.toString();
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
            EditorResult editError = resolveAndValidateEditEntry(editNum, edits.get(i), relativePath, content, matches);
            if (editError != null) {
                return editError;
            }
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

    private EditorResult resolveAndValidateEditEntry(int editNum, Object rawEdit, String relativePath, String content,
            List<BlockMatch> matches) {
        if (!(rawEdit instanceof Map<?, ?> editMap)) {
            return new EditorResult(false, "",
                    "Edit #" + editNum + " failed: Edit entry must be a map with 'target' and 'replacement' keys");
        }
        String targetBlock = Objects.toString(editMap.get("target"), null);
        String replacementBlock = Objects.toString(editMap.get("replacement"), null);

        if (targetBlock == null || targetBlock.isBlank()) {
            return new EditorResult(false, "",
                    "Edit #" + editNum + " failed: " + buildTargetNotFoundHint(relativePath, content, targetBlock));
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
        return null;
    }

    private record BlockMatch(int editIndex, int start, int end, String replacement, String errorHint) {
    }

    private BlockMatch findUniqueBlockMatch(int editIndex, String relativePath, String content, String targetBlock,
            String replacementBlock) {
        String lineSeparator = detectLineSeparator(content, replacementBlock);
        String normalizedTarget = normalizeLineEndings(targetBlock, lineSeparator);
        String normalizedReplacement = normalizeLineEndings(StringUtils.defaultString(replacementBlock), lineSeparator);

        List<Integer> exactMatchOffsets = findExactMatches(content, normalizedTarget);
        if (exactMatchOffsets.size() == SINGLE_MATCH_COUNT) {
            int start = exactMatchOffsets.get(0);
            return new BlockMatch(editIndex, start, start + normalizedTarget.length(), normalizedReplacement, null);
        }
        if (exactMatchOffsets.size() > SINGLE_MATCH_COUNT) {
            return new BlockMatch(editIndex, -1, -1, null,
                    formatNonUniqueMatchError(relativePath, content, exactMatchOffsets));
        }

        List<int[]> regexMatches = findWhitespaceTolerantMatches(content, normalizedTarget);
        if (regexMatches.isEmpty()) {
            return new BlockMatch(editIndex, -1, -1, null, buildTargetNotFoundHint(relativePath, content, targetBlock));
        }
        if (regexMatches.size() > SINGLE_MATCH_COUNT) {
            return new BlockMatch(editIndex, -1, -1, null,
                    formatNonUniqueMatchError(relativePath, content, regexMatches.stream().map(m -> m[0]).toList()));
        }

        int start = adjustMatchStartForLeadingIndent(content, regexMatches.get(0)[0], normalizedTarget,
                normalizedReplacement);
        int end = regexMatches.get(0)[1];
        return new BlockMatch(editIndex, start, end, normalizedReplacement, null);
    }

    private static List<int[]> findWhitespaceTolerantMatches(String content, String normalizedTarget) {
        String[] parts = normalizedTarget.trim().split(WHITESPACE_REGEX);
        StringBuilder sb = new StringBuilder("(?s)");
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(Pattern.quote(parts[i]));
                if (i < parts.length - 1) {
                    sb.append(WHITESPACE_REGEX);
                }
            }
        }
        Matcher matcher = Pattern.compile(sb.toString()).matcher(content);
        List<int[]> regexMatches = new ArrayList<>();
        while (matcher.find()) {
            regexMatches.add(new int[]{matcher.start(), matcher.end()});
        }
        return regexMatches;
    }

    private static int adjustMatchStartForLeadingIndent(String content, int matchStart, String normalizedTarget,
            String normalizedReplacement) {
        int lineStart = 0;
        if (matchStart > 0) {
            lineStart = content.lastIndexOf(LF, matchStart - 1) + 1;
        }
        String linePrefix = content.substring(lineStart, matchStart);
        if (isHorizontalWhitespaceOnly(linePrefix)
                && (startsWithHorizontalWhitespace(StringUtils.stripStart(normalizedTarget, CRLF))
                        || startsWithHorizontalWhitespace(normalizedReplacement))) {
            return lineStart;
        }
        return matchStart;
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
        if (validationError != null) {
            return validationError;
        }

        String rawContent = Files.readString(path);
        String lineSeparator = detectLineSeparator(rawContent, inputContent);
        boolean hasTrailingNewline;
        if (fallbackTrailingToInputWhenEmpty && rawContent.isEmpty()) {
            hasTrailingNewline = hasTrailingLineSeparator(inputContent);
        } else {
            hasTrailingNewline = hasTrailingLineSeparator(rawContent);
        }
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
        if (validationError != null) {
            return validationError;
        }
        Files.delete(path);
        log.info("Deleted file: {} at {}", relativePath, path.toAbsolutePath());
        eventPublisher.publishEvent(new FileChangedEvent(relativePath, FileChangedEvent.ChangeType.DELETED));
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
        boolean targetExisted = Files.exists(target);
        PathUtils.createParentDirectories(target);
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        log.info("Moved file from {} to {}", sourcePath, targetPath);
        eventPublisher.publishEvent(new FileChangedEvent(sourcePath, FileChangedEvent.ChangeType.DELETED));
        eventPublisher
                .publishEvent(new FileChangedEvent(targetPath, FileChangedEvent.ChangeType.ofExisted(targetExisted)));
        return new EditorResult(true, "File moved successfully from " + sourcePath + " to " + targetPath, null);
    }

    @AgentDoc(value = "Copies a file to a target path within the project sandbox.", examples = "fileEditorService.copyFile('src/main/resources/template.toml', 'src/main/resources/custom.toml')")
    public EditorResult copyFile(@AgentDoc("The source file path relative to the project root") String sourcePath,
            @AgentDoc("The target file path relative to the project root") String targetPath) throws IOException {
        Path source = projectService.resolvePath(sourcePath);
        Path target = projectService.resolvePath(targetPath);
        EditorResult validationError = validateRegularFile(source, sourcePath, "Cannot copy directory: ");
        if (validationError != null) {
            return validationError;
        }
        boolean targetExisted = Files.exists(target);
        PathUtils.createParentDirectories(target);
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        log.info("Copied file from {} to {}", sourcePath, targetPath);
        eventPublisher
                .publishEvent(new FileChangedEvent(targetPath, FileChangedEvent.ChangeType.ofExisted(targetExisted)));
        return new EditorResult(true, "File copied successfully from " + sourcePath + " to " + targetPath, null);
    }

    @AgentDoc(value = "Creates a directory and any nonexistent parent directories within the project sandbox.", examples = "fileEditorService.mkdirs('src/main/java/org/roxycode/newpkg')")
    public EditorResult mkdirs(@AgentDoc("The directory path relative to the project root") String relativePath)
            throws IOException {
        Path path = projectService.resolvePath(relativePath);
        boolean existed = Files.exists(path);
        if (existed && !Files.isDirectory(path)) {
            return new EditorResult(false, "", "A regular file already exists at path: " + relativePath);
        }
        Files.createDirectories(path);
        log.info("Created directory: {} at {}", relativePath, path.toAbsolutePath());
        eventPublisher.publishEvent(new FileChangedEvent(relativePath, FileChangedEvent.ChangeType.ofExisted(existed)));
        return new EditorResult(true, "Directory created successfully: " + relativePath, null);
    }

    private static EditorResult validateRegularFile(Path path, String relativePath) {
        return validateRegularFile(path, relativePath, "Path is a directory: ");
    }

    private static EditorResult validateRegularFile(Path path, String relativePath, String directoryErrorPrefix) {
        if (!Files.exists(path)) {
            return new EditorResult(false, "", "File does not exist: " + relativePath);
        }
        if (Files.isDirectory(path)) {
            return new EditorResult(false, "", directoryErrorPrefix + relativePath);
        }
        return null;
    }

    private EditorResult completeFileMutation(Path path, String relativePath, String message) throws IOException {
        return completeFileMutation(path, relativePath, message, FileChangedEvent.ChangeType.MODIFIED);
    }

    private EditorResult completeFileMutation(Path path, String relativePath, String message,
            FileChangedEvent.ChangeType changeType) throws IOException {
        log.info("{} ({} bytes) at {}", message, Files.size(path), path.toAbsolutePath());
        eventPublisher.publishEvent(new FileChangedEvent(relativePath, changeType));
        return new EditorResult(true, message, null);
    }

    private static String formatNonUniqueMatchError(String relativePath, String content, List<Integer> matchOffsets) {
        List<Integer> matchLines = matchOffsets.stream().map(offset -> lineNumberAtOffset(content, offset)).toList();
        return "TargetBlock is not unique in file: " + relativePath + " (found " + matchLines.size()
                + " matches at lines: " + matchLines + ")";
    }

    private static List<Integer> findExactMatches(String content, String target) {
        List<Integer> offsets = new ArrayList<>();
        if (target.isEmpty()) {
            return offsets;
        }
        for (int index = content.indexOf(target, 0); index != -1; index = content.indexOf(target,
                index + target.length())) {
            offsets.add(index);
        }
        return offsets;
    }

    private static int lineNumberAtOffset(String content, int offset) {
        int limit = Math.max(0, Math.min(offset, content.length()));
        return 1 + StringUtils.countMatches(content.subSequence(0, limit), LF);
    }

    static String buildTargetNotFoundHint(String relativePath, String content, String targetBlock) {
        List<String> fileLines = List.of();
        if (!content.isEmpty()) {
            fileLines = content.lines().toList();
        }
        long lineCount = fileLines.size();
        String baseHint = "TargetBlock not found in file: " + relativePath + " (file has " + lineCount + " lines)";
        if (fileLines.isEmpty() || targetBlock == null || targetBlock.isBlank()) {
            return baseHint;
        }

        List<String> targetLines = new ArrayList<>();
        for (String line : targetBlock.lines().toList()) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                targetLines.add(trimmed);
            }
        }
        Set<String> targetTokens = extractIdentifierTokens(targetBlock);
        int bestLine = findBestMatchAnchorLine(fileLines, targetLines, targetTokens);
        if (bestLine <= 0) {
            return baseHint;
        }

        int snippetStart = Math.max(1, bestLine - NEAREST_CONTEXT_PADDING);
        int targetSpan = Math.max(0, targetLines.size() - 1);
        int rawEnd = Math.min(fileLines.size(), bestLine + targetSpan + NEAREST_CONTEXT_PADDING);
        int snippetEnd = Math.min(rawEnd, snippetStart + MAX_NEAREST_SNIPPET_LINES - 1);
        String snippet = FileReadService.formatNumberedLines(fileLines, snippetStart, snippetEnd);
        return baseHint + "\nNearest matching lines in " + relativePath + " (lines " + snippetStart + "-" + snippetEnd
                + "):\n" + snippet;
    }

    private static Set<String> extractIdentifierTokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = IDENTIFIER_TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= MIN_TOKEN_LENGTH) {
                tokens.add(token.toLowerCase(Locale.ROOT));
            }
        }
        return tokens;
    }

    /**
     * Finds the best starting line in the file that matches the target block based on weighted anchor scoring.
     * 
     * @param fileLines
     *            the lines of the file to search
     * @param targetLines
     *            the lines of the block being searched for
     * @param targetTokens
     *            the identifier tokens extracted from the target block
     * @return the 1-based line number of the best match, or -1 if no match reaches MIN_ANCHOR_SCORE
     */
    private static int findBestMatchAnchorLine(List<String> fileLines, List<String> targetLines,
            Set<String> targetTokens) {
        if (targetLines.isEmpty() && targetTokens.isEmpty()) {
            return -1;
        }
        int windowSize = Math.max(1, Math.min(targetLines.size(), MAX_NEAREST_SNIPPET_LINES));
        int bestScore = 0;
        int bestLineNumber = -1;

        for (int i = 0; i < fileLines.size(); i++) {
            int score = scoreCandidateWindow(fileLines, i, windowSize, targetLines, targetTokens);
            if (score > bestScore) {
                bestScore = score;
                bestLineNumber = i + 1;
            }
        }

        if (bestScore < MIN_ANCHOR_SCORE) {
            return -1;
        }
        return bestLineNumber;
    }

    /**
     * Scores a candidate window in the file against the target lines and tokens. Heuristics: - Exact line matches are
     * heavily weighted (EXACT_LINE_MATCH_WEIGHT). - Contained line matches (partial matches) are moderately weighted
     * (CONTAINED_LINE_MATCH_WEIGHT). - Unique identifier token matches provide a baseline score (TOKEN_MATCH_WEIGHT).
     * 
     * @param fileLines
     *            the lines of the file
     * @param startIndex
     *            the starting index (0-based) of the window
     * @param windowSize
     *            the size of the window to check
     * @param targetLines
     *            the lines to match against
     * @param targetTokens
     *            the tokens to match against
     * @return the total score for this window
     */
    private static int scoreCandidateWindow(List<String> fileLines, int startIndex, int windowSize,
            List<String> targetLines, Set<String> targetTokens) {
        int endIndex = Math.min(fileLines.size(), startIndex + windowSize);
        int score = 0;
        Set<String> matchedTokens = new LinkedHashSet<>();

        for (int j = startIndex; j < endIndex; j++) {
            String fileLineTrimmed = fileLines.get(j).trim();
            if (fileLineTrimmed.isEmpty()) {
                continue;
            }
            for (String targetLine : targetLines) {
                if (fileLineTrimmed.equals(targetLine) && targetLine.length() >= MIN_TOKEN_LENGTH) {
                    score += EXACT_LINE_MATCH_WEIGHT;
                } else if (targetLine.length() >= MIN_TOKEN_LENGTH
                        && (fileLineTrimmed.contains(targetLine) || targetLine.contains(fileLineTrimmed))) {
                    score += CONTAINED_LINE_MATCH_WEIGHT;
                }
            }
            String lowerLine = fileLineTrimmed.toLowerCase(Locale.ROOT);
            for (String token : targetTokens) {
                if (lowerLine.contains(token)) {
                    matchedTokens.add(token);
                }
            }
        }
        score += matchedTokens.size() * TOKEN_MATCH_WEIGHT;
        return score;
    }

    private static String normalizeLineEndings(String text, String lineSeparator) {
        return text.replace(CRLF, NL).replace(CR, NL).replace(NL, lineSeparator);
    }

    private static boolean isHorizontalWhitespaceOnly(String text) {
        return !text.isEmpty() && StringUtils.containsOnly(text, TAB_SPACE);
    }

    private static boolean startsWithHorizontalWhitespace(String text) {
        return text != null && !text.isEmpty() && (text.charAt(0) == ' ' || text.charAt(0) == '\t');
    }

    private static String detectLineSeparator(String rawContent, String fallbackText) {
        if (rawContent.contains(CRLF)) {
            return CRLF;
        }
        if (rawContent.contains(NL)) {
            return NL;
        }
        if (fallbackText != null && fallbackText.contains(CRLF)) {
            return CRLF;
        }
        return NL;
    }

    static List<String> splitLines(String content) {
        return org.roxycode.app.util.PathUtils.splitLines(content);
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
        if (hasTrailingNewline) {
            Files.writeString(path, joined + lineSeparator);
        } else {
            Files.writeString(path, joined);
        }
    }
}
