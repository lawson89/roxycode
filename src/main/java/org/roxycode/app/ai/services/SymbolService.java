package org.roxycode.app.ai.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.service.ProjectAnalysisService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.util.ExceptionUtils;
import org.roxycode.app.util.PathUtils;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
@AgentService(value = "symbolService", phases = {"*"})
/**
 * Provides structural symbol outlines, search, and reading.
 */
public class SymbolService {

    private static final String CANCELLED_MSG = "Symbol service operation cancelled";
    private static final String DOC_FILE_PATH = "The file path relative to the project root";
    private static final String DOC_SYMBOL_NAME = "The symbol name to search for";
    public static final int MAX_FIND_SYMBOL_FILES = 25;
    private static final int MAX_RELATED_TESTS = 10;
    private static final String CURRENT_DIR = ".";
    private static final String WORD_BOUNDARY = "\\b";
    private static final Pattern SIMPLE_IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    private static final int USAGE_PARTS_COUNT = 4;
    private static final int MIN_MERGEABLE_RANGES = 2;

    private final ProjectService projectService;
    private final GrepService grepService;
    private final ObjectMapper objectMapper;
    private CtagsRunner ctagsRunner;

    private record LineRange(int startLine, int endLine) {
    }

    private record RawUsageHit(String path, int line, int column, String snippet) {
    }

    private record ResolvedSymbolQuery(String effectivePath, String trimmedQuery, String unqualifiedName) {
    }

    public SymbolService(ProjectService projectService, GrepService grepService, ObjectMapper objectMapper) {
        this.projectService = projectService;
        this.grepService = grepService;
        this.objectMapper = objectMapper;
    }

    void setCtagsRunner(CtagsRunner ctagsRunner) {
        this.ctagsRunner = ctagsRunner;
    }

    private CtagsRunner getCtagsRunner() {
        if (ctagsRunner == null) {
            return CtagsRunner.DEFAULT;
        }
        return ctagsRunner;
    }

    @AgentDoc(value = "Returns a structural outline of symbols in a file, sorted by startLine.", examples = "symbolService.getFileOutline('src/main/java/App.java')")
    public List<SymbolInfo> getFileOutline(@AgentDoc(DOC_FILE_PATH) String relativePath) {
        ExceptionUtils.checkInterrupted(CANCELLED_MSG);
        Path filePath = projectService.resolveReadPath(relativePath);
        if (!java.nio.file.Files.isRegularFile(filePath)) {
            throw new IllegalArgumentException("File does not exist or is not a regular file: " + relativePath);
        }
        int totalLines = FileReadService.getLineCountSafely(filePath);
        return getFileOutlineWithLines(filePath, relativePath, totalLines);
    }

    @AgentDoc(value = "Reads the numbered lines of all matching symbols in a file.", examples = "symbolService.readSymbol('src/main/java/App.java', 'main')")
    public String readSymbol(@AgentDoc(DOC_FILE_PATH) String relativePath, @AgentDoc(DOC_SYMBOL_NAME) String symbolName)
            throws IOException {
        if (symbolName == null || symbolName.isBlank()) {
            return formatSymbolNotFoundError(symbolName, relativePath);
        }
        List<SymbolInfo> outline = getFileOutline(relativePath);
        List<SymbolInfo> matching = filterMatchingSymbols(outline, symbolName);
        if (matching.isEmpty()) {
            return formatSymbolNotFoundError(symbolName, relativePath);
        }
        Path filePath = projectService.resolveReadPath(relativePath);
        List<String> fileLines = FileReadService.readLinesSafely(filePath);
        return extractSymbolContent(matching, fileLines);
    }

    @AgentDoc(value = "Reads multiple symbols from a file in a single batch call.", examples = "symbolService.readSymbols('src/main/java/App.java', ['main', 'helper'])")
    public Map<String, String> readSymbols(@AgentDoc(DOC_FILE_PATH) String relativePath,
            @AgentDoc("The symbol names to read") String... symbolNames) throws IOException {
        if (symbolNames == null || symbolNames.length == 0) {
            return Map.of();
        }
        Map<String, String> results = new LinkedHashMap<>();
        List<SymbolInfo> outline;
        List<String> fileLines;
        try {
            outline = getFileOutline(relativePath);
            Path filePath = projectService.resolveReadPath(relativePath);
            fileLines = FileReadService.readLinesSafely(filePath);
        } catch (Exception e) {
            for (String name : symbolNames) {
                if (name != null && !name.isBlank()) {
                    results.put(name, "Error: " + ExceptionUtils.getMessageOrSimpleName(e));
                }
            }
            return results;
        }
        for (String name : symbolNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            List<SymbolInfo> matching = filterMatchingSymbols(outline, name);
            if (matching.isEmpty()) {
                results.put(name, formatSymbolNotFoundError(name, relativePath));
            } else {
                results.put(name, extractSymbolContent(matching, fileLines));
            }
        }
        return results;
    }

    @AgentDoc(value = "Finds all symbol definitions matching symbolName across the project.", examples = "symbolService.findSymbol('executeJexl')")
    public List<SymbolInfo> findSymbol(@AgentDoc(DOC_SYMBOL_NAME) String symbolName) {
        return findSymbol(symbolName, null);
    }

    @AgentDoc(value = "Finds all symbol definitions matching symbolName within the specified path.", examples = "symbolService.findSymbol('executeJexl', 'src/main')")
    public List<SymbolInfo> findSymbol(@AgentDoc(DOC_SYMBOL_NAME) String symbolName,
            @AgentDoc("Optional path to narrow search") @Nullable String relativePath) {
        ExceptionUtils.checkInterrupted(CANCELLED_MSG);
        if (symbolName == null || symbolName.isBlank()) {
            return List.of();
        }
        ResolvedSymbolQuery query = resolveSymbolQuery(symbolName, relativePath);
        List<String> candidates = collectCandidatePathsForFindSymbol(query.unqualifiedName(), query.effectivePath());
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<Path, String> candidateFiles = resolveCandidateFiles(candidates, false);
        if (candidateFiles.isEmpty()) {
            return List.of();
        }
        Map<Path, List<CtagsRunner.CtagsEntry>> entries = getCtagsRunner()
                .runAndParse(new ArrayList<>(candidateFiles.keySet()), List.of(), objectMapper, CANCELLED_MSG);
        List<SymbolInfo> allMatches = new ArrayList<>();
        for (Map.Entry<Path, List<CtagsRunner.CtagsEntry>> entry : entries.entrySet()) {
            String relPath = candidateFiles.get(entry.getKey());
            List<SymbolInfo> fileSymbols = CtagsRunner.finalizeSymbolsForFile(entry.getValue(), relPath, entry.getKey(),
                    FileReadService.getLineCountSafely(entry.getKey()));
            allMatches.addAll(filterMatchingSymbols(fileSymbols, query.trimmedQuery()));
        }
        return allMatches.stream()
                .sorted(Comparator.comparing(SymbolInfo::path).thenComparingInt(SymbolInfo::startLine)).toList();
    }

    @AgentDoc(value = "Finds all usages of symbolName across the project.", examples = "symbolService.findUsages('executeJexl')")
    public List<SymbolUsage> findUsages(@AgentDoc(DOC_SYMBOL_NAME) String symbolName) {
        return findUsages(symbolName, null);
    }

    @AgentDoc(value = "Finds all usages of symbolName within the specified path.", examples = "symbolService.findUsages('executeJexl', 'src/main')")
    public List<SymbolUsage> findUsages(@AgentDoc(DOC_SYMBOL_NAME) String symbolName,
            @AgentDoc("Optional path to narrow search") @Nullable String relativePath) {
        ExceptionUtils.checkInterrupted(CANCELLED_MSG);
        if (symbolName == null || symbolName.isBlank()) {
            return List.of();
        }
        ResolvedSymbolQuery query = resolveSymbolQuery(symbolName, relativePath);
        String path = query.effectivePath().isEmpty() ? CURRENT_DIR : query.effectivePath();
        List<String> rawMatches = grepService.grepWordMatches(query.unqualifiedName(), path, true);
        if (rawMatches == null || rawMatches.isEmpty()) {
            return List.of();
        }
        List<RawUsageHit> hits = parseAndFilterUsageHits(rawMatches);
        Map<String, List<SymbolInfo>> outlines = resolveOutlinesForUsageHits(hits);
        List<SymbolUsage> usages = new ArrayList<>();
        Set<String> seenLocations = new HashSet<>();
        for (RawUsageHit hit : hits) {
            String locationKey = hit.path() + ":" + hit.line();
            if (!seenLocations.add(locationKey)) {
                continue;
            }
            List<SymbolInfo> outline = outlines.get(hit.path());
            if (outline != null && isDeclarationInOutline(hit.line(), outline, query.trimmedQuery(),
                    query.unqualifiedName(), hit.snippet())) {
                continue;
            }
            String enclosing = findEnclosingSymbolName(outline, hit.line());
            usages.add(new SymbolUsage(hit.path(), hit.line(), hit.column(), hit.snippet(), enclosing,
                    ProjectAnalysisService.isTestFile(hit.path())));
        }
        return usages.stream().sorted(Comparator.comparing(SymbolUsage::path).thenComparingInt(SymbolUsage::line))
                .toList();
    }

    @AgentDoc(value = "Finds test files related to the given source file path using naming conventions, mirrored directory structure, and symbol reference analysis.", examples = "symbolService.findRelatedTests('src/main/java/org/roxycode/app/ai/services/SymbolService.java')")
    public List<String> findRelatedTests(@AgentDoc(DOC_FILE_PATH) String relativePath) {
        ExceptionUtils.checkInterrupted(CANCELLED_MSG);
        if (relativePath == null || relativePath.isBlank()
                || (!projectService.hasActiveProject() && !ProjectService.isReferencePath(relativePath))) {
            return List.of();
        }
        String normalizedPath = normalizeInputRelativePath(relativePath);
        if (normalizedPath.isEmpty() || CURRENT_DIR.equals(normalizedPath)) {
            return List.of();
        }
        if (ProjectAnalysisService.isTestFile(normalizedPath)) {
            if (isExistingRegularFile(normalizedPath)) {
                return List.of(normalizedPath);
            }
            return List.of();
        }

        String stem = PathUtils.getBaseName(normalizedPath);
        if (stem.isBlank()) {
            return List.of();
        }

        String referencePrefix = ProjectService.extractReferenceAliasPrefix(normalizedPath);
        String sourceSubpath = ProjectAnalysisService.extractNormalizedSubpath(
                ProjectService.stripReferenceAliasPrefix(normalizedPath), ProjectAnalysisService.SOURCE_DIR_MARKERS);
        List<String> allFiles = listFilesForScope(referencePrefix);
        List<String> mirroredTier1 = new ArrayList<>();
        List<String> otherTier1 = new ArrayList<>();
        if (allFiles != null) {
            for (String candidate : allFiles) {
                if (candidate == null || candidate.isBlank()) {
                    continue;
                }
                String normCandidate = PathUtils.normalizeRelativePath(candidate.trim());
                if (normCandidate.equals(normalizedPath) || !ProjectAnalysisService.isTestFile(normCandidate)) {
                    continue;
                }
                String candidateFileName = PathUtils.getFileName(normCandidate);
                if (ProjectAnalysisService.matchesTestNamingConvention(candidateFileName, stem)) {
                    String testSubpath = ProjectAnalysisService.extractNormalizedSubpath(
                            ProjectService.stripReferenceAliasPrefix(normCandidate),
                            ProjectAnalysisService.TEST_DIR_MARKERS);
                    if (!sourceSubpath.isEmpty() && sourceSubpath.equals(testSubpath)) {
                        mirroredTier1.add(normCandidate);
                    } else {
                        otherTier1.add(normCandidate);
                    }
                }
            }
        }
        mirroredTier1.sort(Comparator.naturalOrder());
        otherTier1.sort(Comparator.naturalOrder());

        Set<String> orderedResults = new LinkedHashSet<>();
        orderedResults.addAll(mirroredTier1);
        orderedResults.addAll(otherTier1);

        String referencePattern = Pattern.quote(stem);
        if (SIMPLE_IDENTIFIER_PATTERN.matcher(stem).matches()) {
            referencePattern = WORD_BOUNDARY + stem + WORD_BOUNDARY;
        }
        List<String> referencingFiles;
        if (referencePrefix.isEmpty()) {
            referencingFiles = grepService.findMatchingFiles(referencePattern);
        } else {
            referencingFiles = grepService.findMatchingFiles(referencePattern, referencePrefix, null);
        }
        if (referencingFiles != null) {
            List<String> tier2 = new ArrayList<>();
            for (String refFile : referencingFiles) {
                if (refFile == null || refFile.isBlank()) {
                    continue;
                }
                String normRef = PathUtils.normalizeRelativePath(refFile.trim());
                if (!normRef.equals(normalizedPath) && ProjectAnalysisService.isTestFile(normRef)
                        && !orderedResults.contains(normRef)) {
                    tier2.add(normRef);
                }
            }
            tier2.sort(Comparator.naturalOrder());
            for (String item : tier2) {
                if (orderedResults.size() >= MAX_RELATED_TESTS) {
                    break;
                }
                orderedResults.add(item);
            }
        }

        List<String> finalResults = new ArrayList<>(orderedResults);
        if (finalResults.size() > MAX_RELATED_TESTS) {
            return new ArrayList<>(finalResults.subList(0, MAX_RELATED_TESTS));
        }
        return finalResults;
    }

    private ResolvedSymbolQuery resolveSymbolQuery(String symbolName, String relativePath) {
        String trimmedQuery = StringUtils.trimToEmpty(symbolName);
        if (trimmedQuery.isEmpty()) {
            throw new IllegalArgumentException("Symbol name cannot be empty");
        }
        String effectivePath = normalizeInputRelativePath(relativePath);
        String unqualifiedName = extractUnqualifiedName(trimmedQuery);
        return new ResolvedSymbolQuery(effectivePath, trimmedQuery, unqualifiedName);
    }

    private List<RawUsageHit> parseAndFilterUsageHits(List<String> rawMatches) {
        List<RawUsageHit> hits = new ArrayList<>();
        for (String match : rawMatches) {
            String[] parts = match.split(":", USAGE_PARTS_COUNT);
            if (parts.length < USAGE_PARTS_COUNT) {
                continue;
            }
            try {
                String path = PathUtils.normalizeRelativePath(parts[0]);
                int line = Integer.parseInt(parts[1]);
                int col = Integer.parseInt(parts[2]);
                String snippet = parts[3].trim();
                if (isNonUsageSnippet(snippet)) {
                    continue;
                }
                hits.add(new RawUsageHit(path, line, col, snippet));
            } catch (NumberFormatException ignored) {
            }
        }
        return hits;
    }

    private Map<String, List<SymbolInfo>> resolveOutlinesForUsageHits(List<RawUsageHit> hits) {
        Set<String> paths = hits.stream().map(RawUsageHit::path).collect(Collectors.toSet());
        return resolveBatchOutlines(paths, true);
    }

    private Map<String, List<SymbolInfo>> resolveBatchOutlines(Collection<String> rawRelPaths,
            boolean normalizeRelPath) {
        Map<Path, String> candidateFiles = resolveCandidateFiles(rawRelPaths, normalizeRelPath);
        if (candidateFiles.isEmpty()) {
            return Map.of();
        }
        Map<Path, List<CtagsRunner.CtagsEntry>> entries = getCtagsRunner()
                .runAndParse(new ArrayList<>(candidateFiles.keySet()), List.of(), objectMapper, CANCELLED_MSG);
        Map<String, List<SymbolInfo>> results = new HashMap<>();
        for (Map.Entry<Path, List<CtagsRunner.CtagsEntry>> entry : entries.entrySet()) {
            String relPath = candidateFiles.get(entry.getKey());
            results.put(relPath, CtagsRunner.finalizeSymbolsForFile(entry.getValue(), relPath, entry.getKey(),
                    FileReadService.getLineCountSafely(entry.getKey())));
        }
        return results;
    }

    private Map<Path, String> resolveCandidateFiles(Collection<String> rawRelPaths, boolean normalizeRelPath) {
        Map<Path, String> candidates = new LinkedHashMap<>();
        for (String raw : rawRelPaths) {
            if (candidates.size() >= MAX_FIND_SYMBOL_FILES) {
                break;
            }
            try {
                String norm = normalizeRelPath ? PathUtils.normalizeRelativePath(raw) : raw;
                Path resolved = projectService.resolveReadPath(norm);
                candidates.put(resolved, norm);
            } catch (Exception ignored) {
            }
        }
        return candidates;
    }

    static boolean isDeclarationInOutline(int line, List<SymbolInfo> outline, String trimmedQuery,
            String unqualifiedName, String trimmedSnippet) {
        for (SymbolInfo sym : outline) {
            if (sym.startLine() == line && (sym.name().equals(unqualifiedName) || matchesSymbol(sym, trimmedQuery))) {
                return true;
            }
        }
        return trimmedSnippet.startsWith("import ") || trimmedSnippet.startsWith("package ")
                || (unqualifiedName.length() >= 3 && trimmedSnippet.startsWith("@")
                        && trimmedSnippet.contains(unqualifiedName));
    }

    static String findEnclosingSymbolName(List<SymbolInfo> outline, int line) {
        if (outline == null || outline.isEmpty()) {
            return "";
        }
        SymbolInfo best = null;
        for (SymbolInfo sym : outline) {
            if (line >= sym.startLine() && line <= sym.endLine()) {
                if (best == null || (sym.startLine() >= best.startLine() && sym.endLine() <= best.endLine())) {
                    best = sym;
                }
            }
        }
        if (best != null) {
            if (best.scope() != null && !best.scope().isBlank()) {
                return best.scope() + "." + best.name();
            }
            return best.name();
        }
        return "";
    }

    static boolean isNonUsageSnippet(String trimmedSnippet) {
        return trimmedSnippet.isEmpty() || trimmedSnippet.startsWith("//") || trimmedSnippet.startsWith("/*")
                || trimmedSnippet.startsWith("*");
    }

    private List<String> collectCandidatePathsForFindSymbol(String searchPattern, String effectivePath) {
        List<String> candidates;
        if (effectivePath.isEmpty() || CURRENT_DIR.equals(effectivePath)) {
            candidates = grepService.findMatchingFiles(searchPattern);
        } else {
            String referencePrefix = ProjectService.extractReferenceAliasPrefix(effectivePath);
            if (!referencePrefix.isEmpty()) {
                String subPath = ProjectService.stripReferenceAliasPrefix(effectivePath);
                candidates = grepService.findMatchingFiles(searchPattern, referencePrefix, null);
                if (!subPath.isEmpty() && !CURRENT_DIR.equals(subPath)) {
                    candidates = candidates.stream().filter(c -> c.startsWith(effectivePath + "/")).toList();
                }
            } else {
                candidates = grepService.findMatchingFiles(searchPattern, effectivePath, null);
            }
        }
        return candidates;
    }

    private String normalizeInputRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return "";
        }
        return PathUtils.normalizeRelativePath(relativePath.trim());
    }

    private boolean isExistingRegularFile(String normalizedPath) {
        try {
            Path p = projectService.resolveReadPath(normalizedPath);
            return java.nio.file.Files.isRegularFile(p);
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> listFilesForScope(String referencePrefix) {
        if (referencePrefix.isEmpty()) {
            return grepService.listFiles();
        }
        return grepService.listFiles(referencePrefix);
    }

    private static String extractUnqualifiedName(String symbolQuery) {
        int lastDot = symbolQuery.lastIndexOf('.');
        if (lastDot < 0 || lastDot == symbolQuery.length() - 1) {
            return symbolQuery;
        }
        return symbolQuery.substring(lastDot + 1);
    }

    private static String formatSymbolNotFoundError(String symbolName, String relativePath) {
        return "Error: Symbol '" + org.apache.commons.lang3.StringUtils.defaultString(symbolName) + "' not found in "
                + relativePath;
    }

    private String extractSymbolContent(List<SymbolInfo> matching, List<String> fileLines) {
        List<LineRange> ranges = new ArrayList<>();
        for (SymbolInfo sym : matching) {
            int start = adjustStartLineForPrecedingContext(fileLines, sym.startLine());
            ranges.add(new LineRange(start, sym.endLine()));
        }
        List<LineRange> merged = mergeLineRanges(ranges);
        StringBuilder sb = new StringBuilder();
        for (LineRange range : merged) {
            if (sb.length() > 0) {
                sb.append("\n--\n");
            }
            sb.append(FileReadService.formatNumberedLines(fileLines, range.startLine(), range.endLine()));
        }
        return sb.toString();
    }

    static int adjustStartLineForPrecedingContext(List<String> fileLines, int symbolStartLine) {
        int current = symbolStartLine;
        if (current > fileLines.size()) {
            current = fileLines.size();
        }
        while (current > 1) {
            String prevLine = fileLines.get(current - 2).trim();
            if (isAnnotationOrCommentLine(prevLine)) {
                current--;
            } else {
                break;
            }
        }
        return current;
    }

    static boolean isAnnotationOrCommentLine(String trimmedLine) {
        return trimmedLine.startsWith("@") || trimmedLine.startsWith("//") || trimmedLine.startsWith("/*")
                || trimmedLine.startsWith("*");
    }

    private static List<LineRange> mergeLineRanges(List<LineRange> ranges) {
        if (ranges.size() < MIN_MERGEABLE_RANGES) {
            return ranges;
        }
        List<LineRange> sorted = ranges.stream().sorted(Comparator.comparingInt(LineRange::startLine)).toList();
        List<LineRange> merged = new ArrayList<>();
        LineRange current = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            LineRange next = sorted.get(i);
            if (next.startLine() <= current.endLine() + 1) {
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
        if (symbol.name().equals(query)) {
            return true;
        }
        String fullName = symbol.name();
        if (symbol.scope() != null && !symbol.scope().isBlank()) {
            fullName = symbol.scope() + "." + symbol.name();
        }
        return fullName.equals(query) || fullName.endsWith("." + query);
    }

    private List<SymbolInfo> getFileOutlineWithLines(Path filePath, String relativePath, int totalLines) {
        List<CtagsRunner.CtagsEntry> entries = getCtagsRunner()
                .runAndParse(List.of(filePath), List.of(), objectMapper, CANCELLED_MSG)
                .getOrDefault(filePath, List.of());
        return CtagsRunner.finalizeSymbolsForFile(entries, relativePath, filePath, totalLines);
    }

    static List<SymbolInfo> filterMatchingSymbols(List<SymbolInfo> outline, String symbolName) {
        return outline.stream().filter(s -> matchesSymbol(s, symbolName)).toList();
    }
}
