package org.roxycode.app.ai.services;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    char BACKSLASH_CHAR = 92;
    char DQUOTE_CHAR = 34;
    String END_ANCHOR_COMMENT = "$/;" + DQUOTE_CHAR;
    String END_COMMENT = "/;" + DQUOTE_CHAR;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CtagsEntry(@JsonProperty("_type") String type, String name, String path, String kind, int line, int end,
            String scope, String signature, String pattern) {
    }

    ProcessResult run(List<String> command);

    default Map<Path, List<CtagsEntry>> runAndParse(List<Path> files, String fieldsArg, ObjectMapper objectMapper,
            String cancelMessage) {
        List<String> flags = List.of();
        if (fieldsArg != null && !fieldsArg.isBlank()) {
            flags = List.of(fieldsArg);
        }
        return runAndParse(files, flags, objectMapper, cancelMessage);
    }

    default Map<Path, List<CtagsEntry>> runAndParse(List<Path> files, List<String> extraFlags,
            ObjectMapper objectMapper, String cancelMessage) {
        Map<Path, List<CtagsEntry>> results = new LinkedHashMap<>();
        if (files == null || files.isEmpty()) {
            return results;
        }
        ObjectMapper mapper = AiConfig.SHARED_OBJECT_MAPPER;
        if (objectMapper != null) {
            mapper = objectMapper;
        }
        int flagCount = 0;
        if (extraFlags != null) {
            flagCount = extraFlags.size();
        }
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

    static String extractPatternText(String rawPattern) {
        if (rawPattern == null || rawPattern.isBlank()) {
            return "";
        }
        String text = rawPattern.trim();
        if (text.startsWith("/^")) {
            text = text.substring(2);
        } else if (text.startsWith("/")) {
            text = text.substring(1);
        }
        if (text.endsWith(END_ANCHOR_COMMENT)) {
            text = text.substring(0, text.length() - 4);
        } else if (text.endsWith(END_COMMENT)) {
            text = text.substring(0, text.length() - 3);
        } else if (text.endsWith("$/")) {
            text = text.substring(0, text.length() - 2);
        } else if (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == BACKSLASH_CHAR && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (next == '/' || next == BACKSLASH_CHAR) {
                    sb.append(next);
                    i++;
                    continue;
                }
            }
            sb.append(ch);
        }
        return sb.toString();
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
        if (rawPath != null && !rawPath.isBlank()) {
            return Path.of(rawPath).toAbsolutePath().normalize();
        }
        if (files.size() == SINGLE_FILE_COUNT) {
            return files.get(0);
        }
        return null;
    }
}
