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
import org.roxycode.app.util.FrontmatterUtils;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AgentService(value = "memoryService", phases = {"*"})
@AgentDoc("Provides two-tier Global and Project Agent Memory (MEMORY.md cheat-sheets, frontmatter topic notes, archived plans, archived Q&A topics, and unified RipGrep search) across all workflow phases.")
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
    private static final int MAX_EXCERPT_ENTRIES = 8;
    private static final int MAX_ENTRY_CHARS = 500;
    private static final char LF = 10;

    private static final String SCOPE_GLOBAL = "global";
    private static final String SCOPE_PROJECT = "project";
    private static final String NO_ACTIVE_PROJECT = "No active project";
    private static final String GLOBAL_DIR_UNAVAILABLE = "Global memory directory is unavailable";
    private static final String MD_EXTENSION = ".md";

    private record PrefixLabel(String prefix, String label) {
    }

    private final SettingsService settingsService;
    private final ProjectService projectService;

    public MemoryService(SettingsService settingsService, ProjectService projectService) {
        this.settingsService = settingsService;
        this.projectService = projectService;
    }

    @AgentDoc(value = "Reads the pinned global memory cheat-sheet (~/.roxycode/memory/MEMORY.md). Returns empty string if not present.", examples = "memoryService.readGlobalMemory()")
    public String readGlobalMemory() {
        return readMemory(getGlobalMemoryDir());
    }

    @AgentDoc(value = "Writes or overwrites the pinned global memory cheat-sheet (~/.roxycode/memory/MEMORY.md).", examples = "memoryService.updateGlobalMemory('# Global Preferences\n- Prefer concise code')")
    public EditorResult updateGlobalMemory(@AgentDoc("The Markdown content for global MEMORY.md") String content) {
        return updateMemory(getGlobalMemoryDir(), GLOBAL_DIR_UNAVAILABLE, "Global MEMORY.md updated successfully",
                content);
    }

    @AgentDoc(value = "Reads the pinned project memory cheat-sheet (<projectDataDir>/memory/MEMORY.md). Returns empty string if not present or no active project.", examples = "memoryService.readProjectMemory()")
    public String readProjectMemory() {
        return readMemory(getProjectMemoryDir());
    }

    @AgentDoc(value = "Writes or overwrites the pinned project memory cheat-sheet (<projectDataDir>/memory/MEMORY.md).", examples = "memoryService.updateProjectMemory('# Project Architecture\n- Uses Java 21 and Spring Boot')")
    public EditorResult updateProjectMemory(@AgentDoc("The Markdown content for project MEMORY.md") String content) {
        return updateMemory(getProjectMemoryDir(), NO_ACTIVE_PROJECT, "Project MEMORY.md updated successfully",
                content);
    }

    @AgentDoc(value = "Saves or overwrites a global topic note with structured frontmatter in ~/.roxycode/memory/notes/<slug>.md.", examples = "memoryService.saveGlobalNote('coding-style', 'Coding Style Preferences', 'style, java', 'Use final fields where possible.')")
    public EditorResult saveGlobalNote(@AgentDoc("The note slug (lowercase alphanumeric and hyphens)") String slug,
            @AgentDoc("The note title") String title, @AgentDoc("Comma-separated tags") String tags,
            @AgentDoc("The Markdown note body content") String content) {
        return saveNote(getGlobalMemoryDir(), GLOBAL_DIR_UNAVAILABLE, SCOPE_GLOBAL, slug, title, tags, content);
    }

    @AgentDoc(value = "Saves or overwrites a project topic note with structured frontmatter in <projectDataDir>/memory/notes/<slug>.md.", examples = "memoryService.saveProjectNote('swing-html-tables', 'Swing HTML 3.2 Table Layout Rules', 'ui, swing, html', 'Use HTML tables for layout.')")
    public EditorResult saveProjectNote(@AgentDoc("The note slug (lowercase alphanumeric and hyphens)") String slug,
            @AgentDoc("The note title") String title, @AgentDoc("Comma-separated tags") String tags,
            @AgentDoc("The Markdown note body content") String content) {
        return saveNote(getProjectMemoryDir(), NO_ACTIVE_PROJECT, SCOPE_PROJECT, slug, title, tags, content);
    }

    @AgentDoc(value = "Reads a global topic note from ~/.roxycode/memory/notes/<slug>.md.", examples = "memoryService.readGlobalNote('coding-style')")
    public String readGlobalNote(@AgentDoc("The note slug") String slug) {
        return readNote(getGlobalMemoryDir(), slug);
    }

    @AgentDoc(value = "Reads a project topic note from <projectDataDir>/memory/notes/<slug>.md.", examples = "memoryService.readProjectNote('swing-html-tables')")
    public String readProjectNote(@AgentDoc("The note slug") String slug) {
        return readNote(getProjectMemoryDir(), slug);
    }

    @AgentDoc(value = "Deletes an obsolete global topic note from ~/.roxycode/memory/notes/<slug>.md.", examples = "memoryService.deleteGlobalNote('old-note')")
    public EditorResult deleteGlobalNote(@AgentDoc("The note slug to delete") String slug) {
        return deleteNote(getGlobalMemoryDir(), GLOBAL_DIR_UNAVAILABLE, slug);
    }

    @AgentDoc(value = "Deletes an obsolete project topic note from <projectDataDir>/memory/notes/<slug>.md.", examples = "memoryService.deleteProjectNote('old-note')")
    public EditorResult deleteProjectNote(@AgentDoc("The note slug to delete") String slug) {
        return deleteNote(getProjectMemoryDir(), NO_ACTIVE_PROJECT, slug);
    }

    @AgentDoc(value = "Returns a list of formatted summary lines for each global note in ~/.roxycode/memory/notes/*.md.", examples = "memoryService.listGlobalNotes()")
    public List<String> listGlobalNotes() {
        return listNotes(getGlobalMemoryDir());
    }

    @AgentDoc(value = "Returns a list of formatted summary lines for each project note in <projectDataDir>/memory/notes/*.md.", examples = "memoryService.listProjectNotes()")
    public List<String> listProjectNotes() {
        return listNotes(getProjectMemoryDir());
    }

    @AgentDoc(value = "Lists archived completed implementation plans from <projectDataDir>/archive/plans/*.md (most recent first).", examples = "memoryService.listArchivedPlans()")
    public List<String> listArchivedPlans() {
        return listArchivedPlanSummaries().stream().map(entry -> entry.filename() + ": " + entry.title()).toList();
    }

    @AgentDoc(value = "Reads an archived implementation plan from <projectDataDir>/archive/plans/<filename>.", examples = "memoryService.readArchivedPlan('20250514-153000-feature-plan.md')")
    public String readArchivedPlan(@AgentDoc("The archived plan filename (with or without .md)") String filename) {
        return readArchivedFile(getArchivedPlansDir(), filename, "plan");
    }

    @AgentDoc(value = "Lists archived Q&A topic conversations from <projectDataDir>/archive/topics/*.md (most recent first).", examples = "memoryService.listArchivedTopics()")
    public List<String> listArchivedTopics() {
        return listArchivedTopicSummaries().stream().map(entry -> entry.filename() + ": " + entry.title()).toList();
    }

    @AgentDoc(value = "Reads an archived Q&A topic conversation from <projectDataDir>/archive/topics/<filename>.", examples = "memoryService.readArchivedTopic('20250514-153000-memory-architecture.md')")
    public String readArchivedTopic(@AgentDoc("The archived topic filename (with or without .md)") String filename) {
        return readArchivedFile(getArchivedTopicsDir(), filename, "topic");
    }

    private static String readMemory(Path dir) {
        return dir != null ? readFileIfExists(dir.resolve(MEMORY_INDEX_FILE)) : "";
    }

    private static EditorResult updateMemory(Path dir, String missingMsg, String successMsg, String content) {
        return dir == null
                ? new EditorResult(false, "", missingMsg)
                : writeMarkdownFile(dir.resolve(MEMORY_INDEX_FILE), content != null ? content : "", successMsg);
    }

    private static String readNote(Path dir, String slug) {
        String sanitized = sanitizeSlug(slug);
        return dir != null ? readFileIfExists(dir.resolve(NOTES_SUBDIR).resolve(sanitized + MD_EXTENSION)) : "";
    }

    private String readArchivedFile(Path archiveDir, String filename, String label) {
        String cleanName = sanitizeArchiveFilename(filename);
        if (archiveDir == null) {
            return "";
        }
        Path target = archiveDir.resolve(cleanName).toAbsolutePath().normalize();
        if (!target.startsWith(archiveDir)) {
            throw new IllegalArgumentException("Invalid archived " + label + " filename: " + filename);
        }
        return readFileIfExists(target);
    }

    public String archiveTopicConversation(String title, String user, String isoTimestamp,
            List<ChatTranscriptEntry> entries) {
        if (entries == null || entries.isEmpty() || projectService == null || !projectService.hasActiveProject()) {
            return null;
        }
        Path archiveDir = getArchivedTopicsDir();
        if (archiveDir == null) {
            return null;
        }
        try {
            Files.createDirectories(archiveDir);
            String resolvedTitle = firstNonBlank(title, AiService.DEFAULT_TOPIC_TITLE);
            String slug = PathUtils.sanitizeSlug(resolvedTitle, PlanService.MAX_PLAN_SLUG_LENGTH, "topic");
            String baseId = PathUtils.formatArchiveTimestamp(isoTimestamp) + "-" + slug;
            PathUtils.ArchiveFileTarget archiveTarget = PathUtils.resolveUniqueArchiveFile(archiveDir, baseId);
            Path projectRoot = projectService.getCurrentProjectRoot();
            String projectPath = projectRoot != null ? projectRoot.toAbsolutePath().normalize().toString() : "";
            String resolvedUser = firstNonBlank(user, "User");
            String resolvedTimestamp = firstNonBlank(isoTimestamp, Instant.now().toString());

            StringBuilder md = new StringBuilder();
            md.append("""
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
                    """.formatted(FrontmatterUtils.escapeFrontmatterValue(archiveTarget.id()),
                    FrontmatterUtils.escapeFrontmatterValue(projectService.getProjectName()),
                    FrontmatterUtils.escapeFrontmatterValue(projectPath),
                    FrontmatterUtils.escapeFrontmatterValue(resolvedTitle),
                    FrontmatterUtils.escapeFrontmatterValue(resolvedUser),
                    FrontmatterUtils.escapeFrontmatterValue(resolvedTimestamp), entries.size(), resolvedTitle));

            for (ChatTranscriptEntry entry : entries) {
                md.append("\n### ").append("user".equalsIgnoreCase(entry.role()) ? "User" : "Roxy");
                if (entry.timestamp() != null && !entry.timestamp().isBlank()) {
                    md.append(" (").append(entry.timestamp().trim()).append(")");
                }
                md.append("\n").append(entry.content() != null ? entry.content().stripTrailing() : "").append("\n");
            }

            Files.writeString(archiveTarget.file(), md.toString(), StandardCharsets.UTF_8);
            return archiveTarget.file().getFileName().toString();
        } catch (Exception e) {
            log.warn("Failed to archive Q&A topic conversation: {}", e.getMessage());
            return null;
        }
    }

    public static String buildTranscriptExcerpt(List<ChatTranscriptEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int count = Math.min(entries.size(), MAX_EXCERPT_ENTRIES);
        for (int i = 0; i < count; i++) {
            ChatTranscriptEntry entry = entries.get(i);
            String speaker = "user".equalsIgnoreCase(entry.role()) ? "User" : "Roxy";
            String content = entry.content() != null ? entry.content().trim() : "";
            if (content.length() > MAX_ENTRY_CHARS) {
                content = content.substring(0, MAX_ENTRY_CHARS) + "...";
            }
            if (i > 0) {
                sb.append(LF);
            }
            sb.append(speaker).append(": ").append(content);
        }
        return sb.toString();
    }

    public record ArchiveEntrySummary(String filename, String type, String title, String createdBy, String timestamp,
            int messageCount, String matchSnippet) {
        public ArchiveEntrySummary withMatchSnippet(String snippet) {
            return new ArchiveEntrySummary(filename, type, title, createdBy, timestamp, messageCount, snippet);
        }
    }

    public List<ArchiveEntrySummary> listArchivedTopicSummaries() {
        return listArchivedEntrySummaries(getArchivedTopicsDir(), "topic");
    }

    public List<ArchiveEntrySummary> listArchivedPlanSummaries() {
        return listArchivedEntrySummaries(getArchivedPlansDir(), "plan");
    }

    public List<ArchiveEntrySummary> searchArchivedTopics(String query) {
        return searchArchiveEntries(listArchivedTopicSummaries(), getArchivedTopicsDir(), query);
    }

    public List<ArchiveEntrySummary> searchArchivedPlans(String query) {
        return searchArchiveEntries(listArchivedPlanSummaries(), getArchivedPlansDir(), query);
    }

    public static String stripFrontmatter(String rawMarkdown) {
        return FrontmatterUtils.stripFrontmatter(rawMarkdown);
    }

    static int findFrontmatterEndLine(Path file) {
        return FrontmatterUtils.findFrontmatterEndLine(file);
    }

    static Map<String, String> parseFrontmatter(Path file) {
        return FrontmatterUtils.parseFrontmatter(file);
    }

    private List<ArchiveEntrySummary> listArchivedEntrySummaries(Path archiveDir, String defaultType) {
        if (archiveDir == null || !Files.isDirectory(archiveDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(archiveDir)) {
            return stream.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(MD_EXTENSION))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER)
                            .reversed())
                    .map(file -> buildArchiveEntrySummary(file, defaultType)).toList();
        } catch (IOException e) {
            log.warn("Failed to list archived {} summaries in {}: {}", defaultType, archiveDir, e.getMessage());
            return List.of();
        }
    }

    private static ArchiveEntrySummary buildArchiveEntrySummary(Path file, String defaultType) {
        String filename = file.getFileName().toString();
        Map<String, String> fm = parseFrontmatter(file);
        String type = firstNonBlank(fm.get("type"), defaultType);
        String title = firstNonBlank(fm.get("title"), StringUtils.removeEnd(filename, MD_EXTENSION));
        String createdBy = firstNonBlank(fm.get("createdBy"), fm.get("completedBy"), fm.get("approvedBy"), "User");
        String timestamp = firstNonBlank(fm.get("archivedOn"), fm.get("completedOn"), fm.get("approvalTimestamp"), "");
        int messageCount = 0;
        String msgCountStr = fm.get("messageCount");
        if (msgCountStr != null && !msgCountStr.isBlank()) {
            try {
                messageCount = Integer.parseInt(msgCountStr.trim());
            } catch (NumberFormatException ignored) {
                messageCount = 0;
            }
        }
        return new ArchiveEntrySummary(filename, type, title, createdBy, timestamp, messageCount, "");
    }

    private static String firstNonBlank(String... values) {
        if (values != null) {
            for (String v : values) {
                if (v != null && !v.isBlank()) {
                    return v.trim();
                }
            }
        }
        return "";
    }

    private List<ArchiveEntrySummary> searchArchiveEntries(List<ArchiveEntrySummary> allEntries, Path archiveDir,
            String query) {
        if (query == null || query.isBlank()) {
            return allEntries;
        }
        if (allEntries.isEmpty()) {
            return List.of();
        }
        String trimmedQuery = query.trim();
        String lowerQuery = trimmedQuery.toLowerCase(Locale.ROOT);
        Map<String, String> contentSnippets = findArchiveContentMatches(archiveDir, trimmedQuery);

        List<ArchiveEntrySummary> matched = new ArrayList<>();
        for (ArchiveEntrySummary entry : allEntries) {
            boolean metaMatch = containsIgnoreCase(entry.title(), lowerQuery)
                    || containsIgnoreCase(entry.filename(), lowerQuery)
                    || containsIgnoreCase(entry.createdBy(), lowerQuery);
            String snippet = contentSnippets.get(entry.filename());
            if (metaMatch || snippet != null) {
                matched.add(snippet != null && !snippet.isBlank() ? entry.withMatchSnippet(snippet) : entry);
            }
        }
        return List.copyOf(matched);
    }

    private static boolean containsIgnoreCase(String value, String lowerQuery) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    private static List<String> buildBaseRipGrepCommand() {
        return new ArrayList<>(List.of("rg", "--no-heading", "--with-filename", "--line-number", "--color", "never"));
    }

    private Map<String, String> findArchiveContentMatches(Path archiveDir, String query) {
        if (archiveDir == null || !Files.isDirectory(archiveDir) || query.isBlank()) {
            return Map.of();
        }
        List<String> command = buildBaseRipGrepCommand();
        command.addAll(List.of("--ignore-case", "--fixed-strings", "-e", query, "."));
        try {
            ProcessResult result = ProcessRunner.run(command, archiveDir, true);
            if (result.timedOut() || result.exitCode() != 0) {
                return Map.of();
            }
            Map<String, String> snippetsByFilename = new LinkedHashMap<>();
            Map<String, Integer> frontmatterEndLineCache = new LinkedHashMap<>();
            for (String line : result.output().lines().toList()) {
                parseRipGrepArchiveLine(line, archiveDir, frontmatterEndLineCache, snippetsByFilename);
            }
            return snippetsByFilename;
        } catch (IOException e) {
            log.warn("Failed to run RipGrep on archive directory {}: {}", archiveDir, e.getMessage());
            return Map.of();
        }
    }

    static void parseRipGrepArchiveLine(String rawLine, Path archiveDir, Map<String, Integer> frontmatterEndLineCache,
            Map<String, String> snippetsByFilename) {
        if (rawLine == null || rawLine.isBlank()) {
            return;
        }
        String rel = StringUtils.removeStart(rawLine.replace((char) 92, '/'), "./");
        int colonSearchStart = (rel.length() >= 3 && Character.isLetter(rel.charAt(0)) && rel.charAt(1) == ':'
                && rel.charAt(2) == '/') ? 3 : 0;
        int firstColon = rel.indexOf(':', colonSearchStart);
        int secondColon = firstColon > colonSearchStart ? rel.indexOf(':', firstColon + 1) : -1;
        if (secondColon <= firstColon) {
            return;
        }
        String filePart = rel.substring(0, firstColon);
        int lastSlash = filePart.lastIndexOf('/');
        String filename = lastSlash >= 0 ? filePart.substring(lastSlash + 1) : filePart;
        if (snippetsByFilename.containsKey(filename)) {
            return;
        }
        int lineNumber = -1;
        try {
            lineNumber = Integer.parseInt(rel.substring(firstColon + 1, secondColon).trim());
        } catch (NumberFormatException ignored) {
            // Non-numeric line token
        }
        if (lineNumber > 0 && archiveDir != null) {
            int fmEndLine = frontmatterEndLineCache.computeIfAbsent(filename,
                    fn -> findFrontmatterEndLine(archiveDir.resolve(fn)));
            if (lineNumber <= fmEndLine) {
                return;
            }
        }
        String text = rel.substring(secondColon + 1).trim();
        if (!text.isEmpty() && !FrontmatterUtils.FRONTMATTER_DELIMITER.equals(text)) {
            snippetsByFilename.put(filename, text);
        }
    }

    @AgentDoc(value = "Searches across Global Memory (~/.roxycode/memory/), Project Memory (<projectDataDir>/memory/), Archived Plans (<projectDataDir>/archive/plans/), and Archived Topics (<projectDataDir>/archive/topics/) using RipGrep.", examples = "memoryService.search('swing')")
    public EditorResult search(@AgentDoc("The regex pattern to search for") String pattern) {
        return executeSearch(pattern, false);
    }

    @AgentDoc(value = "Searches case-insensitively across Global Memory, Project Memory, Archived Plans, and Archived Topics using RipGrep.", examples = "memoryService.searchIgnoreCase('table layout')")
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

        List<Path> existingDirs = Stream.of(globalDir, projectDir, archiveDir, archiveTopicsDir)
                .filter(d -> d != null && Files.isDirectory(d)).toList();
        if (existingDirs.isEmpty()) {
            return new EditorResult(true, "", null);
        }

        List<String> command = buildBaseRipGrepCommand();
        command.addAll(List.of("-C", String.valueOf(SEARCH_CONTEXT_LINES)));
        if (ignoreCase) {
            command.add("-i");
        }
        command.addAll(List.of("-e", pattern));
        existingDirs.forEach(dir -> command.add(dir.toString()));

        try {
            ProcessResult result = ProcessRunner.run(command, null, true);
            if (result.timedOut()) {
                log.warn("Memory RipGrep search timed out for pattern: {}", pattern);
                return new EditorResult(false, "", "RipGrep search timed out");
            }
            int exitCode = result.exitCode();
            if (exitCode == GrepService.EXIT_CODE_NO_MATCHES) {
                return new EditorResult(true, "", null);
            } else if (exitCode != 0) {
                String error = result.output().trim();
                log.warn("Memory RipGrep failed with exit code {}: {}", exitCode, error);
                return new EditorResult(false, "", "RipGrep error (" + exitCode + "): " + error);
            }
            String normalized = normalizeSearchOutput(result.output().trim(), globalDir, projectDir, archiveDir,
                    archiveTopicsDir);
            return new EditorResult(true, GrepService.capOutputLines(normalized, GrepService.MAX_OUTPUT_LINES), null);
        } catch (IOException e) {
            log.warn("Failed to execute RipGrep for memory search: {}", e.getMessage());
            return new EditorResult(false, "", "Error executing RipGrep: " + e.getMessage());
        }
    }

    static String normalizeSearchOutput(String rawOutput, Path globalDir, Path projectDir, Path archiveDir) {
        return normalizeSearchOutput(rawOutput, globalDir, projectDir, archiveDir, null);
    }

    static String normalizeSearchOutput(String rawOutput, Path globalDir, Path projectDir, Path archiveDir,
            Path archiveTopicsDir) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return "";
        }
        List<PrefixLabel> prefixLabels = List.of(new PrefixLabel(toSlashPrefix(globalDir), "[GLOBAL] "),
                new PrefixLabel(toSlashPrefix(projectDir), "[PROJECT] "),
                new PrefixLabel(toSlashPrefix(archiveDir), "[ARCHIVE] plans/"),
                new PrefixLabel(toSlashPrefix(archiveTopicsDir), "[ARCHIVE] topics/"));

        List<String> lines = rawOutput.lines().toList();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            String line = lines.get(i);
            String slashLine = line.replace((char) 92, '/');
            boolean replaced = false;
            for (PrefixLabel pl : prefixLabels) {
                if (startsWithSlashPrefix(slashLine, pl.prefix())) {
                    sb.append(pl.label()).append(slashLine.substring(pl.prefix().length()));
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    static boolean startsWithSlashPrefix(String slashLine, String prefix) {
        if (slashLine == null || prefix == null || prefix.isEmpty() || slashLine.length() < prefix.length()) {
            return false;
        }
        if (slashLine.startsWith(prefix)) {
            return true;
        }
        return prefix.length() >= 3 && Character.isLetter(prefix.charAt(0)) && prefix.charAt(1) == ':'
                && prefix.charAt(2) == '/'
                && Character.toLowerCase(slashLine.charAt(0)) == Character.toLowerCase(prefix.charAt(0))
                && slashLine.regionMatches(1, prefix, 1, prefix.length() - 1);
    }

    private static String toSlashPrefix(Path dir) {
        if (dir == null) {
            return "";
        }
        String normalized = dir.toAbsolutePath().normalize().toString().replace((char) 92, '/');
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    public static String sanitizeSlug(String rawSlug) {
        if (rawSlug == null || rawSlug.isBlank()) {
            throw new IllegalArgumentException("Note slug must not be blank");
        }
        if (rawSlug.contains("..") || rawSlug.indexOf('/') >= 0 || rawSlug.indexOf(92) >= 0) {
            throw new IllegalArgumentException("Invalid note slug (path traversal not allowed): " + rawSlug);
        }
        String trimmed = rawSlug.trim();
        if (trimmed.toLowerCase(Locale.ROOT).endsWith(MD_EXTENSION)) {
            trimmed = trimmed.substring(0, trimmed.length() - MD_EXTENSION.length()).trim();
        }
        String sanitized = PathUtils.sanitizeSlug(trimmed, MAX_SLUG_LENGTH, "");
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("Note slug must contain alphanumeric characters: " + rawSlug);
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
        if (trimmed.contains("..") || trimmed.indexOf('/') >= 0 || trimmed.indexOf(92) >= 0) {
            throw new IllegalArgumentException("Invalid archived filename (path traversal not allowed): " + filename);
        }
        return trimmed.toLowerCase(Locale.ROOT).endsWith(MD_EXTENSION) ? trimmed : trimmed + MD_EXTENSION;
    }

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
        return truncated
                ? result + "\n... (MEMORY.md truncated at 200 lines / 8 KB limit; move detailed content into topic notes)"
                : result;
    }

    private EditorResult saveNote(Path memoryRoot, String missingMsg, String scope, String rawSlug, String title,
            String tags, String content) {
        String sanitizedSlug = sanitizeSlug(rawSlug);
        if (memoryRoot == null) {
            return new EditorResult(false, "", missingMsg);
        }
        String resolvedTitle = firstNonBlank(title, sanitizedSlug);
        String resolvedTags = tags != null ? tags.trim() : "";
        String body = content != null ? content.stripLeading() : "";

        StringBuilder md = new StringBuilder();
        md.append("""
                ---
                id: "%s"
                scope: "%s"
                title: "%s"
                tags: "%s"
                updatedAt: "%s"
                ---

                """.formatted(FrontmatterUtils.escapeFrontmatterValue(sanitizedSlug),
                FrontmatterUtils.escapeFrontmatterValue(scope), FrontmatterUtils.escapeFrontmatterValue(resolvedTitle),
                FrontmatterUtils.escapeFrontmatterValue(resolvedTags),
                FrontmatterUtils.escapeFrontmatterValue(Instant.now().toString())));

        if (!body.startsWith("# ") && !body.startsWith("#\t")) {
            md.append("# ").append(resolvedTitle).append("\n\n");
        }
        md.append(body);
        if (!body.endsWith("\n")) {
            md.append("\n");
        }

        Path notePath = memoryRoot.resolve(NOTES_SUBDIR).resolve(sanitizedSlug + MD_EXTENSION);
        return writeMarkdownFile(notePath, md.toString(),
                "Saved " + scope + " note: notes/" + sanitizedSlug + MD_EXTENSION);
    }

    private EditorResult deleteNote(Path dir, String missingMsg, String slug) {
        String sanitizedSlug = sanitizeSlug(slug);
        if (dir == null) {
            return new EditorResult(false, "", missingMsg);
        }
        Path notePath = dir.resolve(NOTES_SUBDIR).resolve(sanitizedSlug + MD_EXTENSION);
        try {
            if (!Files.exists(notePath)) {
                return new EditorResult(false, "", "Note does not exist: notes/" + sanitizedSlug + MD_EXTENSION);
            }
            Files.delete(notePath);
            return new EditorResult(true, "Deleted note: notes/" + sanitizedSlug + MD_EXTENSION, null);
        } catch (IOException e) {
            log.warn("Failed to delete note {}: {}", notePath, e.getMessage());
            return new EditorResult(false, "", "Failed to delete note: " + e.getMessage());
        }
    }

    private List<String> listNotes(Path memoryRoot) {
        Path notesDir = memoryRoot != null ? memoryRoot.resolve(NOTES_SUBDIR) : null;
        if (notesDir == null || !Files.isDirectory(notesDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(notesDir)) {
            return stream.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(MD_EXTENSION))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .map(file -> {
                        String filename = file.getFileName().toString();
                        Map<String, String> fm = parseFrontmatter(file);
                        String title = fm.getOrDefault("title", StringUtils.removeEnd(filename, MD_EXTENSION));
                        String tags = fm.getOrDefault("tags", "");
                        String updatedAt = fm.getOrDefault("updatedAt", "");
                        return filename + " - " + '"' + title + '"' + " [" + tags + "]"
                                + (updatedAt.isEmpty() ? "" : " (" + updatedAt + ")");
                    }).toList();
        } catch (IOException e) {
            log.warn("Failed to list notes in {}: {}", notesDir, e.getMessage());
            return List.of();
        }
    }

    private static EditorResult writeMarkdownFile(Path path, String content, String successMessage) {
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
        if (path == null || !Files.isRegularFile(path)) {
            return "";
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read memory file {}: {}", path, e.getMessage());
            return "";
        }
    }

    Path getGlobalMemoryDir() {
        Path roxyHome = settingsService != null ? settingsService.getRoxyHomeDir() : null;
        return roxyHome != null ? roxyHome.resolve(MEMORY_DIR).toAbsolutePath().normalize() : null;
    }

    Path getProjectMemoryDir() {
        return resolveProjectSubdir(MEMORY_DIR);
    }

    Path getArchivedPlansDir() {
        return resolveProjectSubdir(ARCHIVE_PLANS_SUBDIR);
    }

    Path getArchivedTopicsDir() {
        return resolveProjectSubdir(ARCHIVE_TOPICS_SUBDIR);
    }

    private Path resolveProjectSubdir(String subdir) {
        if (projectService == null || !projectService.hasActiveProject()) {
            return null;
        }
        Path dir = projectService.resolveProjectDataPath(subdir);
        return dir != null ? dir.toAbsolutePath().normalize() : null;
    }
}
