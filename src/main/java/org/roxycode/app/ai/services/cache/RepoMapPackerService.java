package org.roxycode.app.ai.services.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.FileReadService;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.ProjectService;
import org.apache.commons.io.FilenameUtils;
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
    private static final int SINGLE_FILE_BATCH_SIZE = 1;
    private static final String CANCELLED_MSG = "Repo map operation cancelled";
    private static final char LF = '\n';

    static final Set<String> SOURCE_EXTENSIONS = Set
            .of("java,py,ts,tsx,js,jsx,go,rs,c,cpp,cc,cxx,h,hpp,cs,rb,php,kt,kts,swift,scala".split(","));
    static final Set<String> KNOWN_TEXT_EXTENSIONS = Stream
            .concat(SOURCE_EXTENSIONS.stream(), Stream.of(
                    "md,txt,json,xml,yml,yaml,toml,properties,css,html,sql,sh,bat,cmd,gradle,gitignore,csv".split(",")))
            .collect(Collectors.toUnmodifiableSet());
    static final Set<String> IGNORED_TAG_KINDS = Set.of("package", "namespace", "import");
    private static final Set<String> KNOWN_BINARY_EXTENSIONS = Set.of(
            "png,jpg,jpeg,gif,bmp,ico,webp,tiff,tif,psd,mp3,mp4,wav,ogg,flac,avi,mov,mkv,webm,zip,tar,gz,tgz,bz2,xz,7z,rar,zst,jar,war,ear,class,jmod,exe,dll,so,dylib,bin,o,a,lib,obj,pyc,pyo,pyd,wasm,pdf,doc,docx,xls,xlsx,ppt,pptx,odt,ods,odp,ttf,otf,woff,woff2,eot,sqlite,sqlite3,db,mdb,parquet,avro,orc,ds_store"
                    .split(","));
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

    @FunctionalInterface
    interface CtagsRunner {
        ProcessResult run(List<String> command) throws IOException;
    }

    static final int MAX_SKELETON_CACHE_SIZE = 10_000;
    private final Cache<Path, CachedSkeleton> skeletonCache = Caffeine.newBuilder().maximumSize(MAX_SKELETON_CACHE_SIZE)
            .build();
    private CtagsRunner ctagsRunner = command -> ProcessRunner.run(command, null, false);

    private String cachedMap = null;
    private long cachedGeneration = 0;

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

    @SuppressWarnings("PMD.NullAssignment")
    public void invalidateCache() {
        synchronized (this) {
            generation.incrementAndGet();
            cachedMap = null;
            cachedGeneration = 0;
            skeletonCache.invalidateAll();
        }
        metaService.deleteRepoCache();
    }

    public EditorResult forceRegenerateRepoMap() {
        invalidateCache();
        return generateRepoMap();
    }

    @SuppressWarnings("PMD.NullAssignment")
    private synchronized void invalidateForFileChange(String changedRelativePath) {
        generation.incrementAndGet();
        cachedMap = null;
        cachedGeneration = 0;
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
        synchronized (this) {
            if (cachedMap != null && cachedGeneration == startGeneration) {
                return new EditorResult(true, cachedMap, null);
            }
        }

        List<String> rawFiles = grepService.listFiles(null);
        List<String> files = new ArrayList<>();
        if (rawFiles != null) {
            for (String f : rawFiles) {
                if (!isExcluded(f) && !isBinaryFile(root.resolve(f))) {
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
                this.cachedMap = generatedMap;
                this.cachedGeneration = startGeneration;
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
        if (batch.isEmpty())
            return;
        try {
            List<String> command = new ArrayList<>(List.of("ctags", "-f", "-", "--output-format=json", "--fields=+n"));
            batch.forEach(stat -> command.add(stat.path().toString()));

            ProcessResult result = ctagsRunner.run(command);
            if (result.timedOut()) {
                log.warn("Ctags timed out for batch of {} files", batch.size());
                return;
            }

            Map<Path, List<Tag>> tagsByPath = new HashMap<>();
            String output = result.output();
            if (output != null && !output.isEmpty()) {
                for (String line : output.lines().toList()) {
                    if (line.isBlank())
                        continue;
                    try {
                        JsonNode node = objectMapper.readTree(line);
                        JsonNode kindNode = node.get("kind");
                        if (kindNode != null
                                && IGNORED_TAG_KINDS.contains(kindNode.asText().toLowerCase(Locale.ROOT))) {
                            continue;
                        }
                        JsonNode nameNode = node.get("name");
                        JsonNode lineNode = node.get("line");
                        if (nameNode != null && lineNode != null) {
                            JsonNode pathNode = node.get("path");
                            Path targetPath = null;
                            if (pathNode != null && !pathNode.asText().isBlank()) {
                                targetPath = Path.of(pathNode.asText()).toAbsolutePath().normalize();
                            } else if (batch.size() == SINGLE_FILE_BATCH_SIZE) {
                                targetPath = batch.get(0).path();
                            }
                            if (targetPath != null) {
                                tagsByPath.computeIfAbsent(targetPath, k -> new ArrayList<>())
                                        .add(new Tag(lineNode.asInt()));
                            }
                        }
                    } catch (Exception e) {
                        ExceptionUtils.rethrowIfCancelled(e, CANCELLED_MSG);
                        log.trace("Skip unparseable line: {}", line);
                    }
                }
            }

            for (FileStat stat : batch) {
                String skeleton = formatSkeletonLines(stat.path(), tagsByPath.get(stat.path()));
                skeletonCache.put(stat.path(),
                        new CachedSkeleton(stat.lastModifiedMillis(), stat.size(), skeleton != null ? skeleton : ""));
            }
        } catch (Exception e) {
            ExceptionUtils.rethrowIfCancelled(e, CANCELLED_MSG);
            log.warn("Ctags batch failed: {}", e.getMessage());
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

    private static String formatSkeletonLines(Path filePath, List<Tag> tags) {
        if (tags == null || tags.isEmpty())
            return null;
        try {
            tags.sort(Comparator.comparingInt(t -> t.line));
            List<String> lines = Files.readAllLines(filePath);
            StringBuilder skeleton = new StringBuilder();
            int lastLine = -1;
            for (Tag tag : tags) {
                if (tag.line > 0 && tag.line <= lines.size() && tag.line != lastLine) {
                    skeleton.append("  ").append(tag.line).append(": ").append(lines.get(tag.line - 1).trim())
                            .append(LF);
                    lastLine = tag.line;
                }
            }
            return skeleton.toString();
        } catch (Exception e) {
            ExceptionUtils.rethrowIfCancelled(e, CANCELLED_MSG);
            log.warn("Failed to format skeleton for {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    private boolean isExcluded(String filePath) {
        return excludePatterns.stream().anyMatch(filePath::startsWith);
    }

    static boolean hasKnownTextExtension(Path path) {
        return hasExtensionInSet(path, KNOWN_TEXT_EXTENSIONS);
    }

    static boolean hasKnownBinaryExtension(Path path) {
        return hasExtensionInSet(path, KNOWN_BINARY_EXTENSIONS);
    }

    private static boolean hasExtensionInSet(Path path, Set<String> extensions) {
        String ext = PathUtils.getLowerExtension(path, true);
        return !ext.isEmpty() && extensions.contains(ext);
    }

    private boolean isBinaryFile(Path path) {
        if (hasKnownTextExtension(path))
            return false;
        if (hasKnownBinaryExtension(path))
            return true;
        try {
            return FileReadService.isBinaryFile(path);
        } catch (Exception e) {
            ExceptionUtils.rethrowIfCancelled(e, CANCELLED_MSG);
            log.warn("Could not determine if file is binary: {}", path, e);
            return false;
        }
    }

    private record Tag(int line) {
    }

    private record FileStat(Path path, long lastModifiedMillis, long size) {
    }

    private record CachedSkeleton(long lastModifiedMillis, long size, String skeleton) {
    }
}
