package org.roxycode.app.ai.services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.ui.MarkdownPane.ChatTranscriptEntry;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service providing a Two-Tier Agent Memory system supporting Global Memory (~/.roxycode/memory/)
 * and Project Memory (<projectDataDir>/memory/), topic notes with YAML frontmatter, archived plans,
 * archived Q&A topics, and unified RipGrep search.
 */
@Service
@AgentService(
        value = "memoryService",
        phases = {"*"})
@AgentDoc(
        "Provides two-tier Global and Project Agent Memory (MEMORY.md cheat-sheets, frontmatter topic notes, archived plans, archived Q&A topics, and unified RipGrep search) across all workflow phases.")
public class MemoryService {
    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    static final String MEMORY_DIR = "memory";
    static final String MEMORY_INDEX_FILE = "MEMORY.md";
    static final String NOTES_SUBDIR = "notes";
    static final String ARCHIVE_PLANS_SUBDIR = PlanService.ARCHIVE_PLANS_SUBDIR;
    public static final String ARCHIVE_TOPICS_SUBDIR = "archive/topics";
    static final int MAX_SLUG_LENGTH = 50;
    static final int MAX_PINNED_MEMORY_LINES = 200;
    static final int MAX_PINNED_MEMORY_CHARS = 8192;
    private static final int SEARCH_CONTEXT_LINES = 2;

    private static final String SCOPE_GLOBAL = "global";
    private static final String SCOPE_PROJECT = "project";
    private static final String NO_ACTIVE_PROJECT = "No active project";
    private static final String MD_EXTENSION = ".md";
    private static final String FRONTMATTER_DELIMITER = "---";

    private static final char DQUOTE = 34;
    private static final char BACKSLASH = 92;
    private static final String DQUOTE_STR = String.valueOf(DQUOTE);
    private static final String BACKSLASH_STR = String.valueOf(BACKSLASH);
    private static final String ESCAPED_DQUOTE = BACKSLASH_STR + DQUOTE_STR;

    private final SettingsService settingsService;
    private final ProjectService projectService;

    public MemoryService(SettingsService settingsService, ProjectService projectService) {
        this.settingsService = settingsService;
        this.projectService = projectService;
    }

    /** Reads the pinned global memory cheat-sheet (~/.roxycode/memory/MEMORY.md). */
    @AgentDoc(
            value =
                    "Reads the pinned global memory cheat-sheet (~/.roxycode/memory/MEMORY.md). Returns empty string if not present.",
            examples = "memoryService.readGlobalMemory()")
    public String readGlobalMemory() {
        Path globalDir = getGlobalMemoryDir();
        if (globalDir == null) {
            return "";
        }
        return readFileIfExists(globalDir.resolve(MEMORY_INDEX_FILE));
    }

    /** Writes or overwrites the pinned global memory cheat-sheet (~/.roxycode/memory/MEMORY.md). */
    @AgentDoc(
            value =
                    "Writes or overwrites the pinned global memory cheat-sheet (~/.roxycode/memory/MEMORY.md).",
            examples =
                    "memoryService.updateGlobalMemory('# Global Preferences\n- Prefer concise code')")
    public EditorResult updateGlobalMemory(
            @AgentDoc("The Markdown content for global MEMORY.md") String content) {
        Path globalDir = getGlobalMemoryDir();
        if (globalDir == null) {
            return new EditorResult(false, "", "Global memory directory is unavailable");
        }
        return writeMarkdownFile(
                globalDir.resolve(MEMORY_INDEX_FILE),
                content != null ? content : "",
                "Global MEMORY.md updated successfully");
    }

    /** Reads the pinned project memory cheat-sheet (<projectDataDir>/memory/MEMORY.md). */
    @AgentDoc(
            value =
                    "Reads the pinned project memory cheat-sheet (<projectDataDir>/memory/MEMORY.md). Returns empty string if not present or no active project.",
            examples = "memoryService.readProjectMemory()")
    public String readProjectMemory() {
        Path projectDir = getProjectMemoryDir();
        if (projectDir == null) {
            return "";
        }
        return readFileIfExists(projectDir.resolve(MEMORY_INDEX_FILE));
    }

    /**
     * Writes or overwrites the pinned project memory cheat-sheet
     * (<projectDataDir>/memory/MEMORY.md).
     */
    @AgentDoc(
            value =
                    "Writes or overwrites the pinned project memory cheat-sheet (<projectDataDir>/memory/MEMORY.md).",
            examples =
                    "memoryService.updateProjectMemory('# Project Architecture\n- Uses Java 21 and Spring Boot')")
    public EditorResult updateProjectMemory(
            @AgentDoc("The Markdown content for project MEMORY.md") String content) {
        Path projectDir = getProjectMemoryDir();
        if (projectDir == null) {
            return new EditorResult(false, "", NO_ACTIVE_PROJECT);
        }
        return writeMarkdownFile(
                projectDir.resolve(MEMORY_INDEX_FILE),
                content != null ? content : "",
                "Project MEMORY.md updated successfully");
    }

    /** Saves a global topic note with YAML frontmatter in ~/.roxycode/memory/notes/<slug>.md. */
    @AgentDoc(
            value =
                    "Saves or overwrites a global topic note with structured frontmatter in ~/.roxycode/memory/notes/<slug>.md.",
            examples =
                    "memoryService.saveGlobalNote('coding-style', 'Coding Style Preferences', 'style, java', 'Use final fields where possible.')")
    public EditorResult saveGlobalNote(
            @AgentDoc("The note slug (lowercase alphanumeric and hyphens)") String slug,
            @AgentDoc("The note title") String title,
            @AgentDoc("Comma-separated tags") String tags,
            @AgentDoc("The Markdown note body content") String content) {
        Path globalDir = getGlobalMemoryDir();
        if (globalDir == null) {
            return new EditorResult(false, "", "Global memory directory is unavailable");
        }
        return saveNote(globalDir, SCOPE_GLOBAL, slug, title, tags, content);
    }

    /**
     * Saves a project topic note with YAML frontmatter in <projectDataDir>/memory/notes/<slug>.md.
     */
    @AgentDoc(
            value =
                    "Saves or overwrites a project topic note with structured frontmatter in <projectDataDir>/memory/notes/<slug>.md.",
            examples =
                    "memoryService.saveProjectNote('swing-html-tables', 'Swing HTML 3.2 Table Layout Rules', 'ui, swing, html', 'Use HTML tables for layout.')")
    public EditorResult saveProjectNote(
            @AgentDoc("The note slug (lowercase alphanumeric and hyphens)") String slug,
            @AgentDoc("The note title") String title,
            @AgentDoc("Comma-separated tags") String tags,
            @AgentDoc("The Markdown note body content") String content) {
        Path projectDir = getProjectMemoryDir();
        if (projectDir == null) {
            return new EditorResult(false, "", NO_ACTIVE_PROJECT);
        }
        return saveNote(projectDir, SCOPE_PROJECT, slug, title, tags, content);
    }

    /** Reads a global topic note from ~/.roxycode/memory/notes/<slug>.md. */
    @AgentDoc(
            value = "Reads a global topic note from ~/.roxycode/memory/notes/<slug>.md.",
            examples = "memoryService.readGlobalNote('coding-style')")
    public String readGlobalNote(@AgentDoc("The note slug") String slug) {
        String sanitized = sanitizeSlug(slug);
        Path globalDir = getGlobalMemoryDir();
        if (globalDir == null) {
            return "";
        }
        return readFileIfExists(globalDir.resolve(NOTES_SUBDIR).resolve(sanitized + MD_EXTENSION));
    }

    /** Reads a project topic note from <projectDataDir>/memory/notes/<slug>.md. */
    @AgentDoc(
            value = "Reads a project topic note from <projectDataDir>/memory/notes/<slug>.md.",
            examples = "memoryService.readProjectNote('swing-html-tables')")
    public String readProjectNote(@AgentDoc("The note slug") String slug) {
        String sanitized = sanitizeSlug(slug);
        Path projectDir = getProjectMemoryDir();
        if (projectDir == null) {
            return "";
        }
        return readFileIfExists(projectDir.resolve(NOTES_SUBDIR).resolve(sanitized + MD_EXTENSION));
    }

    /** Deletes a global topic note from ~/.roxycode/memory/notes/<slug>.md. */
    @AgentDoc(
            value =
                    "Deletes an obsolete global topic note from ~/.roxycode/memory/notes/<slug>.md.",
            examples = "memoryService.deleteGlobalNote('old-note')")
    public EditorResult deleteGlobalNote(@AgentDoc("The note slug to delete") String slug) {
        String sanitized = sanitizeSlug(slug);
        Path globalDir = getGlobalMemoryDir();
        if (globalDir == null) {
            return new EditorResult(false, "", "Global memory directory is unavailable");
        }
        return deleteNoteFile(
                globalDir.resolve(NOTES_SUBDIR).resolve(sanitized + MD_EXTENSION), sanitized);
    }

    /** Deletes a project topic note from <projectDataDir>/memory/notes/<slug>.md. */
    @AgentDoc(
            value =
                    "Deletes an obsolete project topic note from <projectDataDir>/memory/notes/<slug>.md.",
            examples = "memoryService.deleteProjectNote('old-note')")
    public EditorResult deleteProjectNote(@AgentDoc("The note slug to delete") String slug) {
        String sanitized = sanitizeSlug(slug);
        Path projectDir = getProjectMemoryDir();
        if (projectDir == null) {
            return new EditorResult(false, "", NO_ACTIVE_PROJECT);
        }
        return deleteNoteFile(
                projectDir.resolve(NOTES_SUBDIR).resolve(sanitized + MD_EXTENSION), sanitized);
    }

    /** Lists all global topic notes with parsed frontmatter metadata. */
    @AgentDoc(
            value =
                    "Returns a list of formatted summary lines for each global note in ~/.roxycode/memory/notes/*.md.",
            examples = "memoryService.listGlobalNotes()")
    public List<String> listGlobalNotes() {
        Path globalDir = getGlobalMemoryDir();
        if (globalDir == null) {
            return List.of();
        }
        return listNotesInDir(globalDir.resolve(NOTES_SUBDIR));
    }

    /** Lists all project topic notes with parsed frontmatter metadata. */
    @AgentDoc(
            value =
                    "Returns a list of formatted summary lines for each project note in <projectDataDir>/memory/notes/*.md.",
            examples = "memoryService.listProjectNotes()")
    public List<String> listProjectNotes() {
        Path projectDir = getProjectMemoryDir();
        if (projectDir == null) {
            return List.of();
        }
        return listNotesInDir(projectDir.resolve(NOTES_SUBDIR));
    }

    /** Lists archived implementation plans from <projectDataDir>/archive/plans/*.md. */
    @AgentDoc(
            value =
                    "Lists archived completed implementation plans from <projectDataDir>/archive/plans/*.md (most recent first).",
            examples = "memoryService.listArchivedPlans()")
    public List<String> listArchivedPlans() {
        return listArchivedMarkdownSummaries(getArchivedPlansDir(), "plans");
    }

    /** Reads an archived implementation plan from <projectDataDir>/archive/plans/<filename>. */
    @AgentDoc(
            value =
                    "Reads an archived implementation plan from <projectDataDir>/archive/plans/<filename>.",
            examples = "memoryService.readArchivedPlan('20250514-153000-feature-plan.md')")
    public String readArchivedPlan(
            @AgentDoc("The archived plan filename (with or without .md)") String filename) {
        String cleanName = sanitizeArchiveFilename(filename);
        Path archiveDir = getArchivedPlansDir();
        if (archiveDir == null) {
            return "";
        }
        Path target = archiveDir.resolve(cleanName).toAbsolutePath().normalize();
        if (!target.startsWith(archiveDir)) {
            throw new IllegalArgumentException("Invalid archived plan filename: " + filename);
        }
        return readFileIfExists(target);
    }

    /** Lists archived Q&A topics from <projectDataDir>/archive/topics/*.md. */
    @AgentDoc(
            value =
                    "Lists archived Q&A topic conversations from <projectDataDir>/archive/topics/*.md (most recent first).",
            examples = "memoryService.listArchivedTopics()")
    public List<String> listArchivedTopics() {
        return listArchivedMarkdownSummaries(getArchivedTopicsDir(), "topics");
    }

    /** Reads an archived Q&A topic from <projectDataDir>/archive/topics/<filename>. */
    @AgentDoc(
            value =
                    "Reads an archived Q&A topic conversation from <projectDataDir>/archive/topics/<filename>.",
            examples = "memoryService.readArchivedTopic('20250514-153000-memory-architecture.md')")
    public String readArchivedTopic(
            @AgentDoc("The archived topic filename (with or without .md)") String filename) {
        String cleanName = sanitizeArchiveFilename(filename);
        Path archiveDir = getArchivedTopicsDir();
        if (archiveDir == null) {
            return "";
        }
        Path target = archiveDir.resolve(cleanName).toAbsolutePath().normalize();
        if (!target.startsWith(archiveDir)) {
            throw new IllegalArgumentException("Invalid archived topic filename: " + filename);
        }
        return readFileIfExists(target);
    }

    /**
     * Archives a Q&A topic conversation transcript with YAML frontmatter to {@code
     * <projectDataDir>/archive/topics/<YYYYMMDD-HHmmss>-<slug>.md}.
     *
     * @param title the topic title
     * @param user the user who created/archived the topic
     * @param isoTimestamp the ISO-8601 timestamp when archived
     * @param entries the non-empty conversation transcript entries
     * @return the saved archive filename (e.g. {@code 20250514-153000-topic.md}), or {@code null}
     *     if skipped or failed
     */
    public String archiveTopicConversation(
            String title, String user, String isoTimestamp, List<ChatTranscriptEntry> entries) {
        if (entries == null
                || entries.isEmpty()
                || projectService == null
                || !projectService.hasActiveProject()) {
            return null;
        }
        Path archiveDir = getArchivedTopicsDir();
        if (archiveDir == null) {
            return null;
        }
        try {
            Files.createDirectories(archiveDir);

            String resolvedTitle =
                    (title != null && !title.isBlank())
                            ? title.trim()
                            : AiService.DEFAULT_TOPIC_TITLE;
            String slug = PlanService.sanitizePlanTitle(resolvedTitle);
            if ("plan".equals(slug) && !resolvedTitle.toLowerCase(Locale.ROOT).contains("plan")) {
                slug = "topic";
            }

            String baseId = PlanService.formatArchiveTimestamp(isoTimestamp) + "-" + slug;
            String id = baseId;
            Path targetFile = archiveDir.resolve(id + MD_EXTENSION);
            int suffix = 2;
            while (Files.exists(targetFile)) {
                id = baseId + "-" + suffix;
                targetFile = archiveDir.resolve(id + MD_EXTENSION);
                suffix++;
            }

            String projectName = projectService.getProjectName();
            Path projectRoot = projectService.getCurrentProjectRoot();
            String projectPath =
                    projectRoot != null ? projectRoot.toAbsolutePath().normalize().toString() : "";
            String resolvedUser = (user != null && !user.isBlank()) ? user.trim() : "User";
            String resolvedTimestamp =
                    (isoTimestamp != null && !isoTimestamp.isBlank())
                            ? isoTimestamp.trim()
                            : Instant.now().toString();

            StringBuilder md = new StringBuilder();
            md.append(
                    """
                    ---
                    id: "%s"
                    type: "topic"
                    project: "%s"
                    projectPath: "%s"
                    title: "%s"
                    createdBy: "%s"
                    archivedOn: "%s"
                    messageCount: %d
                    ---

                    # Topic: %s

                    ## Conversation Transcript
                    """
                            .formatted(
                                    escapeFrontmatter(id),
                                    escapeFrontmatter(projectName),
                                    escapeFrontmatter(projectPath),
                                    escapeFrontmatter(resolvedTitle),
                                    escapeFrontmatter(resolvedUser),
                                    escapeFrontmatter(resolvedTimestamp),
                                    entries.size(),
                                    resolvedTitle));

            for (ChatTranscriptEntry entry : entries) {
                String speaker = "user".equalsIgnoreCase(entry.role()) ? "User" : "Roxy";
                md.append("\n### ").append(speaker);
                if (entry.timestamp() != null && !entry.timestamp().isBlank()) {
                    md.append(" (").append(entry.timestamp().trim()).append(")");
                }
                md.append("\n");
                String content = entry.content() != null ? entry.content().stripTrailing() : "";
                md.append(content).append("\n");
            }

            Files.writeString(targetFile, md.toString(), StandardCharsets.UTF_8);
            return targetFile.getFileName().toString();
        } catch (Exception e) {
            log.warn("Failed to archive Q&A topic conversation: {}", e.getMessage());
            return null;
        }
    }

    private List<String> listArchivedMarkdownSummaries(Path archiveDir, String label) {
        if (archiveDir == null || !Files.isDirectory(archiveDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(archiveDir)) {
            List<Path> files =
                    stream.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(MD_EXTENSION))
                            .sorted(
                                    Comparator.comparing(
                                                    (Path p) -> p.getFileName().toString(),
                                                    String.CASE_INSENSITIVE_ORDER)
                                            .reversed())
                            .toList();
            List<String> summaries = new ArrayList<>(files.size());
            for (Path file : files) {
                String filename = file.getFileName().toString();
                Map<String, String> fm = parseFrontmatter(file);
                String title = fm.getOrDefault("title", "");
                if (!title.isBlank()) {
                    summaries.add(filename + ": " + title);
                } else {
                    summaries.add(filename);
                }
            }
            return List.copyOf(summaries);
        } catch (IOException e) {
            log.warn("Failed to list archived {} in {}: {}", label, archiveDir, e.getMessage());
            return List.of();
        }
    }

    /**
     * Searches across Global Memory, Project Memory, Archived Plans, and Archived Topics using
     * RipGrep.
     *
     * @param pattern the regex pattern to search for
     * @return the search results with [GLOBAL], [PROJECT], and [ARCHIVE] prefixes
     */
    @AgentDoc(
            value =
                    "Searches across Global Memory (~/.roxycode/memory/), Project Memory (<projectDataDir>/memory/), Archived Plans (<projectDataDir>/archive/plans/), and Archived Topics (<projectDataDir>/archive/topics/) using RipGrep.",
            examples = "memoryService.search('swing')")
    public EditorResult search(@AgentDoc("The regex pattern to search for") String pattern) {
        return executeSearch(pattern, false);
    }

    /**
     * Searches case-insensitively across Global Memory, Project Memory, Archived Plans, and
     * Archived Topics using RipGrep.
     *
     * @param pattern the regex pattern to search for case-insensitively
     * @return the search results with [GLOBAL], [PROJECT], and [ARCHIVE] prefixes
     */
    @AgentDoc(
            value =
                    "Searches case-insensitively across Global Memory, Project Memory, Archived Plans, and Archived Topics using RipGrep.",
            examples = "memoryService.searchIgnoreCase('table layout')")
    public EditorResult searchIgnoreCase(
            @AgentDoc("The regex pattern to search for case-insensitively") String pattern) {
        return executeSearch(pattern, true);
    }

    private EditorResult executeSearch(String pattern, boolean ignoreCase) {
        if (pattern == null || pattern.isEmpty()) {
            return new EditorResult(false, "", "Search pattern must not be empty");
        }

        Path globalDir = getGlobalMemoryDir();
        Path projectDir = getProjectMemoryDir();
        Path archiveDir = getArchivedPlansDir();
        Path archiveTopicsDir = getArchivedTopicsDir();

        List<Path> existingDirs = new ArrayList<>(4);
        if (globalDir != null && Files.isDirectory(globalDir)) {
            existingDirs.add(globalDir);
        }
        if (projectDir != null && Files.isDirectory(projectDir)) {
            existingDirs.add(projectDir);
        }
        if (archiveDir != null && Files.isDirectory(archiveDir)) {
            existingDirs.add(archiveDir);
        }
        if (archiveTopicsDir != null && Files.isDirectory(archiveTopicsDir)) {
            existingDirs.add(archiveTopicsDir);
        }

        if (existingDirs.isEmpty()) {
            return new EditorResult(true, "", null);
        }

        List<String> command = new ArrayList<>();
        command.add("rg");
        command.add("-n");
        command.add("-H");
        command.add("--no-heading");
        command.add("--color");
        command.add("never");
        command.add("-C");
        command.add(String.valueOf(SEARCH_CONTEXT_LINES));
        if (ignoreCase) {
            command.add("-i");
        }
        command.add("-e");
        command.add(pattern);
        for (Path dir : existingDirs) {
            command.add(dir.toString());
        }

        try {
            ProcessResult result = ProcessRunner.run(command, null, true);
            if (result.timedOut()) {
                log.warn("Memory RipGrep search timed out for pattern: {}", pattern);
                return new EditorResult(false, "", "RipGrep search timed out");
            }

            int exitCode = result.exitCode();
            final int exitCodeNoMatches = 1;
            if (exitCode == exitCodeNoMatches) {
                return new EditorResult(true, "", null);
            } else if (exitCode != 0) {
                String error = result.output().trim();
                log.warn("Memory RipGrep failed with exit code {}: {}", exitCode, error);
                return new EditorResult(false, "", "RipGrep error (" + exitCode + "): " + error);
            }

            String normalized =
                    normalizeSearchOutput(
                            result.output().trim(),
                            globalDir,
                            projectDir,
                            archiveDir,
                            archiveTopicsDir);
            String capped = GrepService.capOutputLines(normalized, GrepService.MAX_OUTPUT_LINES);
            return new EditorResult(true, capped, null);
        } catch (IOException e) {
            log.warn("Failed to execute RipGrep for memory search: {}", e.getMessage());
            return new EditorResult(false, "", "Error executing RipGrep: " + e.getMessage());
        }
    }

    static String normalizeSearchOutput(
            String rawOutput, Path globalDir, Path projectDir, Path archiveDir) {
        return normalizeSearchOutput(rawOutput, globalDir, projectDir, archiveDir, null);
    }

    static String normalizeSearchOutput(
            String rawOutput,
            Path globalDir,
            Path projectDir,
            Path archiveDir,
            Path archiveTopicsDir) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return "";
        }
        String globalPrefix = toSlashPrefix(globalDir);
        String projectPrefix = toSlashPrefix(projectDir);
        String archivePrefix = toSlashPrefix(archiveDir);
        String archiveTopicsPrefix = toSlashPrefix(archiveTopicsDir);

        List<String> lines = rawOutput.lines().toList();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            String line = lines.get(i);
            String slashLine = line.replace(BACKSLASH, '/');
            if (!globalPrefix.isEmpty() && slashLine.startsWith(globalPrefix)) {
                sb.append("[GLOBAL] ").append(slashLine.substring(globalPrefix.length()));
            } else if (!projectPrefix.isEmpty() && slashLine.startsWith(projectPrefix)) {
                sb.append("[PROJECT] ").append(slashLine.substring(projectPrefix.length()));
            } else if (!archivePrefix.isEmpty() && slashLine.startsWith(archivePrefix)) {
                sb.append("[ARCHIVE] plans/").append(slashLine.substring(archivePrefix.length()));
            } else if (!archiveTopicsPrefix.isEmpty()
                    && slashLine.startsWith(archiveTopicsPrefix)) {
                sb.append("[ARCHIVE] topics/")
                        .append(slashLine.substring(archiveTopicsPrefix.length()));
            } else {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String toSlashPrefix(Path dir) {
        if (dir == null) {
            return "";
        }
        String normalized = dir.toAbsolutePath().normalize().toString().replace(BACKSLASH, '/');
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    /**
     * Sanitizes a note slug to lowercase alphanumeric and hyphens (max 50 chars), stripping any
     * trailing {@code .md} suffix and rejecting path traversal.
     *
     * @param rawSlug the raw note slug
     * @return the sanitized slug
     */
    public static String sanitizeSlug(String rawSlug) {
        if (rawSlug == null || rawSlug.isBlank()) {
            throw new IllegalArgumentException("Note slug must not be blank");
        }
        if (rawSlug.contains("..") || rawSlug.contains("/") || rawSlug.contains(BACKSLASH_STR)) {
            throw new IllegalArgumentException(
                    "Invalid note slug (path traversal not allowed): " + rawSlug);
        }
        String trimmed = rawSlug.trim();
        if (trimmed.toLowerCase(Locale.ROOT).endsWith(MD_EXTENSION)) {
            trimmed = trimmed.substring(0, trimmed.length() - MD_EXTENSION.length()).trim();
        }
        if (trimmed.contains("..") || trimmed.contains("/") || trimmed.contains(BACKSLASH_STR)) {
            throw new IllegalArgumentException(
                    "Invalid note slug (path traversal not allowed): " + rawSlug);
        }
        String sanitized =
                trimmed.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("(^-+|-+$)", "");
        if (sanitized.length() > MAX_SLUG_LENGTH) {
            sanitized = sanitized.substring(0, MAX_SLUG_LENGTH).replaceAll("-+$", "");
        }
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException(
                    "Note slug must contain alphanumeric characters: " + rawSlug);
        }
        return sanitized;
    }

    static String sanitizeArchiveFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Archived filename must not be blank");
        }
        String trimmed = filename.trim();
        int colonIdx = trimmed.indexOf(':');
        if (colonIdx > 0) {
            trimmed = trimmed.substring(0, colonIdx).trim();
        }
        if (trimmed.contains("..") || trimmed.contains("/") || trimmed.contains(BACKSLASH_STR)) {
            throw new IllegalArgumentException(
                    "Invalid archived filename (path traversal not allowed): " + filename);
        }
        if (!trimmed.toLowerCase(Locale.ROOT).endsWith(MD_EXTENSION)) {
            trimmed = trimmed + MD_EXTENSION;
        }
        return trimmed;
    }

    /**
     * Caps pinned MEMORY.md content at {@link #MAX_PINNED_MEMORY_LINES} lines and {@link
     * #MAX_PINNED_MEMORY_CHARS} characters for system prompt injection.
     *
     * @param content the raw MEMORY.md content
     * @return the capped content
     */
    public static String capPinnedMemoryForPrompt(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String result = content;
        boolean truncated = false;
        List<String> lines = result.lines().toList();
        if (lines.size() > MAX_PINNED_MEMORY_LINES) {
            result = String.join("\n", lines.subList(0, MAX_PINNED_MEMORY_LINES));
            truncated = true;
        }
        if (result.length() > MAX_PINNED_MEMORY_CHARS) {
            result = result.substring(0, MAX_PINNED_MEMORY_CHARS);
            truncated = true;
        }
        if (truncated) {
            result =
                    result
                            + "\n... (MEMORY.md truncated at 200 lines / 8 KB limit; move detailed content into topic notes)";
        }
        return result;
    }

    private EditorResult saveNote(
            Path memoryRoot,
            String scope,
            String rawSlug,
            String title,
            String tags,
            String content) {
        String sanitizedSlug = sanitizeSlug(rawSlug);
        String resolvedTitle = (title != null && !title.isBlank()) ? title.trim() : sanitizedSlug;
        String resolvedTags = tags != null ? tags.trim() : "";
        String updatedAt = Instant.now().toString();
        String body = content != null ? content.stripLeading() : "";

        StringBuilder md = new StringBuilder();
        md.append(
                """
                ---
                id: "%s"
                scope: "%s"
                title: "%s"
                tags: "%s"
                updatedAt: "%s"
                ---

                """
                        .formatted(
                                escapeFrontmatter(sanitizedSlug),
                                escapeFrontmatter(scope),
                                escapeFrontmatter(resolvedTitle),
                                escapeFrontmatter(resolvedTags),
                                escapeFrontmatter(updatedAt)));

        if (!body.startsWith("# ") && !body.startsWith("#\t")) {
            md.append("# ").append(resolvedTitle).append("\n\n");
        }
        md.append(body);
        if (!body.endsWith("\n")) {
            md.append("\n");
        }

        Path notePath = memoryRoot.resolve(NOTES_SUBDIR).resolve(sanitizedSlug + MD_EXTENSION);
        return writeMarkdownFile(
                notePath,
                md.toString(),
                "Saved " + scope + " note: notes/" + sanitizedSlug + MD_EXTENSION);
    }

    private EditorResult deleteNoteFile(Path notePath, String sanitizedSlug) {
        try {
            if (!Files.exists(notePath)) {
                return new EditorResult(
                        false, "", "Note does not exist: notes/" + sanitizedSlug + MD_EXTENSION);
            }
            Files.delete(notePath);
            return new EditorResult(
                    true, "Deleted note: notes/" + sanitizedSlug + MD_EXTENSION, null);
        } catch (IOException e) {
            log.warn("Failed to delete note {}: {}", notePath, e.getMessage());
            return new EditorResult(false, "", "Failed to delete note: " + e.getMessage());
        }
    }

    private List<String> listNotesInDir(Path notesDir) {
        if (notesDir == null || !Files.isDirectory(notesDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(notesDir)) {
            List<Path> files =
                    stream.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(MD_EXTENSION))
                            .sorted(
                                    Comparator.comparing(
                                            p -> p.getFileName().toString(),
                                            String.CASE_INSENSITIVE_ORDER))
                            .toList();
            List<String> summaries = new ArrayList<>(files.size());
            for (Path file : files) {
                String filename = file.getFileName().toString();
                Map<String, String> fm = parseFrontmatter(file);
                String title =
                        fm.getOrDefault("title", StringUtils.removeEnd(filename, MD_EXTENSION));
                String tags = fm.getOrDefault("tags", "");
                String updatedAt = fm.getOrDefault("updatedAt", "");
                StringBuilder line = new StringBuilder();
                line.append(filename).append(" - ").append(DQUOTE).append(title).append(DQUOTE);
                line.append(" [").append(tags).append("]");
                if (!updatedAt.isEmpty()) {
                    line.append(" (").append(updatedAt).append(")");
                }
                summaries.add(line.toString());
            }
            return List.copyOf(summaries);
        } catch (IOException e) {
            log.warn("Failed to list notes in {}: {}", notesDir, e.getMessage());
            return List.of();
        }
    }

    static Map<String, String> parseFrontmatter(Path file) {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !FRONTMATTER_DELIMITER.equals(lines.get(0).trim())) {
                return map;
            }
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (FRONTMATTER_DELIMITER.equals(line)) {
                    break;
                }
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String key = line.substring(0, colon).trim();
                    String val = line.substring(colon + 1).trim();
                    if (val.length() >= 2
                            && val.startsWith(DQUOTE_STR)
                            && val.endsWith(DQUOTE_STR)) {
                        val =
                                val.substring(1, val.length() - 1)
                                        .replace(ESCAPED_DQUOTE, DQUOTE_STR);
                    }
                    map.put(key, val);
                }
            }
        } catch (IOException e) {
            log.debug("Failed to parse frontmatter from {}: {}", file, e.getMessage());
        }
        return map;
    }

    private static EditorResult writeMarkdownFile(
            Path path, String content, String successMessage) {
        try {
            PathUtils.createParentDirectories(path);
            Files.writeString(path, content, StandardCharsets.UTF_8);
            return new EditorResult(true, successMessage, null);
        } catch (IOException e) {
            log.error("Failed to write memory file {}: {}", path, e.getMessage());
            return new EditorResult(false, "", "Failed to write memory file: " + e.getMessage());
        }
    }

    private static String readFileIfExists(Path path) {
        if (path == null || !Files.exists(path) || !Files.isRegularFile(path)) {
            return "";
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read memory file {}: {}", path, e.getMessage());
            return "";
        }
    }

    private static String escapeFrontmatter(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(DQUOTE_STR, ESCAPED_DQUOTE)
                .replace("\r\n", " ")
                .replace('\n', ' ')
                .replace('\r', ' ');
    }

    Path getGlobalMemoryDir() {
        if (settingsService == null) {
            return null;
        }
        Path roxyHome = settingsService.getRoxyHomeDir();
        if (roxyHome == null) {
            return null;
        }
        return roxyHome.resolve(MEMORY_DIR).toAbsolutePath().normalize();
    }

    Path getProjectMemoryDir() {
        if (projectService == null || !projectService.hasActiveProject()) {
            return null;
        }
        Path dir = projectService.resolveProjectDataPath(MEMORY_DIR);
        return dir != null ? dir.toAbsolutePath().normalize() : null;
    }

    Path getArchivedPlansDir() {
        if (projectService == null || !projectService.hasActiveProject()) {
            return null;
        }
        Path dir = projectService.resolveProjectDataPath(ARCHIVE_PLANS_SUBDIR);
        return dir != null ? dir.toAbsolutePath().normalize() : null;
    }

    Path getArchivedTopicsDir() {
        if (projectService == null || !projectService.hasActiveProject()) {
            return null;
        }
        Path dir = projectService.resolveProjectDataPath(ARCHIVE_TOPICS_SUBDIR);
        return dir != null ? dir.toAbsolutePath().normalize() : null;
    }
}
