package org.roxycode.app.ai.services.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.roxycode.app.ai.services.CtagsRunner;
import org.roxycode.app.ai.services.CtagsRunner.CtagsEntry;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.service.ProjectService;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.util.ExceptionUtils;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class RepoMapPackerService {
    private static final Logger log = LoggerFactory.getLogger(RepoMapPackerService.class);
    static final int CTAGS_BATCH_SIZE = 100;
    private static final String CANCELLED_MSG = "Repo map operation cancelled";
    private static final String CTAGS_SKELETON_FIELDS = "--fields=+n";
    private static final char LF = 10;
    static final int MAX_SIGNATURE_CONTINUATION_LINES = 8;
    static final int MAX_SIGNATURE_CHARS = 240;
    private static final char SQUOTE = 39;
    private static final char DQUOTE = 34;
    private static final char BACKSLASH = 92;
    private static final char OPEN_PAREN = '(';
    private static final char CLOSE_PAREN = ')';
    private static final char OPEN_BRACE = '{';
    private static final char SEMICOLON = ';';
    private static final char COMMA = ',';
    private static final char COLON = ':';
    private static final char EQUALS_SIGN = '=';
    private static final char SLASH = '/';
    private static final char AT_SIGN = '@';
    private static final char SPACE = ' ';
    private static final Pattern INLINE_PARAM_ANNOTATION_PATTERN = Pattern
            .compile("@(?:ToolParam|AgentDoc)\\s*(?:\\((?:\"[^\"]*\"|\'[^\']*\'|[^()])*\\))?\\s*");
    private static final List<String> HEADER_CONTINUATION_PREFIXES = List.of("throws ", "{", "implements ", "extends ",
            "permits ");

    static final Set<String> SOURCE_EXTENSIONS = Set
            .of("java,py,ts,tsx,js,jsx,go,rs,c,cpp,cc,cxx,h,hpp,cs,rb,php,kt,kts,swift,scala".split(","));
    static final Set<String> IGNORED_TAG_KINDS = CtagsRunner.IGNORED_TAG_KINDS;
    private static final List<String> TEST_DIR_MARKERS = List.of("src/test/", "test/", "tests/", "__tests__/");
    private static final List<String> TEST_FILE_SUFFIXES = List.of(
            "Test.java,Tests.java,IT.java,_test.py,_test.go,.test.ts,.spec.ts,.test.tsx,.spec.tsx,.test.js,.spec.js,.test.jsx,.spec.jsx"
                    .split(","));

    private final ProjectService projectService;
    private final ObjectMapper objectMapper;
    private final GrepService grepService;
    private final ProjectCacheMetaService metaService;
    private final List<String> excludePatterns = List.of("src/main/resources/prompts", "src/main/resources/docs");
    private final AtomicLong generation = new AtomicLong(1);

    static final int MAX_SKELETON_CACHE_SIZE = 10_000;
    private final Cache<Path, CachedSkeleton> skeletonCache = Caffeine.newBuilder().maximumSize(MAX_SKELETON_CACHE_SIZE)
            .build();
    private final Cache<Long, String> repoMapCache = Caffeine.newBuilder().maximumSize(1).build();
    private CtagsRunner ctagsRunner = CtagsRunner.DEFAULT;

    public RepoMapPackerService(ProjectService projectService, ObjectMapper objectMapper, GrepService grepService,
            ProjectCacheMetaService metaService) {
        this.projectService = projectService;
        this.objectMapper = objectMapper;
        this.grepService = grepService;
        this.metaService = metaService;
    }

    void setCtagsRunner(CtagsRunner ctagsRunner) {
        this.ctagsRunner = ctagsRunner;
    }

    @EventListener
    public void handleProjectChanged(ProjectChangedEvent event) {
        invalidateCache();
        log.debug("Repo map invalidated due to project change.");
    }

    @EventListener
    public void handleFileChanged(FileChangedEvent event) {
        invalidateForFileChange(event != null ? event.path() : null);
        log.debug("Repo map invalidated due to file change: {}", event != null ? event.path() : "");
    }

    public void invalidateCache() {
        synchronized (this) {
            generation.incrementAndGet();
            repoMapCache.invalidateAll();
            skeletonCache.invalidateAll();
        }
        metaService.deleteRepoCache();
    }

    public EditorResult forceRegenerateRepoMap() {
        invalidateCache();
        return generateRepoMap();
    }

    private synchronized void invalidateForFileChange(String changedRelativePath) {
        generation.incrementAndGet();
        repoMapCache.invalidateAll();
        if (changedRelativePath != null && !changedRelativePath.isBlank()) {
            Path root = projectService.getCurrentProjectRoot();
            if (root != null) {
                skeletonCache.invalidate(root.resolve(changedRelativePath).toAbsolutePath().normalize());
            }
        }
    }

    public EditorResult generateRepoMap() {
        ExceptionUtils.checkInterrupted("Repo map generation cancelled");
        Path root = projectService.getCurrentProjectRoot();
        if (root == null || !projectService.hasActiveProject()) {
            return new EditorResult(false, "", "No active project");
        }

        long startGeneration = generation.get();
        String existingMap = repoMapCache.getIfPresent(startGeneration);
        if (existingMap != null) {
            return new EditorResult(true, existingMap, null);
        }

        List<String> rawFiles = grepService.listFiles(null);
        List<String> files = new ArrayList<>();
        if (rawFiles != null) {
            for (String f : rawFiles) {
                if (!isExcluded(f)) {
                    files.add(f);
                }
            }
        }
        files.sort(Comparator.naturalOrder());

        prefetchMissingSkeletons(root, files);

        StringBuilder sb = new StringBuilder("# Repo Map Snapshot").append(LF);
        for (String relPath : files) {
            ExceptionUtils.checkInterrupted("Repo map generation cancelled");
            Path filePath = root.resolve(relPath);
            if (!Files.isRegularFile(filePath))
                continue;
            sb.append(relPath).append(LF);
            if (shouldGenerateSkeleton(relPath)) {
                try {
                    String skeleton = getSkeleton(filePath);
                    if (skeleton != null && !skeleton.isBlank()) {
                        sb.append(skeleton);
                    }
                } catch (Exception e) {
                    ExceptionUtils.rethrowIfCancelled(e, CANCELLED_MSG);
                    log.warn("Error processing file {}: {}", relPath, e.getMessage());
                }
            }
        }

        String generatedMap = sb.toString();
        boolean cacheSaved = false;
        synchronized (this) {
            if (generation.get() == startGeneration && root.equals(projectService.getCurrentProjectRoot())) {
                repoMapCache.put(startGeneration, generatedMap);
                cacheSaved = true;
            }
        }
        if (cacheSaved) {
            metaService.saveRepoCache(generatedMap);
        }
        return new EditorResult(true, generatedMap, null);
    }

    static boolean isSourceFile(String relPath) {
        String ext = PathUtils.getLowerExtension(relPath, false);
        return !ext.isEmpty() && SOURCE_EXTENSIONS.contains(ext);
    }

    static boolean isTestFile(String relPath) {
        if (relPath == null || relPath.isBlank())
            return false;
        String normalized = FilenameUtils.separatorsToUnix(relPath);
        for (String marker : TEST_DIR_MARKERS) {
            if (normalized.startsWith(marker) || normalized.contains("/" + marker)) {
                return true;
            }
        }
        String fileName = PathUtils.getFileName(normalized);
        if (fileName.startsWith("test_") && fileName.endsWith(".py")) {
            return true;
        }
        return TEST_FILE_SUFFIXES.stream().anyMatch(fileName::endsWith);
    }

    static boolean shouldGenerateSkeleton(String relPath) {
        return isSourceFile(relPath) && !isTestFile(relPath);
    }

    private void prefetchMissingSkeletons(Path root, List<String> relativeFiles) {
        List<FileStat> uncached = new ArrayList<>();
        for (String relPath : relativeFiles) {
            ExceptionUtils.checkInterrupted("Repo map generation cancelled");
            if (!shouldGenerateSkeleton(relPath))
                continue;
            Path filePath = root.resolve(relPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(filePath))
                continue;
            try {
                long size = Files.size(filePath);
                if (size <= 0)
                    continue;
                long lastModified = Files.getLastModifiedTime(filePath).toMillis();
                if (getValidCachedSkeleton(filePath, lastModified, size) == null) {
                    uncached.add(new FileStat(filePath, lastModified, size));
                }
            } catch (Exception e) {
                ExceptionUtils.rethrowIfCancelled(e, CANCELLED_MSG);
                log.debug("Failed to stat file for skeleton prefetch {}: {}", filePath, e.getMessage());
            }
        }

        for (int i = 0; i < uncached.size(); i += CTAGS_BATCH_SIZE) {
            populateSkeletonBatch(uncached.subList(i, Math.min(i + CTAGS_BATCH_SIZE, uncached.size())));
        }
    }

    private void populateSkeletonBatch(List<FileStat> batch) {
        if (batch.isEmpty()) {
            return;
        }
        List<Path> paths = batch.stream().map(FileStat::path).toList();
        Map<Path, List<CtagsEntry>> entriesByPath = ctagsRunner.runAndParse(paths, CTAGS_SKELETON_FIELDS, objectMapper,
                CANCELLED_MSG);
        for (FileStat stat : batch) {
            String skeleton = formatSkeletonLines(stat.path(), entriesByPath.get(stat.path()));
            skeletonCache.put(stat.path(),
                    new CachedSkeleton(stat.lastModifiedMillis(), stat.size(), skeleton != null ? skeleton : ""));
        }
    }

    private String getSkeleton(Path filePath) {
        try {
            Path normalized = filePath.toAbsolutePath().normalize();
            long size = Files.size(normalized);
            if (size <= 0)
                return null;
            long lastModified = Files.getLastModifiedTime(normalized).toMillis();
            CachedSkeleton cached = getValidCachedSkeleton(normalized, lastModified, size);
            if (cached == null) {
                populateSkeletonBatch(List.of(new FileStat(normalized, lastModified, size)));
                cached = getValidCachedSkeleton(normalized, lastModified, size);
            }
            return (cached != null && !cached.skeleton().isEmpty()) ? cached.skeleton() : null;
        } catch (Exception e) {
            ExceptionUtils.rethrowIfCancelled(e, CANCELLED_MSG);
            log.warn("Ctags failed for {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    private CachedSkeleton getValidCachedSkeleton(Path path, long lastModifiedMillis, long size) {
        CachedSkeleton cached = skeletonCache.getIfPresent(path);
        return (cached != null && cached.lastModifiedMillis() == lastModifiedMillis && cached.size() == size)
                ? cached
                : null;
    }

    static String formatSkeletonLines(Path filePath, List<CtagsEntry> tags) {
        if (tags == null || tags.isEmpty())
            return null;
        try {
            tags.sort(Comparator.comparingInt(CtagsEntry::line));
            List<String> lines = Files.readAllLines(filePath);
            StringBuilder skeleton = new StringBuilder();
            int lastLine = 0;
            for (CtagsEntry tag : tags) {
                int tagLine = tag.line();
                if (tagLine > lastLine && tagLine <= lines.size()) {
                    CollapsedSignature collapsed = collapseSignatureWithEndLine(lines, tagLine - 1);
                    if (!collapsed.text().isEmpty()) {
                        skeleton.append("  ").append(tagLine).append(": ").append(collapsed.text()).append(LF);
                    }
                    lastLine = collapsed.endLineIndex() + 1;
                }
            }
            return skeleton.toString();
        } catch (Exception e) {
            ExceptionUtils.rethrowIfCancelled(e, CANCELLED_MSG);
            log.warn("Failed to format skeleton for {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    record CollapsedSignature(String text, int endLineIndex) {
    }

    static String collapseSignature(List<String> lines, int startIndex) {
        return collapseSignatureWithEndLine(lines, startIndex).text();
    }

    static CollapsedSignature collapseSignatureWithEndLine(List<String> lines, int startIndex) {
        if (lines == null || startIndex < 0 || startIndex >= lines.size()) {
            return new CollapsedSignature("", startIndex);
        }
        String firstLine = stripLineComment(lines.get(startIndex)).trim();
        if (firstLine.isEmpty()) {
            return new CollapsedSignature("", startIndex);
        }

        int maxIndex = Math.min(lines.size() - 1, startIndex + MAX_SIGNATURE_CONTINUATION_LINES);
        StringBuilder joined = new StringBuilder();
        int parenDepth = 0;
        boolean sawOpenParen = false;
        boolean sawTerminator = false;
        boolean sawEquals = false;
        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;
        int endLineIndex = startIndex;

        for (int i = startIndex; i <= maxIndex; i++) {
            String line = (i == startIndex) ? firstLine : stripLineComment(lines.get(i)).trim();
            endLineIndex = i;
            if (!line.isEmpty()) {
                appendSignatureSegment(joined, line);
                int idx = 0;
                int len = line.length();
                while (idx < len) {
                    char c = line.charAt(idx);
                    if (c == BACKSLASH && (inDoubleQuote || inSingleQuote)) {
                        idx += 2;
                        continue;
                    }
                    if (!inSingleQuote && c == DQUOTE) {
                        inDoubleQuote = !inDoubleQuote;
                    } else if (!inDoubleQuote && c == SQUOTE) {
                        inSingleQuote = !inSingleQuote;
                    } else if (!inDoubleQuote && !inSingleQuote) {
                        if (c == OPEN_PAREN) {
                            parenDepth++;
                            sawOpenParen = true;
                        } else if (c == CLOSE_PAREN && parenDepth > 0) {
                            parenDepth--;
                        } else if (c == EQUALS_SIGN && parenDepth == 0) {
                            sawEquals = true;
                        } else if ((c == OPEN_BRACE || c == SEMICOLON) && (parenDepth == 0 || idx == len - 1)) {
                            sawTerminator = true;
                        }
                    }
                    idx++;
                }
            }

            if (!shouldContinueSignature(lines, i, maxIndex, parenDepth, sawOpenParen, sawTerminator, sawEquals,
                    firstLine)) {
                break;
            }
        }

        String cleaned = normalizeSignatureWhitespace(stripParameterAnnotations(joined.toString()));
        String abbreviated = StringUtils.abbreviate(cleaned, MAX_SIGNATURE_CHARS);
        return new CollapsedSignature(abbreviated, endLineIndex);
    }

    private static boolean shouldContinueSignature(List<String> lines, int currentIndex, int maxIndex, int parenDepth,
            boolean sawOpenParen, boolean sawTerminator, boolean sawEquals, String firstLine) {
        if (sawTerminator || currentIndex >= maxIndex) {
            return false;
        }
        if (parenDepth > 0) {
            return true;
        }
        String nextTrimmed = stripLineComment(lines.get(currentIndex + 1)).trim();
        if (nextTrimmed.isEmpty()) {
            return false;
        }
        if (startsWithContinuationClause(nextTrimmed)) {
            return true;
        }
        return !sawOpenParen && !sawEquals && firstLine.charAt(firstLine.length() - 1) != COLON
                && firstLine.charAt(0) != AT_SIGN;
    }

    private static boolean startsWithContinuationClause(String trimmedLine) {
        for (String prefix : HEADER_CONTINUATION_PREFIXES) {
            if (trimmedLine.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static void appendSignatureSegment(StringBuilder joined, String segment) {
        if (joined.isEmpty()) {
            joined.append(segment);
            return;
        }
        char prev = joined.charAt(joined.length() - 1);
        char next = segment.charAt(0);
        if (prev != OPEN_PAREN && next != CLOSE_PAREN && next != COMMA && next != SEMICOLON) {
            joined.append(SPACE);
        }
        joined.append(segment);
    }

    private static String stripLineComment(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        boolean inDouble = false;
        boolean inSingle = false;
        int i = 0;
        int len = line.length();
        while (i < len) {
            char c = line.charAt(i);
            if (c == BACKSLASH && (inDouble || inSingle)) {
                i += 2;
                continue;
            }
            if (!inSingle && c == DQUOTE) {
                inDouble = !inDouble;
            } else if (!inDouble && c == SQUOTE) {
                inSingle = !inSingle;
            } else if (!inDouble && !inSingle && c == SLASH && i + 1 < len && line.charAt(i + 1) == SLASH) {
                return line.substring(0, i);
            }
            i++;
        }
        return line;
    }

    private static String stripParameterAnnotations(String text) {
        return INLINE_PARAM_ANNOTATION_PATTERN.matcher(text).replaceAll("");
    }

    private static String normalizeSignatureWhitespace(String text) {
        return StringUtils.normalizeSpace(text).replaceAll("\\s*([),;])", "$1").replace("( ", "(");
    }

    private boolean isExcluded(String filePath) {
        return excludePatterns.stream().anyMatch(filePath::startsWith);
    }

    private record FileStat(Path path, long lastModifiedMillis, long size) {
    }

    private record CachedSkeleton(long lastModifiedMillis, long size, String skeleton) {
    }
}
