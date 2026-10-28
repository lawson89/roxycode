package org.roxycode.app.ai.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
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

    static final int MAX_PRECEDING_CONTEXT_LINES = 5;
    static final int MAX_FIND_SYMBOL_FILES = 25;
    private static final int SINGLE_FILE_COUNT = 1;
    private static final int MIN_LINE_NUMBER = 1;
    private static final int NOT_FOUND_INDEX = -1;
    private static final char DOT_CHAR = '.';
    private static final char BACKSLASH_CHAR = 92;
    private static final char SLASH_CHAR = '/';
    private static final String DOUBLE_NEWLINE = "\n\n";

    static final Set<String> IGNORED_TAG_KINDS = Set.of("package", "namespace", "import");
    private static final Pattern SIMPLE_IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z0-9_$]+$");

    @FunctionalInterface
    interface CtagsRunner {
        ProcessResult run(List<String> command) throws IOException;
    }

    private record RawSymbol(String name, String kind, String path, int startLine, int endLine, String scope,
            String signature) {
    }

    private record LineRange(int startLine, int endLine) {
    }

    private final ProjectService projectService;
    private final FileReadService fileReadService;
    private final GrepService grepService;
    private final ObjectMapper objectMapper;
    private CtagsRunner ctagsRunner = command -> ProcessRunner.run(command, null, false);

    public SymbolService(ProjectService projectService, FileReadService fileReadService, GrepService grepService,
            ObjectMapper objectMapper) {
        this.projectService = projectService;
        this.fileReadService = fileReadService;
        this.grepService = grepService;
        this.objectMapper = objectMapper != null ? objectMapper : AiConfig.SHARED_OBJECT_MAPPER;
    }

    void setCtagsRunner(CtagsRunner ctagsRunner) {
        this.ctagsRunner = ctagsRunner;
    }

    @AgentDoc(value = "Returns a structural outline of symbols in a file (works on both source and test files), sorted by startLine.", examples = "symbolService.getFileOutline('src/main/java/App.java')")
    public List<SymbolInfo> getFileOutline(@AgentDoc(DOC_FILE_PATH) String relativePath) {
        ExceptionUtils.checkInterrupted(CANCELLED_MSG);
        Path filePath = resolveRegularFile(relativePath);
        String normalizedRelPath = normalizeRelativePath(filePath, relativePath);
        Map<Path, String> pathMap = Map.of(filePath, normalizedRelPath);
        Map<Path, List<RawSymbol>> rawByPath = runCtagsForFiles(List.of(filePath), pathMap);
        List<RawSymbol> rawSymbols = rawByPath.getOrDefault(filePath, List.of());
        if (rawSymbols.isEmpty()) {
            return List.of();
        }
        int totalLines = countFileLinesSafely(filePath);
        return finalizeSymbolsForFile(rawSymbols, totalLines);
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
        return readSymbols(relativePath, symbolNames == null ? List.of() : Arrays.asList(symbolNames));
    }

    public Map<String, String> readSymbols(String relativePath, List<String> symbolNames) {
        ExceptionUtils.checkInterrupted(CANCELLED_MSG);
        Map<String, String> results = new LinkedHashMap<>();
        if (symbolNames == null || symbolNames.isEmpty()) {
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
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            String errorMsg = msg.startsWith(ERROR_PREFIX) ? msg : ERROR_PREFIX + msg;
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
        String searchPattern = SIMPLE_IDENTIFIER_PATTERN.matcher(unqualifiedName).matches()
                ? unqualifiedName
                : Pattern.quote(unqualifiedName);
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

        Map<Path, List<RawSymbol>> rawByPath = runCtagsForFiles(candidatePaths, relPathByAbsPath);
        List<SymbolInfo> matches = new ArrayList<>();
        for (Path filePath : candidatePaths) {
            List<RawSymbol> rawForFile = rawByPath.get(filePath);
            if (rawForFile == null || rawForFile.isEmpty()) {
                continue;
            }
            int totalLines = countFileLinesSafely(filePath);
            for (SymbolInfo symbol : finalizeSymbolsForFile(rawForFile, totalLines)) {
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
        String normalizedRelPath = normalizeRelativePath(filePath, relativePath);
        Map<Path, String> pathMap = Map.of(filePath, normalizedRelPath);
        Map<Path, List<RawSymbol>> rawByPath = runCtagsForFiles(List.of(filePath), pathMap);
        List<RawSymbol> rawSymbols = rawByPath.getOrDefault(filePath, List.of());
        if (rawSymbols.isEmpty()) {
            return List.of();
        }
        return finalizeSymbolsForFile(rawSymbols, totalLines);
    }

    private String extractSymbolContent(String relativePath, String symbolName, List<SymbolInfo> outline,
            List<String> fileLines) throws IOException {
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
            int clampedEnd = totalLines > 0
                    ? Math.min(Math.max(adjustedStart, symbol.endLine()), totalLines)
                    : Math.max(adjustedStart, symbol.endLine());
            ranges.add(new LineRange(adjustedStart, clampedEnd));
        }

        List<LineRange> mergedRanges = mergeLineRanges(ranges);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mergedRanges.size(); i++) {
            if (i > 0) {
                sb.append(DOUBLE_NEWLINE);
            }
            LineRange range = mergedRanges.get(i);
            sb.append(fileReadService.readLines(relativePath, range.startLine(), range.endLine()));
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
        return dotIndex == NOT_FOUND_INDEX ? symbolQuery : symbolQuery.substring(dotIndex + 1).trim();
    }

    private static String formatSymbolNotFoundError(String symbolName, String relativePath) {
        return ERROR_PREFIX + "Symbol '" + (symbolName == null ? "" : symbolName) + "' not found in " + relativePath;
    }

    private Map<Path, List<RawSymbol>> runCtagsForFiles(List<Path> files, Map<Path, String> relPathByAbsPath) {
        Map<Path, List<RawSymbol>> results = new LinkedHashMap<>();
        if (files == null || files.isEmpty()) {
            return results;
        }
        try {
            List<String> command = new ArrayList<>(
                    List.of("ctags", "-f", "-", "--output-format=json", "--fields=+neKSs"));
            for (Path file : files) {
                command.add(file.toString());
            }
            ProcessResult procResult = ctagsRunner.run(command);
            if (procResult.timedOut()) {
                log.warn("Universal Ctags timed out for {} file(s)", files.size());
                return results;
            }
            String output = procResult.output();
            if (output == null || output.isBlank()) {
                return results;
            }
            for (String line : output.lines().toList()) {
                if (line.isBlank()) {
                    continue;
                }
                parseCtagsJsonLine(line, files, relPathByAbsPath, results);
            }
        } catch (Exception e) {
            ExceptionUtils.rethrowIfCancelled(e, CANCELLED_MSG);
            log.warn("Universal Ctags execution failed: {}", e.getMessage());
        }
        return results;
    }

    private void parseCtagsJsonLine(String line, List<Path> files, Map<Path, String> relPathByAbsPath,
            Map<Path, List<RawSymbol>> results) {
        try {
            JsonNode node = objectMapper.readTree(line);
            JsonNode typeNode = node.get("_type");
            if (typeNode != null && !"tag".equals(typeNode.asText())) {
                return;
            }
            JsonNode kindNode = node.get("kind");
            String kind = kindNode != null ? kindNode.asText("") : "";
            if (!kind.isEmpty() && IGNORED_TAG_KINDS.contains(kind.toLowerCase(Locale.ROOT))) {
                return;
            }
            JsonNode nameNode = node.get("name");
            JsonNode lineNode = node.get("line");
            if (nameNode == null || lineNode == null) {
                return;
            }
            int startLine = lineNode.asInt(0);
            if (startLine < MIN_LINE_NUMBER) {
                return;
            }
            Path targetPath = resolveTargetPath(node.get("path"), files);
            if (targetPath == null) {
                return;
            }
            String relPath = relPathByAbsPath.getOrDefault(targetPath, normalizeRelativePath(targetPath, null));
            JsonNode endNode = node.get("end");
            int endLine = endNode != null && endNode.canConvertToInt() ? endNode.asInt(0) : 0;
            JsonNode scopeNode = node.get("scope");
            String scope = scopeNode != null && !scopeNode.isNull() ? scopeNode.asText("") : "";
            JsonNode sigNode = node.get("signature");
            String signature = sigNode != null && !sigNode.isNull() ? sigNode.asText("") : "";

            results.computeIfAbsent(targetPath, k -> new ArrayList<>())
                    .add(new RawSymbol(nameNode.asText(""), kind, relPath, startLine, endLine, scope, signature));
        } catch (Exception e) {
            ExceptionUtils.rethrowIfCancelled(e, CANCELLED_MSG);
            log.trace("Skipping unparseable ctags JSON line: {}", line);
        }
    }

    private static Path resolveTargetPath(JsonNode pathNode, List<Path> files) {
        if (pathNode != null && !pathNode.asText().isBlank()) {
            return Path.of(pathNode.asText()).toAbsolutePath().normalize();
        }
        if (files.size() == SINGLE_FILE_COUNT) {
            return files.get(0);
        }
        return null;
    }

    private static List<SymbolInfo> finalizeSymbolsForFile(List<RawSymbol> rawSymbols, int totalLines) {
        List<RawSymbol> sorted = new ArrayList<>(rawSymbols);
        sorted.sort(Comparator.comparingInt(RawSymbol::startLine).thenComparing(RawSymbol::name));
        List<SymbolInfo> finalized = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            RawSymbol current = sorted.get(i);
            int resolvedEnd = resolveEndLine(sorted, i, totalLines);
            finalized.add(new SymbolInfo(current.name(), current.kind(), current.path(), current.startLine(),
                    resolvedEnd, current.scope(), current.signature()));
        }
        return finalized;
    }

    private static int resolveEndLine(List<RawSymbol> sorted, int index, int totalLines) {
        RawSymbol current = sorted.get(index);
        if (current.endLine() >= current.startLine()) {
            return totalLines > 0
                    ? Math.min(current.endLine(), Math.max(current.startLine(), totalLines))
                    : current.endLine();
        }
        for (int j = index + 1; j < sorted.size(); j++) {
            int nextStart = sorted.get(j).startLine();
            if (nextStart > current.startLine()) {
                return nextStart - 1;
            }
        }
        if (totalLines >= current.startLine()) {
            return totalLines;
        }
        return current.startLine();
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
        return fallbackRelativePath == null ? "" : fallbackRelativePath.trim().replace(BACKSLASH_CHAR, SLASH_CHAR);
    }

    private static int countFileLinesSafely(Path filePath) {
        try {
            return (int) Files.readString(filePath).lines().count();
        } catch (Exception e) {
            return 0;
        }
    }
}
