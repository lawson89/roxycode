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
import java.util.Map;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.service.AiService;
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
    private static final String NL = String.valueOf(LF);

    private static final String SCOPE_GLOBAL = "global";
    private static final String SCOPE_PROJECT = "project";
    private static final String NO_ACTIVE_PROJECT = "No active project";
    private static final String GLOBAL_DIR_UNAVAILABLE = "Global memory directory is unavailable";
    private static final String MD_EXTENSION = ".md";

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

    @AgentDoc(value = "Writes or overwrites the pinned global memory cheat-sheet (~/.roxycode/memory/MEMORY.md).", examples = "memoryService.updateGlobalMemory('# Global Preferences')")
    public EditorResult updateGlobalMemory(@AgentDoc("The Markdown content for global MEMORY.md") String content) {
        return updateMemory(getGlobalMemoryDir(), GLOBAL_DIR_UNAVAILABLE, "Global MEMORY.md updated successfully",
                content);
    }

    @AgentDoc(value = "Reads the pinned project memory cheat-sheet (<projectDataDir>/memory/MEMORY.md). Returns empty string if not present or no active project.", examples = "memoryService.readProjectMemory()")
    public String readProjectMemory() {
        return readMemory(resolveProjectSubdir(MEMORY_DIR));
    }

    @AgentDoc(value = "Writes or overwrites the pinned project memory cheat-sheet (<projectDataDir>/memory/MEMORY.md).", examples = "memoryService.updateProjectMemory('# Project Architecture')")
    public EditorResult updateProjectMemory(@AgentDoc("The Markdown content for project MEMORY.md") String content) {
        return updateMemory(resolveProjectSubdir(MEMORY_DIR), NO_ACTIVE_PROJECT,
                "Project MEMORY.md updated successfully", content);
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
        return saveNote(resolveProjectSubdir(MEMORY_DIR), NO_ACTIVE_PROJECT, SCOPE_PROJECT, slug, title, tags, content);
    }

    @AgentDoc(value = "Reads a global topic note from ~/.roxycode/memory/notes/<slug>.md.", examples = "memoryService.readGlobalNote('coding-style')")
    public String readGlobalNote(@AgentDoc("The note slug") String slug) {
        return readNote(getGlobalMemoryDir(), slug);
    }

    @AgentDoc(value = "Reads a project topic note from <projectDataDir>/memory/notes/<slug>.md.", examples = "memoryService.readProjectNote('swing-html-tables')")
    public String readProjectNote(@AgentDoc("The note slug") String slug) {
        return readNote(resolveProjectSubdir(MEMORY_DIR), slug);
    }

    @AgentDoc(value = "Deletes an obsolete global topic note from ~/.roxycode/memory/notes/<slug>.md.", examples = "memoryService.deleteGlobalNote('old-note')")
    public EditorResult deleteGlobalNote(@AgentDoc("The note slug to delete") String slug) {
        return deleteNote(getGlobalMemoryDir(), GLOBAL_DIR_UNAVAILABLE, slug);
    }

    @AgentDoc(value = "Deletes an obsolete project topic note from <projectDataDir>/memory/notes/<slug>.md.", examples = "memoryService.deleteProjectNote('old-note')")
    public EditorResult deleteProjectNote(@AgentDoc("The note slug to delete") String slug) {
        return deleteNote(resolveProjectSubdir(MEMORY_DIR), NO_ACTIVE_PROJECT, slug);
    }

    @AgentDoc(value = "Returns a list of formatted summary lines for each global note in ~/.roxycode/memory/notes/*.md.", examples = "memoryService.listGlobalNotes()")
    public List<String> listGlobalNotes() {
        return listNotes(getGlobalMemoryDir());
    }

    @AgentDoc(value = "Returns a list of formatted summary lines for each project note in <projectDataDir>/memory/notes/*.md.", examples = "memoryService.listProjectNotes()")
    public List<String> listProjectNotes() {
        return listNotes(resolveProjectSubdir(MEMORY_DIR));
    }

    @AgentDoc(value = "Lists archived completed implementation plans from <projectDataDir>/archive/plans/*.md (most recent first).", examples = "memoryService.listArchivedPlans()")
    public List<String> listArchivedPlans() {
        return listArchivedPlanSummaries().stream().map(entry -> entry.filename() + ": " + entry.title()).toList();
    }

    @AgentDoc(value = "Reads an archived implementation plan from <projectDataDir>/archive/plans/<filename>.", examples = "memoryService.readArchivedPlan('20250514-153000-feature-plan.md')")
    public String readArchivedPlan(@AgentDoc("The archived plan filename (with or without .md)") String filename) {
        return readArchivedFile(resolveProjectSubdir(ARCHIVE_PLANS_SUBDIR), filename, "plan");
    }

    @AgentDoc(value = "Lists archived Q&A topic conversations from <projectDataDir>/archive/topics/*.md (most recent first).", examples = "memoryService.listArchivedTopics()")
    public List<String> listArchivedTopics() {
        return listArchivedTopicSummaries().stream().map(entry -> entry.filename() + ": " + entry.title()).toList();
    }

    @AgentDoc(value = "Reads an archived Q&A topic conversation from <projectDataDir>/archive/topics/<filename>.", examples = "memoryService.readArchivedTopic('20250514-153000-memory-architecture.md')")
    public String readArchivedTopic(@AgentDoc("The archived topic filename (with or without .md)") String filename) {
        return readArchivedFile(resolveProjectSubdir(ARCHIVE_TOPICS_SUBDIR), filename, "topic");
    }

    private static String readMemory(Path dir) {
        if (dir == null) {
            return "";
        }
        return readFileIfExists(dir.resolve(MEMORY_INDEX_FILE));
    }

    private static EditorResult updateMemory(Path dir, String missingMsg, String successMsg, String content) {
        if (dir == null) {
            return new EditorResult(false, "", missingMsg);
        }
        return writeMarkdownFile(dir.resolve(MEMORY_INDEX_FILE), StringUtils.defaultString(content), successMsg);
    }

    private static String readNote(Path dir, String slug) {
        String sanitized = sanitizeSlug(slug);
        if (dir == null) {
            return "";
        }
        return readFileIfExists(dir.resolve(NOTES_SUBDIR).resolve(sanitized + MD_EXTENSION));
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
        Path archiveDir = resolveProjectSubdir(ARCHIVE_TOPICS_SUBDIR);
        if (archiveDir == null) {
            return null;
        }
        try {
            Files.createDirectories(archiveDir);
            String resolvedTitle = StringUtils.defaultIfBlank(title, AiService.DEFAULT_TOPIC_TITLE).trim();
            String slug = PathUtils.sanitizeSlug(resolvedTitle, PlanService.MAX_PLAN_SLUG_LENGTH, "topic");
            String baseId = PathUtils.formatArchiveTimestamp(isoTimestamp) + "-" + slug;
            PathUtils.ArchiveFileTarget archiveTarget = PathUtils.resolveUniqueArchiveFile(archiveDir, baseId);
            Path projectRoot = projectService.getCurrentProjectRoot();
            String projectPath = "";
            if (projectRoot != null) {
                projectPath = projectRoot.toAbsolutePath().normalize().toString();
            }
            String resolvedUser = StringUtils.defaultIfBlank(user, "User").trim();
            String resolvedTimestamp = StringUtils.defaultIfBlank(isoTimestamp, Instant.now().toString()).trim();

            Map<String, Object> frontmatter = new LinkedHashMap<>();
            frontmatter.put("id", archiveTarget.id());
            frontmatter.put("type", "topic");
            frontmatter.put("project", projectService.getProjectName());
            frontmatter.put("projectPath", projectPath);
            frontmatter.put("title", resolvedTitle);
            frontmatter.put("createdBy", resolvedUser);
            frontmatter.put("archivedOn", resolvedTimestamp);
            frontmatter.put("messageCount", entries.size());

            StringBuilder md = new StringBuilder(FrontmatterUtils.formatFrontmatter(frontmatter));
            md.append("# Topic: ").append(resolvedTitle).append(NL).append(NL).append("## Conversation Transcript")
                    .append(NL);

            for (ChatTranscriptEntry entry : entries) {
                String speaker;
                if ("user".equalsIgnoreCase(entry.role())) {
                    speaker = "User";
                } else {
                    speaker = "Roxy";
                }
                md.append(NL).append("### ").append(speaker);
                if (StringUtils.isNotBlank(entry.timestamp())) {
                    md.append(" (").append(entry.timestamp().trim()).append(")");
                }
                md.append(NL).append(StringUtils.defaultString(entry.content()).stripTrailing()).append(NL);
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
            String speaker;
            if ("user".equalsIgnoreCase(entry.role())) {
                speaker = "User";
            } else {
                speaker = "Roxy";
            }
            String content = StringUtils.defaultString(entry.content()).trim();
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
        return listArchivedEntrySummaries(resolveProjectSubdir(ARCHIVE_TOPICS_SUBDIR), "topic");
    }

    public List<ArchiveEntrySummary> listArchivedPlanSummaries() {
        return listArchivedEntrySummaries(resolveProjectSubdir(ARCHIVE_PLANS_SUBDIR), "plan");
    }

    public List<ArchiveEntrySummary> searchArchivedTopics(String query) {
        return searchArchiveEntries(listArchivedTopicSummaries(), resolveProjectSubdir(ARCHIVE_TOPICS_SUBDIR), query);
    }

    public List<ArchiveEntrySummary> searchArchivedPlans(String query) {
        return searchArchiveEntries(listArchivedPlanSummaries(), resolveProjectSubdir(ARCHIVE_PLANS_SUBDIR), query);
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
        Map<String, String> fm = FrontmatterUtils.parseFrontmatter(file);
        String type = StringUtils.defaultIfBlank(fm.get("type"), defaultType).trim();
        String title = StringUtils.defaultIfBlank(fm.get("title"), StringUtils.removeEnd(filename, MD_EXTENSION))
                .trim();
        String rawCreator = StringUtils.firstNonBlank(fm.get("createdBy"), fm.get("completedBy"), fm.get("approvedBy"),
                "User");
        String createdBy = StringUtils.defaultString(rawCreator).trim();
        String rawTimestamp = StringUtils.firstNonBlank(fm.get("archivedOn"), fm.get("completedOn"),
                fm.get("approvalTimestamp"), "");
        String timestamp = StringUtils.defaultString(rawTimestamp).trim();
        int messageCount = 0;
        String msgCountStr = fm.get("messageCount");
        if (StringUtils.isNotBlank(msgCountStr)) {
            try {
                messageCount = Integer.parseInt(msgCountStr.trim());
            } catch (NumberFormatException ignored) {
                messageCount = 0;
            }
        }
        return new ArchiveEntrySummary(filename, type, title, createdBy, timestamp, messageCount, "");
    }

    private List<ArchiveEntrySummary> searchArchiveEntries(List<ArchiveEntrySummary> allEntries, Path archiveDir,
            String query) {
        if (StringUtils.isBlank(query)) {
            return allEntries;
        }
        if (allEntries.isEmpty() || archiveDir == null) {
            return List.of();
        }
        String trimmedQuery = query.trim();
        Map<String, String> snippetsByFile = new LinkedHashMap<>();
        if (Files.isDirectory(archiveDir)) {
            List<String> command = List.of("rg", "--color=never", "-i", "--no-heading", "--with-filename", "-m", "1",
                    "-F", "--", trimmedQuery, ".");
            List<String> rgLines = GrepService.runRipGrepLines(command, archiveDir, false);
            for (String line : rgLines) {
                int colonIdx = line.indexOf(':');
                if (colonIdx <= 0) {
                    continue;
                }
                String filePart = line.substring(0, colonIdx).trim();
                if (filePart.startsWith("./") || filePart.startsWith("." + (char) 92)) {
                    filePart = filePart.substring(2);
                }
                String snippetPart = line.substring(colonIdx + 1).trim();
                if (!filePart.isEmpty() && !snippetPart.isEmpty()) {
                    snippetsByFile.putIfAbsent(filePart, snippetPart);
                }
            }
        }
        List<ArchiveEntrySummary> matched = new ArrayList<>();
        for (ArchiveEntrySummary entry : allEntries) {
            boolean metaMatch = StringUtils.containsIgnoreCase(entry.title(), trimmedQuery)
                    || StringUtils.containsIgnoreCase(entry.filename(), trimmedQuery)
                    || StringUtils.containsIgnoreCase(entry.createdBy(), trimmedQuery);
            String snippet = snippetsByFile.getOrDefault(entry.filename(), "").trim();
            if (metaMatch || !snippet.isEmpty()) {
                matched.add(entry.withMatchSnippet(snippet));
            }
        }
        return List.copyOf(matched);
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
        if (StringUtils.isEmpty(pattern)) {
            return new EditorResult(false, "", "Search pattern must not be empty");
        }
        List<Path> existingDirs = Stream
                .of(getGlobalMemoryDir(), resolveProjectSubdir(MEMORY_DIR), resolveProjectSubdir(ARCHIVE_PLANS_SUBDIR),
                        resolveProjectSubdir(ARCHIVE_TOPICS_SUBDIR))
                .filter(d -> d != null && Files.isDirectory(d)).toList();
        if (existingDirs.isEmpty()) {
            return new EditorResult(true, "", null);
        }

        List<String> command = new ArrayList<>(List.of("rg", "--no-heading", "--with-filename", "--line-number",
                "--color", "never", "-C", String.valueOf(SEARCH_CONTEXT_LINES)));
        if (ignoreCase) {
            command.add("-i");
        }
        command.addAll(List.of("-e", pattern));
        existingDirs.forEach(dir -> command.add(dir.toString()));

        return GrepService.runRipGrepSearch(command, null, rawOutput -> rawOutput);
    }

    public static String sanitizeSlug(String rawSlug) {
        if (StringUtils.isBlank(rawSlug)) {
            throw new IllegalArgumentException("Note slug must not be blank");
        }
        if (rawSlug.contains("..") || rawSlug.indexOf('/') >= 0 || rawSlug.indexOf(92) >= 0) {
            throw new IllegalArgumentException("Invalid note slug (path traversal not allowed): " + rawSlug);
        }
        String trimmed = StringUtils.removeEndIgnoreCase(rawSlug.trim(), MD_EXTENSION).trim();
        String sanitized = PathUtils.sanitizeSlug(trimmed, MAX_SLUG_LENGTH, "");
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("Note slug must contain alphanumeric characters: " + rawSlug);
        }
        return sanitized;
    }

    static String sanitizeArchiveFilename(String filename) {
        if (StringUtils.isBlank(filename)) {
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
        return StringUtils.appendIfMissingIgnoreCase(trimmed, MD_EXTENSION);
    }

    public static String capPinnedMemoryForPrompt(String content) {
        if (StringUtils.isBlank(content)) {
            return "";
        }
        String result = content;
        boolean truncated = false;
        List<String> lines = result.lines().toList();
        if (lines.size() > MAX_PINNED_MEMORY_LINES) {
            result = String.join(NL, lines.subList(0, MAX_PINNED_MEMORY_LINES));
            truncated = true;
        }
        if (result.length() > MAX_PINNED_MEMORY_CHARS) {
            result = result.substring(0, MAX_PINNED_MEMORY_CHARS);
            truncated = true;
        }
        if (truncated) {
            return result + NL
                    + "... (MEMORY.md truncated at 200 lines / 8 KB limit; move detailed content into topic notes)";
        }
        return result;
    }

    private EditorResult saveNote(Path memoryRoot, String missingMsg, String scope, String rawSlug, String title,
            String tags, String content) {
        String sanitizedSlug = sanitizeSlug(rawSlug);
        if (memoryRoot == null) {
            return new EditorResult(false, "", missingMsg);
        }
        String resolvedTitle = StringUtils.defaultIfBlank(title, sanitizedSlug).trim();
        String resolvedTags = StringUtils.defaultString(tags).trim();
        String body = StringUtils.defaultString(content).stripLeading();

        Map<String, Object> frontmatter = new LinkedHashMap<>();
        frontmatter.put("id", sanitizedSlug);
        frontmatter.put("scope", scope);
        frontmatter.put("title", resolvedTitle);
        frontmatter.put("tags", resolvedTags);
        frontmatter.put("updatedAt", Instant.now().toString());

        StringBuilder md = new StringBuilder(FrontmatterUtils.formatFrontmatter(frontmatter));

        if (!body.startsWith("# ") && !body.startsWith("#" + (char) 9)) {
            md.append("# ").append(resolvedTitle).append(NL).append(NL);
        }
        md.append(body);
        if (!body.endsWith(NL)) {
            md.append(NL);
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
        if (memoryRoot == null) {
            return List.of();
        }
        Path notesDir = memoryRoot.resolve(NOTES_SUBDIR);
        if (!Files.isDirectory(notesDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(notesDir)) {
            return stream.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(MD_EXTENSION))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .map(file -> {
                        String filename = file.getFileName().toString();
                        Map<String, String> fm = FrontmatterUtils.parseFrontmatter(file);
                        String title = fm.getOrDefault("title", StringUtils.removeEnd(filename, MD_EXTENSION));
                        String tags = fm.getOrDefault("tags", "");
                        String updatedAt = fm.getOrDefault("updatedAt", "");
                        String updatedAtSuffix = "";
                        if (!updatedAt.isEmpty()) {
                            updatedAtSuffix = " (" + updatedAt + ")";
                        }
                        return filename + " - " + '"' + title + '"' + " [" + tags + "]" + updatedAtSuffix;
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
        if (settingsService == null) {
            return null;
        }
        Path roxyHome = settingsService.getRoxyHomeDir();
        if (roxyHome == null) {
            return null;
        }
        return roxyHome.resolve(MEMORY_DIR).toAbsolutePath().normalize();
    }

    private Path resolveProjectSubdir(String subdir) {
        if (projectService == null || !projectService.hasActiveProject()) {
            return null;
        }
        Path dir = projectService.resolveProjectDataPath(subdir);
        if (dir == null) {
            return null;
        }
        return dir.toAbsolutePath().normalize();
    }
}
