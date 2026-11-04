package org.roxycode.app.ai.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.ai.services.CtagsRunner.CtagsEntry;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.ProjectService.ReferenceFolderMount;
import org.roxycode.app.util.ExceptionUtils;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AgentService(value = "symbolService", phases = {"*"})
@AgentDoc("Provides structural symbol outlines, symbol search, and symbol body reading using Universal Ctags across the project and mounted @<alias> reference folders.")
public class SymbolService {

    private static final Logger log = LoggerFactory.getLogger(SymbolService.class);

    private static final String DOC_FILE_PATH = "The file path relative to the project root (or '@<alias>/path' for a reference folder)";
    private static final String DOC_SEARCH_PATH = "The directory or file path relative to the project root (or '@<alias>' / '@<alias>/subpath') to scope the search";
    private static final String DOC_SYMBOL_NAME = "The symbol name (e.g., 'myMethod' or 'MyClass.myMethod')";
    private static final String DOC_SYMBOL_NAMES = "Array of symbol names to read from the file";
    private static final String ERROR_PREFIX = "Error: ";
    private static final String CANCELLED_MSG = "Symbol operation cancelled";
    private static final String CURRENT_DIR = ".";
    private static final List<String> CTAGS_OUTLINE_FLAGS = List.of("--fields=+neKSs");
    private static final List<String> SOURCE_DIR_PREFIXES = List.of("src/main/java/", "src/main/kotlin/",
            "src/main/scala/", "src/main/groovy/", "src/main/", "src/", "lib/", "app/");
    private static final List<String> TEST_DIR_PREFIXES = List.of("src/test/java/", "src/test/kotlin/",
            "src/test/scala/", "src/test/groovy/", "src/test/", "tests/", "test/", "__tests__/");

    static final int MAX_PRECEDING_CONTEXT_LINES = 5;
    static final int MAX_FIND_SYMBOL_FILES = 25;
    static final int MAX_USAGES = 100;
    static final int MAX_RELATED_TESTS = 15;
    private static final int SINGLE_FILE_COUNT = 1;
    private static final int MIN_LINE_NUMBER = 1;
    private static final int NOT_FOUND_INDEX = -1;
    private static final char DOT_CHAR = '.';
    private static final char COLON_CHAR = ':';
    private static final char SLASH_CHAR = '/';
    private static final String WORD_BOUNDARY = "\\b";
    private static final String IMPORT_TAB_PREFIX = "import\t";
    private static final String PACKAGE_TAB_PREFIX = "package\t";
    private static final String DOUBLE_NEWLINE = "\n\n";
    private static final Pattern SIMPLE_IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z0-9_$]+$");
    private static final Pattern VIMGREP_LINE_PATTERN = Pattern.compile("^(.+?):([0-9]+):([0-9]+):(.*)$");
    private record LineRange(int startLine, int endLine) {
    }

    private record RawUsageHit(String path, int line, int column, String snippet) {
    }

    private final ProjectService projectService;
    private final GrepService grepService;
    private final ObjectMapper objectMapper;
    private CtagsRunner ctagsRunner = CtagsRunner.DEFAULT;

    public SymbolService(ProjectService projectService, GrepService grepService, ObjectMapper objectMapper) {
        this.projectService = projectService;
        this.grepService = grepService;
        this.objectMapper = Objects.requireNonNullElse(objectMapper, AiConfig.SHARED_OBJECT_MAPPER);
    }

    void setCtagsRunner(CtagsRunner ctagsRunner) {
        this.ctagsRunner = ctagsRunner;
    }

    @AgentDoc(value = "Returns a structural outline of symbols in a file (works on both source and test files), sorted by startLine.", examples = "symbolService.getFileOutline('src/main/java/App.java')")
    public List<SymbolInfo> getFileOutline(@AgentDoc(DOC_FILE_PATH) String relativePath) {
        ExceptionUtils.checkInterrupted(CANCELLED_MSG);
        Path filePath = resolveRegularFile(relativePath);
        return getFileOutlineWithLines(filePath, relativePath, -1);
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
            String msg = ExceptionUtils.getMessageOrSimpleName(e);
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

    @AgentDoc(value = "Finds all symbol definitions matching symbolName across the project and mounted @<alias> reference folders by narrowing candidate files with RipGrep and running Universal Ctags.", examples = {
            "symbolService.findSymbol('executeJexl')", "symbolService.findSymbol('JexlTool.executeJexl')"})
    public List<SymbolInfo> findSymbol(@AgentDoc(DOC_SYMBOL_NAME) String symbolName) {
        return findSymbol(symbolName, CURRENT_DIR);
    }

    @AgentDoc(value = "Finds all symbol definitions matching symbolName within the specified relative path or @<alias> reference folder.", examples = {
            "symbolService.findSymbol('InvoiceProcessor', '@billing-service')",
            "symbolService.findSymbol('executeJexl', 'src/main')"})
    public List<SymbolInfo> findSymbol(@AgentDoc(DOC_SYMBOL_NAME) String symbolName,
            @AgentDoc(DOC_SEARCH_PATH) String relativePath) {
        ExceptionUtils.checkInterrupted(CANCELLED_MSG);
        String effectivePath = CURRENT_DIR;
        if (relativePath != null && !relativePath.isBlank()) {
            effectivePath = relativePath.trim();
        }
        if (symbolName == null || symbolName.isBlank()
                || (!projectService.hasActiveProject() && !ProjectService.isReferencePath(effectivePath))) {
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
        List<String> candidateRelPaths = collectCandidatePathsForFindSymbol(searchPattern, effectivePath);
        if (candidateRelPaths.isEmpty()) {
            return List.of();
        }

        Map<Path, String> relPathByAbsPath = resolveCandidateFiles(candidateRelPaths, true);
        if (relPathByAbsPath.isEmpty()) {
            return List.of();
        }

        List<Path> candidatePaths = new ArrayList<>(relPathByAbsPath.keySet());
        Map<Path, List<CtagsEntry>> entriesByPath = ctagsRunner.runAndParse(candidatePaths, CTAGS_OUTLINE_FLAGS,
                objectMapper, CANCELLED_MSG);
        List<SymbolInfo> matches = new ArrayList<>();
        for (Map.Entry<Path, String> fileEntry : relPathByAbsPath.entrySet()) {
            Path filePath = fileEntry.getKey();
            List<CtagsEntry> entriesForFile = entriesByPath.get(filePath);
            if (entriesForFile == null || entriesForFile.isEmpty()) {
                continue;
            }
            String relPath = fileEntry.getValue();
            for (SymbolInfo symbol : finalizeSymbolsForFile(entriesForFile, relPath, filePath, -1)) {
                if (matchesSymbol(symbol, trimmedQuery)) {
                    matches.add(symbol);
                }
            }
        }
        matches.sort(Comparator.comparing(SymbolInfo::path).thenComparingInt(SymbolInfo::startLine)
                .thenComparingInt(SymbolInfo::endLine).thenComparing(SymbolInfo::name));
        return matches;
    }

    @AgentDoc(value = "Finds all usages of symbolName across the project (excluding definitions, comments, and imports), enriched with the enclosing symbol and test file classification.", examples = {
            "symbolService.findUsages('executeJexl')", "symbolService.findUsages('JexlTool.executeJexl')"})
    public List<SymbolUsage> findUsages(@AgentDoc(DOC_SYMBOL_NAME) String symbolName) {
        return findUsages(symbolName, CURRENT_DIR);
    }

    @AgentDoc(value = "Finds all usages of symbolName within the specified relative path (excluding definitions, comments, and imports), enriched with the enclosing symbol and test file classification.", examples = "symbolService.findUsages('executeJexl', 'src/main')")
    public List<SymbolUsage> findUsages(@AgentDoc(DOC_SYMBOL_NAME) String symbolName,
            @AgentDoc(DOC_SEARCH_PATH) String relativePath) {
        ExceptionUtils.checkInterrupted(CANCELLED_MSG);
        String effectivePath = CURRENT_DIR;
        if (relativePath != null && !relativePath.isBlank()) {
            effectivePath = relativePath.trim();
        }
        if (symbolName == null || symbolName.isBlank()
                || (!projectService.hasActiveProject() && !ProjectService.isReferencePath(effectivePath))) {
            return List.of();
        }
        String trimmedQuery = symbolName.trim();
        String unqualifiedName = extractUnqualifiedName(trimmedQuery);
        if (unqualifiedName.isEmpty()) {
            return List.of();
        }
        boolean fixedWord = SIMPLE_IDENTIFIER_PATTERN.matcher(unqualifiedName).matches();
        List<String> rawMatches = grepService.grepWordMatches(unqualifiedName, effectivePath, fixedWord);
        if (rawMatches == null || rawMatches.isEmpty()) {
            return List.of();
        }

        List<RawUsageHit> filteredHits = parseAndFilterUsageHits(rawMatches);
        if (filteredHits.isEmpty()) {
            return List.of();
        }
        Map<String, List<SymbolInfo>> outlinesByRelPath = resolveOutlinesForUsageHits(filteredHits);
        List<SymbolUsage> usages = new ArrayList<>();
        for (RawUsageHit hit : filteredHits) {
            List<SymbolInfo> outline = outlinesByRelPath.getOrDefault(hit.path(), List.of());
            if (isDeclarationInOutline(hit.line(), outline, trimmedQuery, unqualifiedName)) {
                continue;
            }
            String enclosing = findEnclosingSymbolName(outline, hit.line());
            boolean testFile = RepoMapPackerService.isTestFile(hit.path());
            usages.add(new SymbolUsage(hit.path(), hit.line(), hit.column(), hit.snippet(), enclosing, testFile));
            if (usages.size() >= MAX_USAGES) {
                break;
            }
        }

        usages.sort(Comparator.comparing(SymbolUsage::isTestFile).thenComparing(SymbolUsage::path)
                .thenComparingInt(SymbolUsage::line).thenComparingInt(SymbolUsage::column));
        return usages;
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
        if (RepoMapPackerService.isTestFile(normalizedPath)) {
            if (isExistingRegularFile(normalizedPath)) {
                return List.of(normalizedPath);
            }
            return List.of();
        }

        String fileName = PathUtils.getFileName(normalizedPath);
        String stem = stripExtension(fileName);
        if (stem.isBlank()) {
            return List.of();
        }

        String referencePrefix = extractReferenceAliasPrefix(normalizedPath);
        String sourceSubpath = extractNormalizedSubpath(stripReferenceAliasPrefix(normalizedPath), SOURCE_DIR_PREFIXES);
        List<String> allFiles;
        if (referencePrefix.isEmpty()) {
            allFiles = grepService.listFiles();
        } else {
            allFiles = grepService.listFiles(referencePrefix);
        }
        List<String> mirroredTier1 = new ArrayList<>();
        List<String> otherTier1 = new ArrayList<>();
        if (allFiles != null) {
            for (String candidate : allFiles) {
                if (candidate == null || candidate.isBlank()) {
                    continue;
                }
                String normCandidate = PathUtils.normalizeRelativePath(candidate.trim());
                if (normCandidate.equals(normalizedPath) || !RepoMapPackerService.isTestFile(normCandidate)) {
                    continue;
                }
                String candidateFileName = PathUtils.getFileName(normCandidate);
                if (matchesTestNamingConvention(candidateFileName, stem)) {
                    String testSubpath = extractNormalizedSubpath(stripReferenceAliasPrefix(normCandidate),
                            TEST_DIR_PREFIXES);
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
                if (!normRef.equals(normalizedPath) && RepoMapPackerService.isTestFile(normRef)
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

    private List<RawUsageHit> parseAndFilterUsageHits(List<String> rawMatches) {
        List<RawUsageHit> hits = new ArrayList<>();
        Set<String> seenLineLocations = new HashSet<>();
        for (String rawLine : rawMatches) {
            if (rawLine == null || rawLine.isBlank()) {
                continue;
            }
            Matcher matcher = VIMGREP_LINE_PATTERN.matcher(rawLine);
            if (!matcher.matches()) {
                continue;
            }
            String path = PathUtils.normalizeRelativePath(matcher.group(1).trim());
            int line = NumberUtils.toInt(matcher.group(2), NOT_FOUND_INDEX);
            int column = NumberUtils.toInt(matcher.group(3), NOT_FOUND_INDEX);
            String snippet = matcher.group(4).trim();
            if (line < MIN_LINE_NUMBER || isNonUsageSnippet(snippet)) {
                continue;
            }
            String locationKey = formatLocationKey(path, line);
            if (!seenLineLocations.add(locationKey)) {
                continue;
            }
            hits.add(new RawUsageHit(path, line, column, snippet));
        }
        return hits;
    }

    private Map<String, List<SymbolInfo>> resolveOutlinesForUsageHits(List<RawUsageHit> hits) {
        List<String> hitPaths = new ArrayList<>(hits.size());
        for (RawUsageHit hit : hits) {
            hitPaths.add(hit.path());
        }
        Map<Path, String> relPathByAbsPath = resolveCandidateFiles(hitPaths, false);
        if (relPathByAbsPath.isEmpty()) {
            return Map.of();
        }
        List<Path> candidatePaths = new ArrayList<>(relPathByAbsPath.keySet());
        Map<Path, List<CtagsEntry>> entriesByPath = ctagsRunner.runAndParse(candidatePaths, CTAGS_OUTLINE_FLAGS,
                objectMapper, CANCELLED_MSG);
        Map<String, List<SymbolInfo>> outlines = new LinkedHashMap<>();
        for (Map.Entry<Path, String> fileEntry : relPathByAbsPath.entrySet()) {
            Path filePath = fileEntry.getKey();
            List<CtagsEntry> entries = entriesByPath.get(filePath);
            if (entries == null || entries.isEmpty()) {
                continue;
            }
            String relPath = fileEntry.getValue();
            outlines.put(relPath, finalizeSymbolsForFile(entries, relPath, filePath, -1));
        }
        return outlines;
    }

    private Map<Path, String> resolveCandidateFiles(Collection<String> rawRelPaths, boolean normalizeRelPath) {
        Map<Path, String> relPathByAbsPath = new LinkedHashMap<>();
        for (String relPath : rawRelPaths) {
            if (StringUtils.isBlank(relPath)) {
                continue;
            }
            try {
                Path resolved = resolveReadTarget(relPath).toAbsolutePath().normalize();
                if (Files.isRegularFile(resolved) && !relPathByAbsPath.containsKey(resolved)) {
                    String mappedRel = relPath;
                    if (normalizeRelPath) {
                        mappedRel = normalizeRelativePath(resolved, relPath);
                    }
                    relPathByAbsPath.put(resolved, mappedRel);
                    if (relPathByAbsPath.size() >= MAX_FIND_SYMBOL_FILES) {
                        break;
                    }
                }
            } catch (Exception e) {
                ExceptionUtils.rethrowIfCancelled(e, CANCELLED_MSG);
                log.debug("Skipping candidate file {}: {}", relPath, e.getMessage());
            }
        }
        return relPathByAbsPath;
    }

    static boolean isDeclarationInOutline(int line, List<SymbolInfo> outline, String trimmedQuery,
            String unqualifiedName) {
        for (SymbolInfo symbol : outline) {
            if (symbol.startLine() == line) {
                if (matchesSymbol(symbol, trimmedQuery) || symbol.name().equals(unqualifiedName)) {
                    return true;
                }
            }
        }
        return false;
    }

    static String findEnclosingSymbolName(List<SymbolInfo> outline, int line) {
        SymbolInfo best = null;
        int bestSpan = Integer.MAX_VALUE;
        for (SymbolInfo symbol : outline) {
            if (symbol.startLine() <= line && line <= symbol.endLine()) {
                int span = symbol.endLine() - symbol.startLine();
                if (best == null || span <= bestSpan) {
                    best = symbol;
                    bestSpan = span;
                }
            }
        }
        if (best == null) {
            return "";
        }
        if (best.scope().isEmpty()) {
            return best.name();
        }
        return best.scope() + DOT_CHAR + best.name();
    }

    static boolean isNonUsageSnippet(String trimmedSnippet) {
        if (trimmedSnippet == null || trimmedSnippet.isEmpty()) {
            return true;
        }
        if (trimmedSnippet.startsWith("//") || trimmedSnippet.startsWith("/*") || trimmedSnippet.startsWith("*")
                || trimmedSnippet.startsWith("*/")) {
            return true;
        }
        if (trimmedSnippet.startsWith("import ") || trimmedSnippet.startsWith(IMPORT_TAB_PREFIX)
                || trimmedSnippet.startsWith("package ") || trimmedSnippet.startsWith(PACKAGE_TAB_PREFIX)) {
            return true;
        }
        return trimmedSnippet.startsWith("from ") && trimmedSnippet.contains(" import ");
    }

    static boolean matchesTestNamingConvention(String candidateFileName, String stem) {
        if (candidateFileName == null || candidateFileName.isBlank() || stem == null || stem.isBlank()) {
            return false;
        }
        String candidateStem = stripExtension(candidateFileName);
        if (candidateStem.equals(stem + "Test") || candidateStem.equals(stem + "Tests")
                || candidateStem.equals(stem + "IT") || candidateStem.equals("Test" + stem)) {
            return true;
        }
        String lowerCandidateStem = candidateStem.toLowerCase(Locale.ROOT);
        String lowerStem = stem.toLowerCase(Locale.ROOT);
        return lowerCandidateStem.equals("test_" + lowerStem) || lowerCandidateStem.equals(lowerStem + "_test")
                || lowerCandidateStem.equals(lowerStem + "_tests") || lowerCandidateStem.equals(lowerStem + ".test")
                || lowerCandidateStem.equals(lowerStem + ".spec");
    }

    private List<String> collectCandidatePathsForFindSymbol(String searchPattern, String effectivePath) {
        if (!CURRENT_DIR.equals(effectivePath)) {
            List<String> scoped = grepService.findMatchingFiles(searchPattern, effectivePath, null);
            return Objects.requireNonNullElse(scoped, List.of());
        }
        List<String> combined = new ArrayList<>();
        if (projectService.hasActiveProject()) {
            List<String> projectMatches = grepService.findMatchingFiles(searchPattern);
            if (projectMatches != null) {
                combined.addAll(projectMatches);
            }
        }
        List<ReferenceFolderMount> mounts = Objects.requireNonNullElse(projectService.getReferenceFolderMounts(),
                List.of());
        for (ReferenceFolderMount mount : mounts) {
            if (mount != null) {
                List<String> mountMatches = grepService.findMatchingFiles(searchPattern, mount.aliasPrefix(), null);
                if (mountMatches != null) {
                    combined.addAll(mountMatches);
                }
            }
        }
        return combined;
    }

    private static String extractReferenceAliasPrefix(String normalizedPath) {
        if (!ProjectService.isReferencePath(normalizedPath)) {
            return "";
        }
        int slashIdx = normalizedPath.indexOf(SLASH_CHAR);
        if (slashIdx < 0) {
            return normalizedPath;
        }
        return normalizedPath.substring(0, slashIdx);
    }

    private static String stripReferenceAliasPrefix(String normalizedPath) {
        if (!ProjectService.isReferencePath(normalizedPath)) {
            return normalizedPath;
        }
        int slashIdx = normalizedPath.indexOf(SLASH_CHAR);
        if (slashIdx < 0) {
            return "";
        }
        return normalizedPath.substring(slashIdx + 1);
    }

    private Path resolveReadTarget(String relativePath) {
        Path resolved = projectService.resolveReadPath(relativePath);
        if (resolved == null && !ProjectService.isReferencePath(relativePath)) {
            return projectService.resolvePath(relativePath);
        }
        return resolved;
    }

    private String normalizeInputRelativePath(String relativePath) {
        String fallback = PathUtils.normalizeRelativePath(relativePath.trim());
        try {
            Path resolved = resolveReadTarget(relativePath);
            if (resolved != null) {
                return normalizeRelativePath(resolved.toAbsolutePath().normalize(), fallback);
            }
        } catch (Exception e) {
            ExceptionUtils.rethrowIfCancelled(e, CANCELLED_MSG);
        }
        return fallback;
    }

    private boolean isExistingRegularFile(String normalizedPath) {
        try {
            Path resolved = resolveReadTarget(normalizedPath);
            if (resolved != null && Files.isRegularFile(resolved)) {
                return true;
            }
        } catch (Exception e) {
            ExceptionUtils.rethrowIfCancelled(e, CANCELLED_MSG);
        }
        String refPrefix = extractReferenceAliasPrefix(normalizedPath);
        List<String> allFiles;
        if (refPrefix.isEmpty()) {
            allFiles = grepService.listFiles();
        } else {
            allFiles = grepService.listFiles(refPrefix);
        }
        if (allFiles != null) {
            for (String file : allFiles) {
                if (normalizedPath.equals(PathUtils.normalizeRelativePath(file))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String stripExtension(String fileName) {
        if (StringUtils.isEmpty(fileName) || fileName.lastIndexOf(DOT_CHAR) <= 0) {
            return StringUtils.defaultString(fileName);
        }
        return FilenameUtils.removeExtension(fileName);
    }

    private static String extractNormalizedSubpath(String normalizedPath, List<String> prefixes) {
        int lastSlash = normalizedPath.lastIndexOf(SLASH_CHAR);
        if (lastSlash <= 0) {
            return "";
        }
        String dirPath = normalizedPath.substring(0, lastSlash);
        for (String prefix : prefixes) {
            String prefixWithoutTrailing = prefix.substring(0, prefix.length() - 1);
            if (dirPath.equals(prefixWithoutTrailing)) {
                return "";
            }
            if (dirPath.startsWith(prefix)) {
                return dirPath.substring(prefix.length());
            }
        }
        return dirPath;
    }

    private static String formatLocationKey(String path, int line) {
        return path + COLON_CHAR + line;
    }

    private List<SymbolInfo> getFileOutlineWithLines(Path filePath, String relativePath, int totalLines) {
        Map<Path, List<CtagsEntry>> entriesByPath = ctagsRunner.runAndParse(List.of(filePath), CTAGS_OUTLINE_FLAGS,
                objectMapper, CANCELLED_MSG);
        List<CtagsEntry> entries = entriesByPath.getOrDefault(filePath, List.of());
        if (entries.isEmpty()) {
            return List.of();
        }
        return finalizeSymbolsForFile(entries, normalizeRelativePath(filePath, relativePath), filePath, totalLines);
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
        return ERROR_PREFIX + "Symbol '" + StringUtils.defaultString(symbolName) + "' not found in " + relativePath;
    }

    private static List<SymbolInfo> finalizeSymbolsForFile(List<CtagsEntry> entries, String relPath, Path filePath,
            int knownTotalLines) {
        List<CtagsEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(CtagsEntry::line).thenComparing(CtagsEntry::name));
        int totalLines = knownTotalLines;
        List<SymbolInfo> finalized = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            CtagsEntry current = sorted.get(i);
            if (current.end() < current.line() && totalLines < 0 && !hasSubsequentLineSymbol(sorted, i)) {
                totalLines = countLinesSafely(filePath);
            }
            int resolvedEnd = resolveEndLine(sorted, i, totalLines);
            finalized.add(new SymbolInfo(current.name(), current.kind(), relPath, current.line(), resolvedEnd,
                    current.scope(), current.signature()));
        }
        return finalized;
    }

    private static boolean hasSubsequentLineSymbol(List<CtagsEntry> sorted, int index) {
        int currentLine = sorted.get(index).line();
        for (int j = index + 1; j < sorted.size(); j++) {
            if (sorted.get(j).line() > currentLine) {
                return true;
            }
        }
        return false;
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
        Path path = resolveReadTarget(relativePath);
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException(
                    ERROR_PREFIX + "Path is not a regular file or does not exist: " + relativePath);
        }
        return path.toAbsolutePath().normalize();
    }

    private String normalizeRelativePath(Path resolvedPath, String fallbackRelativePath) {
        String formatted = projectService.formatReadDisplayPath(resolvedPath, fallbackRelativePath);
        if (StringUtils.isNotEmpty(formatted)) {
            return formatted;
        }
        Path root = projectService.getCurrentProjectRoot();
        if (root != null && resolvedPath != null) {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path normalizedTarget = resolvedPath.toAbsolutePath().normalize();
            if (normalizedTarget.startsWith(normalizedRoot)) {
                String rel = PathUtils.normalizeRelativePath(normalizedRoot.relativize(normalizedTarget).toString());
                if (!rel.isEmpty()) {
                    return rel;
                }
            }
        }
        return PathUtils.normalizeRelativePath(StringUtils.trimToEmpty(fallbackRelativePath));
    }

    private static int countLinesSafely(Path filePath) {
        try (Stream<String> lines = Files.lines(filePath)) {
            return (int) lines.count();
        } catch (Exception e) {
            return 0;
        }
    }
}
