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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.FileReadService;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Service to generate a token-optimized map of the repository. Uses ripgrep for tree generation and
 * ctags for skeletonization. Implements lazy evaluation to avoid redundant processing.
 */
@Service
public class RepoMapPackerService {
    private static final Logger log = LoggerFactory.getLogger(RepoMapPackerService.class);
    static final int CTAGS_BATCH_SIZE = 100;
    private static final String CANCELLED_MSG = "Repo map operation cancelled";
    private static final Set<String> KNOWN_BINARY_EXTENSIONS =
            Set.of(
                    "png",
                    "jpg",
                    "jpeg",
                    "gif",
                    "bmp",
                    "ico",
                    "webp",
                    "tiff",
                    "tif",
                    "psd",
                    "mp3",
                    "mp4",
                    "wav",
                    "ogg",
                    "flac",
                    "avi",
                    "mov",
                    "mkv",
                    "webm",
                    "zip",
                    "tar",
                    "gz",
                    "tgz",
                    "bz2",
                    "xz",
                    "7z",
                    "rar",
                    "zst",
                    "jar",
                    "war",
                    "ear",
                    "class",
                    "jmod",
                    "exe",
                    "dll",
                    "so",
                    "dylib",
                    "bin",
                    "o",
                    "a",
                    "lib",
                    "obj",
                    "pyc",
                    "pyo",
                    "pyd",
                    "wasm",
                    "pdf",
                    "doc",
                    "docx",
                    "xls",
                    "xlsx",
                    "ppt",
                    "pptx",
                    "odt",
                    "ods",
                    "odp",
                    "ttf",
                    "otf",
                    "woff",
                    "woff2",
                    "eot",
                    "sqlite",
                    "sqlite3",
                    "db",
                    "mdb",
                    "parquet",
                    "avro",
                    "orc",
                    "ds_store");

    private final ProjectService projectService;
    private final ObjectMapper objectMapper;
    private final GrepService grepService;
    private final ProjectCacheMetaService metaService;
    private final List<String> excludePatterns;
    private final AtomicLong generation = new AtomicLong(1);

    @FunctionalInterface
    interface CtagsRunner {
        ProcessResult run(List<String> command) throws IOException;
    }

    private final Map<Path, CachedSkeleton> skeletonCache = new ConcurrentHashMap<>();
    private CtagsRunner ctagsRunner = command -> ProcessRunner.run(command, null, false);

    private String cachedMap = null;
    private long cachedGeneration = 0;
    private boolean diskCacheEligible = true;

    public RepoMapPackerService(
            ProjectService projectService,
            ObjectMapper objectMapper,
            GrepService grepService,
            ProjectCacheMetaService metaService) {
        this.projectService = projectService;
        this.objectMapper = objectMapper;
        this.grepService = grepService;
        this.metaService = metaService;

        this.excludePatterns = List.of("src/main/resources/prompts", "src/main/resources/docs");
    }

    void setCtagsRunner(CtagsRunner ctagsRunner) {
        this.ctagsRunner = ctagsRunner;
    }

    @EventListener
    public void handleProjectChanged(ProjectChangedEvent event) {
        invalidateForProjectChange();
        log.debug("Repo map invalidated due to project change.");
    }

    @EventListener
    public void handleFileChanged(FileChangedEvent event) {
        invalidateForFileChange(event != null ? event.path() : null);
        log.debug("Repo map invalidated due to file change: {}", event != null ? event.path() : "");
    }

    @SuppressWarnings("PMD.NullAssignment")
    private synchronized void invalidateForProjectChange() {
        generation.incrementAndGet();
        cachedMap = null;
        cachedGeneration = 0;
        diskCacheEligible = true;
        skeletonCache.clear();
    }

    @SuppressWarnings("PMD.NullAssignment")
    private synchronized void invalidateForFileChange(String changedRelativePath) {
        generation.incrementAndGet();
        cachedMap = null;
        cachedGeneration = 0;
        diskCacheEligible = false;
        if (changedRelativePath != null && !changedRelativePath.isBlank()) {
            Path root = projectService.getCurrentProjectRoot();
            if (root != null) {
                Path resolved = root.resolve(changedRelativePath).toAbsolutePath().normalize();
                skeletonCache.remove(resolved);
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
        boolean checkDiskCache = false;
        synchronized (this) {
            if (cachedMap != null && cachedGeneration == startGeneration) {
                return new EditorResult(true, cachedMap, null);
            }
            if (diskCacheEligible) {
                checkDiskCache = true;
                diskCacheEligible = false;
            }
        }

        if (checkDiskCache) {
            Optional<String> persisted = metaService.loadRepoCache();
            if (persisted != null && persisted.isPresent() && !persisted.get().isBlank()) {
                String loaded = persisted.get();
                synchronized (this) {
                    if (generation.get() == startGeneration
                            && root.equals(projectService.getCurrentProjectRoot())) {
                        this.cachedMap = loaded;
                        this.cachedGeneration = startGeneration;
                    }
                }
                return new EditorResult(true, loaded, null);
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

        prefetchMissingSkeletons(root, files);

        StringBuilder sb = new StringBuilder();
        sb.append("# Repo Map Snapshot\n\n");
        sb.append("## Project Tree\n\n```\n");
        sb.append(FileTreeFormatter.format(files));
        sb.append("\n```\n\n");

        for (String relPath : files) {
            ExceptionUtils.checkInterrupted("Repo map generation cancelled");
            Path filePath = root.resolve(relPath);
            if (!Files.isRegularFile(filePath)) {
                continue;
            }
            try {
                String skeleton = getSkeleton(filePath);
                if (skeleton != null && !skeleton.isBlank()) {
                    sb.append("--- ").append(relPath).append(" ---\n");
                    sb.append(skeleton);
                    sb.append("\n\n");
                }
            } catch (Exception e) {
                ExceptionUtils.rethrowIfCancelled(e, CANCELLED_MSG);
                log.warn("Error processing file {}: {}", relPath, e.getMessage());
            }
        }

        String generatedMap = sb.toString();
        boolean cacheSaved = false;
        synchronized (this) {
            if (generation.get() == startGeneration
                    && root.equals(projectService.getCurrentProjectRoot())) {
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

    private void prefetchMissingSkeletons(Path root, List<String> relativeFiles) {
        List<FileStat> uncached = new ArrayList<>();
        for (String relPath : relativeFiles) {
            ExceptionUtils.checkInterrupted("Repo map generation cancelled");
            Path filePath = root.resolve(relPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(filePath)) {
                continue;
            }
            try {
                long size = Files.size(filePath);
                if (size <= 0) {
                    continue;
                }
                long lastModified = Files.getLastModifiedTime(filePath).toMillis();
                if (getValidCachedSkeleton(filePath, lastModified, size) == null) {
                    uncached.add(new FileStat(filePath, lastModified, size));
                }
            } catch (Exception e) {
                ExceptionUtils.rethrowIfCancelled(e, CANCELLED_MSG);
                log.debug(
                        "Failed to stat file for skeleton prefetch {}: {}",
                        filePath,
                        e.getMessage());
            }
        }

        for (int i = 0; i < uncached.size(); i += CTAGS_BATCH_SIZE) {
            int end = Math.min(i + CTAGS_BATCH_SIZE, uncached.size());
            populateSkeletonBatch(uncached.subList(i, end));
        }
    }

    private void populateSkeletonBatch(List<FileStat> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            List<String> command = new ArrayList<>(5 + batch.size());
            command.add("ctags");
            command.add("-f");
            command.add("-");
            command.add("--output-format=json");
            command.add("--fields=+n");
            for (FileStat stat : batch) {
                command.add(stat.path().toString());
            }

            ProcessResult result = ctagsRunner.run(command);
            if (result.timedOut()) {
                log.warn("Ctags timed out for batch of {} files", batch.size());
                return;
            }

            Map<Path, List<Tag>> tagsByPath = new HashMap<>();
            String output = result.output();
            if (output != null && !output.isEmpty()) {
                for (String line : output.lines().toList()) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        JsonNode node = objectMapper.readTree(line);
                        JsonNode nameNode = node.get("name");
                        JsonNode lineNode = node.get("line");
                        if (nameNode != null && lineNode != null) {
                            JsonNode pathNode = node.get("path");
                            Path targetPath = null;
                            if (pathNode != null && !pathNode.asText().isBlank()) {
                                targetPath =
                                        Path.of(pathNode.asText()).toAbsolutePath().normalize();
                            } else if (batch.size() == 1) {
                                targetPath = batch.get(0).path();
                            }
                            if (targetPath != null) {
                                tagsByPath
                                        .computeIfAbsent(targetPath, k -> new ArrayList<>())
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
                List<Tag> tags = tagsByPath.get(stat.path());
                String skeleton = formatSkeletonLines(stat.path(), tags);
                skeletonCache.put(
                        stat.path(),
                        new CachedSkeleton(
                                stat.lastModifiedMillis(),
                                stat.size(),
                                skeleton != null ? skeleton : ""));
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
            if (size <= 0) {
                return null;
            }
            long lastModified = Files.getLastModifiedTime(normalized).toMillis();
            CachedSkeleton cached = getValidCachedSkeleton(normalized, lastModified, size);
            if (cached != null) {
                return cached.skeleton().isEmpty() ? null : cached.skeleton();
            }

            populateSkeletonBatch(List.of(new FileStat(normalized, lastModified, size)));
            cached = getValidCachedSkeleton(normalized, lastModified, size);
            if (cached != null) {
                return cached.skeleton().isEmpty() ? null : cached.skeleton();
            }
            return null;
        } catch (Exception e) {
            ExceptionUtils.rethrowIfCancelled(e, CANCELLED_MSG);
            log.warn("Ctags failed for {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    private CachedSkeleton getValidCachedSkeleton(Path path, long lastModifiedMillis, long size) {
        CachedSkeleton cached = skeletonCache.get(path);
        if (cached != null
                && cached.lastModifiedMillis() == lastModifiedMillis
                && cached.size() == size) {
            return cached;
        }
        return null;
    }

    private static String formatSkeletonLines(Path filePath, List<Tag> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        try {
            tags.sort(Comparator.comparingInt(t -> t.line));
            List<String> lines = Files.readAllLines(filePath);
            StringBuilder skeleton = new StringBuilder();
            int lastLine = -1;
            for (Tag tag : tags) {
                if (tag.line > 0 && tag.line <= lines.size() && tag.line != lastLine) {
                    skeleton.append(tag.line)
                            .append(": ")
                            .append(lines.get(tag.line - 1).trim())
                            .append('\n');
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
        for (String pattern : excludePatterns) {
            if (filePath.startsWith(pattern)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasKnownBinaryExtension(Path path) {
        if (path == null) {
            return false;
        }
        Path fileNamePath = path.getFileName();
        if (fileNamePath == null) {
            return false;
        }
        String fileName = fileNamePath.toString();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return false;
        }
        String ext = fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        return KNOWN_BINARY_EXTENSIONS.contains(ext);
    }

    private boolean isBinaryFile(Path path) {
        if (hasKnownBinaryExtension(path)) {
            return true;
        }
        try {
            return FileReadService.isBinaryFile(path);
        } catch (Exception e) {
            ExceptionUtils.rethrowIfCancelled(e, CANCELLED_MSG);
            log.warn("Could not determine if file is binary: {}", path, e);
            return false;
        }
    }

    private record Tag(int line) {}

    private record FileStat(Path path, long lastModifiedMillis, long size) {}

    private record CachedSkeleton(long lastModifiedMillis, long size, String skeleton) {}
}
