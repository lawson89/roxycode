package org.roxycode.app.ai.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.ai.services.CtagsRunner.CtagsEntry;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AgentService(value = "symbolService", phases = {"*"})
@AgentDoc("Provides structural symbol outlines, symbol search, and symbol body reading using Universal Ctags.")
public class SymbolService {

    private static final Logger log = LoggerFactory.getLogger(SymbolService.class);

    private static final String DOC_FILE_PATH = "The file path relative to the project root";
    private static final String DOC_SYMBOL_NAME = "The symbol name (e.g., 'myMethod' or 'MyClass.myMethod')";
    private static final String DOC_SYMBOL_NAMES = "Array of symbol names to read from the file";
    private static final String ERROR_PREFIX = "Error: ";
    private static final String CANCELLED_MSG = "Symbol operation cancelled";
    private static final List<String> CTAGS_OUTLINE_FLAGS = List.of("--fields=+neKSs");

    static final int MAX_PRECEDING_CONTEXT_LINES = 5;
    static final int MAX_FIND_SYMBOL_FILES = 25;
    private static final int SINGLE_FILE_COUNT = 1;
    private static final int MIN_LINE_NUMBER = 1;
    private static final int NOT_FOUND_INDEX = -1;
    private static final char DOT_CHAR = '.';
    private static final char BACKSLASH_CHAR = 92;
    private static final char SLASH_CHAR = '/';
    private static final String DOUBLE_NEWLINE = String.valueOf((char) 10) + (char) 10;

    static final Set<String> IGNORED_TAG_KINDS = CtagsRunner.IGNORED_TAG_KINDS;
    private static final Pattern SIMPLE_IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z0-9_$]+$");

    private record LineRange(int startLine, int endLine) {
    }

    private final ProjectService projectService;
    private final GrepService grepService;
    private final ObjectMapper objectMapper;
    private CtagsRunner ctagsRunner = CtagsRunner.DEFAULT;

    public SymbolService(ProjectService projectService, GrepService grepService, ObjectMapper objectMapper) {
        this.projectService = projectService;
        this.grepService = grepService;
        if (objectMapper != null) {
            this.objectMapper = objectMapper;
        } else {
            this.objectMapper = AiConfig.SHARED_OBJECT_MAPPER;
        }
    }

    void setCtagsRunner(CtagsRunner ctagsRunner) {
        this.ctagsRunner = ctagsRunner;
    }

    @AgentDoc(value = "Returns a structural outline of symbols in a file (works on both source and test files), sorted by startLine.", examples = "symbolService.getFileOutline('src/main/java/App.java')")
    public List<SymbolInfo> getFileOutline(@AgentDoc(DOC_FILE_PATH) String relativePath) {
        ExceptionUtils.checkInterrupted(CANCELLED_MSG);
        Path filePath = resolveRegularFile(relativePath);
        return getFileOutlineWithLines(filePath, relativePath, readLinesSafely(filePath).size());
    }

    @AgentDoc(value = "Reads the numbered lines of all matching symbols (including overloads or 'Scope.symbolName' and immediately preceding annotations/comments) in a file.", examples = {
            "symbolService.readSymbol('src/main/java/App.java', 'main')",
            "symbolService.readSymbol('src/main/java/App.java', 'App.main')"})
    public String readSymbol(@AgentDoc(DOC_FILE_PATH) String relativePath, @AgentDoc(DOC_SYMBOL_NAME) String symbolName)
            throws IOException {
        ExceptionUtils.checkInterrupted(CANCELLED_MSG);
        Path filePath = resolveRegularFile(relativePath);
        if (symbolName == null || symbolName.isBlank()) {
            return formatSymbolNotFoundError(symbolName, relativePath);
        }
        List<String> fileLines = Files.readAllLines(filePath);
        List<SymbolInfo> outline = getFileOutlineWithLines(filePath, relativePath, fileLines.size());
        return extractSymbolContent(relativePath, symbolName.trim(), outline, fileLines);
    }

    @AgentDoc(value = "Reads multiple symbols from a file in a single batch call, returning a map of symbolName to numbered lines (or 'Error: ...' if not found).", examples = "symbolService.readSymbols('src/main/java/App.java', ['main', 'helper'])")
    public Map<String, String> readSymbols(@AgentDoc(DOC_FILE_PATH) String relativePath,
            @AgentDoc(DOC_SYMBOL_NAMES) String... symbolNames) {
        ExceptionUtils.checkInterrupted(CANCELLED_MSG);
        Map<String, String> results = new LinkedHashMap<>();
        if (symbolNames == null || symbolNames.length == 0) {
            return results;
        }
        try {
            Path filePath = resolveRegularFile(relativePath);
            List<String> fileLines = Files.readAllLines(filePath);
            List<SymbolInfo> outline = getFileOutlineWithLines(filePath, relativePath, fileLines.size());
            for (String rawSymbolName : symbolNames) {
                if (rawSymbolName == null || rawSymbolName.isBlank() || results.containsKey(rawSymbolName)) {
                    continue;
                }
                String content = extractSymbolContent(relativePath, rawSymbolName.trim(), outline, fileLines);
                results.put(rawSymbolName, content);
            }
        } catch (Exception e) {
            ExceptionUtils.rethrowIfCancelled(e, CANCELLED_MSG);
            String msg = e.getClass().getSimpleName();
            if (e.getMessage() != null) {
                msg = e.getMessage();
            }
            String errorMsg = ERROR_PREFIX + msg;
            if (msg.startsWith(ERROR_PREFIX)) {
                errorMsg = msg;
            }
            for (String rawSymbolName : symbolNames) {
                if (rawSymbolName != null && !rawSymbolName.isBlank()) {
                    results.putIfAbsent(rawSymbolName, errorMsg);
                }
            }
        }
        return results;
    }

    @AgentDoc(value = "Finds all symbol definitions matching symbolName across the project by narrowing candidate files with RipGrep and running Universal Ctags.", examples = {
            "symbolService.findSymbol('executeJexl')", "symbolService.findSymbol('JexlTool.executeJexl')"})
    public List<SymbolInfo> findSymbol(@AgentDoc(DOC_SYMBOL_NAME) String symbolName) {
        ExceptionUtils.checkInterrupted(CANCELLED_MSG);
        if (symbolName == null || symbolName.isBlank() || !projectService.hasActiveProject()) {
            return List.of();
        }
        String trimmedQuery = symbolName.trim();
        String unqualifiedName = extractUnqualifiedName(trimmedQuery);
        if (unqualifiedName.isEmpty()) {
            return List.of();
        }
        String searchPattern = Pattern.quote(unqualifiedName);
        if (SIMPLE_IDENTIFIER_PATTERN.matcher(unqualifiedName).matches()) {
            searchPattern = unqualifiedName;
        }
        List<String> candidateRelPaths = grepService.findMatchingFiles(searchPattern);
        if (candidateRelPaths == null || candidateRelPaths.isEmpty()) {
            return List.of();
        }

        List<Path> candidatePaths = new ArrayList<>();
        Map<Path, String> relPathByAbsPath = new LinkedHashMap<>();
        for (String relPath : candidateRelPaths) {
            if (relPath == null || relPath.isBlank()) {
                continue;
            }
            try {
                Path resolved = projectService.resolvePath(relPath).toAbsolutePath().normalize();
                if (Files.isRegularFile(resolved) && !relPathByAbsPath.containsKey(resolved)) {
                    String normalizedRel = normalizeRelativePath(resolved, relPath);
                    relPathByAbsPath.put(resolved, normalizedRel);
                    candidatePaths.add(resolved);
                    if (candidatePaths.size() >= MAX_FIND_SYMBOL_FILES) {
                        break;
                    }
                }
            } catch (Exception e) {
                ExceptionUtils.rethrowIfCancelled(e, CANCELLED_MSG);
                log.debug("Skipping candidate file {}: {}", relPath, e.getMessage());
            }
        }
        if (candidatePaths.isEmpty()) {
            return List.of();
        }

        Map<Path, List<CtagsEntry>> entriesByPath = ctagsRunner.runAndParse(candidatePaths, CTAGS_OUTLINE_FLAGS,
                objectMapper, CANCELLED_MSG);
        List<SymbolInfo> matches = new ArrayList<>();
        for (Path filePath : candidatePaths) {
            List<CtagsEntry> entriesForFile = entriesByPath.get(filePath);
            if (entriesForFile == null || entriesForFile.isEmpty()) {
                continue;
            }
            String relPath = relPathByAbsPath.getOrDefault(filePath, normalizeRelativePath(filePath, null));
            int totalLines = readLinesSafely(filePath).size();
            for (SymbolInfo symbol : finalizeSymbolsForFile(entriesForFile, relPath, totalLines)) {
                if (matchesSymbol(symbol, trimmedQuery)) {
                    matches.add(symbol);
                }
            }
        }
        matches.sort(Comparator.comparing(SymbolInfo::path).thenComparingInt(SymbolInfo::startLine)
                .thenComparingInt(SymbolInfo::endLine).thenComparing(SymbolInfo::name));
        return matches;
    }

    private List<SymbolInfo> getFileOutlineWithLines(Path filePath, String relativePath, int totalLines) {
        Map<Path, List<CtagsEntry>> entriesByPath = ctagsRunner.runAndParse(List.of(filePath), CTAGS_OUTLINE_FLAGS,
                objectMapper, CANCELLED_MSG);
        List<CtagsEntry> entries = entriesByPath.getOrDefault(filePath, List.of());
        if (entries.isEmpty()) {
            return List.of();
        }
        return finalizeSymbolsForFile(entries, normalizeRelativePath(filePath, relativePath), totalLines);
    }

    private String extractSymbolContent(String relativePath, String symbolName, List<SymbolInfo> outline,
            List<String> fileLines) {
        List<SymbolInfo> matching = new ArrayList<>();
        for (SymbolInfo symbol : outline) {
            if (matchesSymbol(symbol, symbolName)) {
                matching.add(symbol);
            }
        }
        if (matching.isEmpty()) {
            return formatSymbolNotFoundError(symbolName, relativePath);
        }

        List<LineRange> ranges = new ArrayList<>();
        int totalLines = fileLines.size();
        for (SymbolInfo symbol : matching) {
            int adjustedStart = adjustStartLineForPrecedingContext(fileLines, symbol.startLine());
            int clampedEnd = Math.max(adjustedStart, symbol.endLine());
            if (totalLines > 0) {
                clampedEnd = Math.min(clampedEnd, totalLines);
            }
            ranges.add(new LineRange(adjustedStart, clampedEnd));
        }

        List<LineRange> mergedRanges = mergeLineRanges(ranges);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mergedRanges.size(); i++) {
            if (i > 0) {
                sb.append(DOUBLE_NEWLINE);
            }
            LineRange range = mergedRanges.get(i);
            sb.append(FileReadService.formatNumberedLines(fileLines, range.startLine(), range.endLine()));
        }
        return sb.toString();
    }

    static int adjustStartLineForPrecedingContext(List<String> fileLines, int symbolStartLine) {
        if (fileLines == null || fileLines.isEmpty() || symbolStartLine <= MIN_LINE_NUMBER) {
            return Math.max(MIN_LINE_NUMBER, symbolStartLine);
        }
        int minCandidateLine = Math.max(MIN_LINE_NUMBER, symbolStartLine - MAX_PRECEDING_CONTEXT_LINES);
        int adjustedStart = Math.min(symbolStartLine, fileLines.size());
        for (int lineNum = adjustedStart - 1; lineNum >= minCandidateLine; lineNum--) {
            String trimmed = fileLines.get(lineNum - 1).trim();
            if (isAnnotationOrCommentLine(trimmed)) {
                adjustedStart = lineNum;
            } else {
                break;
            }
        }
        return adjustedStart;
    }

    static boolean isAnnotationOrCommentLine(String trimmedLine) {
        if (trimmedLine == null || trimmedLine.isEmpty()) {
            return false;
        }
        return trimmedLine.startsWith("@") || trimmedLine.startsWith("//") || trimmedLine.startsWith("/*")
                || trimmedLine.startsWith("*") || trimmedLine.startsWith("*/");
    }

    private static List<LineRange> mergeLineRanges(List<LineRange> ranges) {
        if (ranges.size() <= SINGLE_FILE_COUNT) {
            return ranges;
        }
        List<LineRange> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingInt(LineRange::startLine).thenComparingInt(LineRange::endLine));
        List<LineRange> merged = new ArrayList<>();
        LineRange current = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            LineRange next = sorted.get(i);
            if (next.startLine() <= current.endLine()) {
                current = new LineRange(current.startLine(), Math.max(current.endLine(), next.endLine()));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    static boolean matchesSymbol(SymbolInfo symbol, String query) {
        if (symbol == null || query == null || query.isBlank()) {
            return false;
        }
        String trimmed = query.trim();
        int dotIndex = trimmed.lastIndexOf(DOT_CHAR);
        if (dotIndex == NOT_FOUND_INDEX) {
            return symbol.name().equals(trimmed);
        }
        String scopePart = trimmed.substring(0, dotIndex);
        String namePart = trimmed.substring(dotIndex + 1);
        if (!symbol.name().equals(namePart) || symbol.scope().isEmpty()) {
            return false;
        }
        return symbol.scope().equals(scopePart) || symbol.scope().endsWith(DOT_CHAR + scopePart);
    }

    private static String extractUnqualifiedName(String symbolQuery) {
        int dotIndex = symbolQuery.lastIndexOf(DOT_CHAR);
        if (dotIndex == NOT_FOUND_INDEX) {
            return symbolQuery;
        }
        return symbolQuery.substring(dotIndex + 1).trim();
    }

    private static String formatSymbolNotFoundError(String symbolName, String relativePath) {
        String resolvedName = "";
        if (symbolName != null) {
            resolvedName = symbolName;
        }
        return ERROR_PREFIX + "Symbol '" + resolvedName + "' not found in " + relativePath;
    }

    private static List<SymbolInfo> finalizeSymbolsForFile(List<CtagsEntry> entries, String relPath, int totalLines) {
        List<CtagsEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(CtagsEntry::line).thenComparing(CtagsEntry::name));
        List<SymbolInfo> finalized = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            CtagsEntry current = sorted.get(i);
            int resolvedEnd = resolveEndLine(sorted, i, totalLines);
            finalized.add(new SymbolInfo(current.name(), current.kind(), relPath, current.line(), resolvedEnd,
                    current.scope(), current.signature()));
        }
        return finalized;
    }

    private static int resolveEndLine(List<CtagsEntry> sorted, int index, int totalLines) {
        CtagsEntry current = sorted.get(index);
        if (current.end() >= current.line()) {
            if (totalLines > 0) {
                return Math.min(current.end(), Math.max(current.line(), totalLines));
            }
            return current.end();
        }
        for (int j = index + 1; j < sorted.size(); j++) {
            int nextStart = sorted.get(j).line();
            if (nextStart > current.line()) {
                return nextStart - 1;
            }
        }
        if (totalLines >= current.line()) {
            return totalLines;
        }
        return current.line();
    }

    private Path resolveRegularFile(String relativePath) {
        Path path = projectService.resolvePath(relativePath);
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException(
                    ERROR_PREFIX + "Path is not a regular file or does not exist: " + relativePath);
        }
        return path.toAbsolutePath().normalize();
    }

    private String normalizeRelativePath(Path resolvedPath, String fallbackRelativePath) {
        Path root = projectService.getCurrentProjectRoot();
        if (root != null && resolvedPath != null) {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path normalizedTarget = resolvedPath.toAbsolutePath().normalize();
            if (normalizedTarget.startsWith(normalizedRoot)) {
                String rel = normalizedRoot.relativize(normalizedTarget).toString().replace(BACKSLASH_CHAR, SLASH_CHAR);
                if (!rel.isEmpty()) {
                    return rel;
                }
            }
        }
        if (fallbackRelativePath == null) {
            return "";
        }
        return fallbackRelativePath.trim().replace(BACKSLASH_CHAR, SLASH_CHAR);
    }

    private static List<String> readLinesSafely(Path filePath) {
        try {
            return Files.readAllLines(filePath);
        } catch (Exception e) {
            return List.of();
        }
    }
}
