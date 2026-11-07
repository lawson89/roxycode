package org.roxycode.app.ai.services;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared Universal Ctags CLI runner and JSON line parser used by {@link SymbolService} and
 * {@code RepoMapPackerService}.
 */
@FunctionalInterface
public interface CtagsRunner {

    Logger LOG = LoggerFactory.getLogger(CtagsRunner.class);
    Set<String> IGNORED_TAG_KINDS = Set.of("package", "namespace", "import");
    CtagsRunner DEFAULT = command -> ProcessRunner.run(command, null, false);

    int SINGLE_FILE_COUNT = 1;
    int MIN_LINE_NUMBER = 1;
    String TAG_TYPE = "tag";

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CtagsEntry(@JsonProperty("_type") String type, String name, String path, String kind, int line, int end,
            String scope, String signature) {
    }

    ProcessResult run(List<String> command);

    default Map<Path, List<CtagsEntry>> runAndParse(List<Path> files, List<String> extraFlags,
            ObjectMapper objectMapper, String cancelMessage) {
        Map<Path, List<CtagsEntry>> results = new LinkedHashMap<>();
        if (files == null || files.isEmpty()) {
            return results;
        }
        ObjectMapper mapper = Objects.requireNonNullElse(objectMapper, AiConfig.SHARED_OBJECT_MAPPER);
        int flagCount = Objects.requireNonNullElse(extraFlags, List.of()).size();
        List<String> command = new ArrayList<>(files.size() + flagCount + 4);
        command.add("ctags");
        command.add("-f");
        command.add("-");
        command.add("--output-format=json");
        if (extraFlags != null) {
            for (String flag : extraFlags) {
                if (flag != null && !flag.isBlank()) {
                    command.add(flag);
                }
            }
        }
        for (Path file : files) {
            command.add(file.toString());
        }

        ProcessResult procResult = run(command);
        if (procResult.timedOut()) {
            LOG.warn("Universal Ctags timed out for {} file(s)", files.size());
            return results;
        }
        if (procResult.exitCode() != 0 && !procResult.errorOutput().isBlank()) {
            LOG.warn("Universal Ctags execution failed: {}", procResult.errorOutput());
        }
        for (String line : procResult.outputLines()) {
            if (!line.isBlank()) {
                parseJsonLine(line, files, mapper, cancelMessage, results);
            }
        }
        return results;
    }

    private static void parseJsonLine(String line, List<Path> files, ObjectMapper mapper, String cancelMessage,
            Map<Path, List<CtagsEntry>> results) {
        try {
            CtagsEntry entry = mapper.readValue(line, CtagsEntry.class);
            if (entry.type() != null && !TAG_TYPE.equals(entry.type())) {
                return;
            }
            if (entry.name() == null || entry.line() < MIN_LINE_NUMBER) {
                return;
            }
            if (entry.kind() != null && !entry.kind().isEmpty()
                    && IGNORED_TAG_KINDS.contains(entry.kind().toLowerCase(Locale.ROOT))) {
                return;
            }
            Path targetPath = resolveTargetPath(entry.path(), files);
            if (targetPath != null) {
                results.computeIfAbsent(targetPath, k -> new ArrayList<>()).add(entry);
            }
        } catch (Exception e) {
            ExceptionUtils.rethrowIfCancelled(e, cancelMessage);
            LOG.trace("Skipping unparseable ctags JSON line: {}", line);
        }
    }

    static Path resolveTargetPath(String rawPath, List<Path> files) {
        if (StringUtils.isNotBlank(rawPath)) {
            return Path.of(rawPath).toAbsolutePath().normalize();
        }
        if (files.size() == SINGLE_FILE_COUNT) {
            return files.get(0);
        }
        return null;
    }

    static List<SymbolInfo> finalizeSymbolsForFile(List<CtagsEntry> entries, String relPath, Path filePath,
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

    static boolean hasSubsequentLineSymbol(List<CtagsEntry> sorted, int index) {
        int currentLine = sorted.get(index).line();
        for (int j = index + 1; j < sorted.size(); j++) {
            if (sorted.get(j).line() > currentLine) {
                return true;
            }
        }
        return false;
    }

    static int resolveEndLine(List<CtagsEntry> sorted, int index, int totalLines) {
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

    static int countLinesSafely(Path filePath) {
        try (Stream<String> lines = Files.lines(filePath)) {
            return (int) lines.count();
        } catch (Exception e) {
            return 0;
        }
    }
}
