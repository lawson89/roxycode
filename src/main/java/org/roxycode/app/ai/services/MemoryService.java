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
import org.apache.commons.lang3.math.NumberUtils;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.events.ArchiveUpdatedEvent;
import org.roxycode.app.events.MemoryChangedEvent;
import org.roxycode.app.model.ChatTranscriptEntry;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.util.FrontmatterUtils;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;

@AgentService(value = "memoryService", phases = {"*"})
@AgentDoc("Provides two-tier Global and Project Agent Memory (MEMORY.md cheat-sheets, frontmatter topic notes, archived plans, archived Q&A topics, and unified RipGrep search) across all workflow phases.")
public class MemoryService {
    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    static final String MEMORY_DIR = "memory";
    static final String MEMORY_INDEX_FILE = "MEMORY.md";
    static final String NOTES_SUBDIR = "notes";
    static final String ARCHIVE_PLANS_SUBDIR = PlanService.ARCHIVE_PLANS_SUBDIR;
    public static final String ARCHIVE_TOPICS_SUBDIR = "archive/topics";
    public static final String DEFAULT_TOPIC_TITLE = "Codebase Q&A Topic";
    public static final int MAX_TOPIC_TITLE_LENGTH = 80;
    public static final int MAX_SLUG_LENGTH = 50;
    public static final int MAX_PINNED_MEMORY_LINES = 200;
    public static final int MAX_PINNED_MEMORY_CHARS = 8192;
    private static final int SEARCH_CONTEXT_LINES = 2;
    private static final String STRIP_TITLE_CHARS_REGEX = "[#*_~`\"']";
    private static final char LF = '\n';
    private static final char CR = '\r';
    private static final String NL = String.valueOf(LF);
    private static final String SCOPE_GLOBAL = "global";
    private static final String SCOPE_PROJECT = "project";
    private static final String TYPE_TOPIC = "topic";
    private static final String KEY_TITLE = "title";
    private static final String KEY_TAGS = "tags";
    private static final String KEY_UPDATED_AT = "updatedAt";
    private static final String ROLE_USER = ChatTranscriptEntry.ROLE_USER;
    private static final String NO_ACTIVE_PROJECT = "No active project";
    private static final String GLOBAL_DIR_UNAVAILABLE = "Global memory directory is unavailable";
    private static final String MD_EXTENSION = ".md";
    private static final String TOPIC_HEADING_PREFIX = "# Topic: ";

    public record NoteDetail(String slug, String title, String tags, String updated, String body, String rawMarkdown) {
    }

    private final SettingsService settingsService;
    private final ProjectService projectService;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public MemoryService(SettingsService settingsService, ProjectService projectService,
            @Nullable ApplicationEventPublisher eventPublisher) {
        this.settingsService = settingsService;
        this.projectService = projectService;
        this.eventPublisher = eventPublisher;
    }

    @AgentDoc(value = "Reads the pinned global memory cheat-sheet (~/.roxycode/memory/MEMORY.md). Returns empty string if not present.", examples = "memoryService.readGlobalMemory()")
    public String readGlobalMemory() {
        return readMemory(getGlobalMemoryDir());
    }

    @AgentDoc(value = "Writes or overwrites the pinned global memory cheat-sheet (~/.roxycode/memory/MEMORY.md).", examples = "memoryService.updateGlobalMemory('# Global Preferences')")
    public EditorResult updateGlobalMemory(@AgentDoc("The Markdown content for global MEMORY.md") String content) {
        EditorResult result = updateMemory(getGlobalMemoryDir(), GLOBAL_DIR_UNAVAILABLE,
                "Global MEMORY.md updated successfully", content);
        if (result.success()) {
            publishMemoryChanged(true);
        }
        return result;
    }

    @AgentDoc(value = "Reads the pinned project memory cheat-sheet (<projectDataDir>/memory/MEMORY.md). Returns empty string if not present or no active project.", examples = "memoryService.readProjectMemory()")
    public String readProjectMemory() {
        return readMemory(resolveProjectSubdir(MEMORY_DIR));
    }

    @AgentDoc(value = "Writes or overwrites the pinned project memory cheat-sheet (<projectDataDir>/memory/MEMORY.md).", examples = "memoryService.updateProjectMemory('# Project Architecture')")
    public EditorResult updateProjectMemory(@AgentDoc("The Markdown content for project MEMORY.md") String content) {
        EditorResult result = updateMemory(resolveProjectSubdir(MEMORY_DIR), NO_ACTIVE_PROJECT,
                "Project MEMORY.md updated successfully", content);
        if (result.success()) {
            publishMemoryChanged(false);
        }
        return result;
    }

    @AgentDoc(value = "Saves or overwrites a global topic note with structured frontmatter in ~/.roxycode/memory/notes/<slug>.md.", examples = "memoryService.saveGlobalNote('coding-style', 'Coding Style Preferences', 'style, java', 'Use final fields where possible.')")
    public EditorResult saveGlobalNote(@AgentDoc("The note slug (lowercase alphanumeric and hyphens)") String slug,
            @AgentDoc("The note title") String title, @AgentDoc("Comma-separated tags") String tags,
            @AgentDoc("The Markdown note body content") String content) {
        EditorResult result = saveNote(getGlobalMemoryDir(), GLOBAL_DIR_UNAVAILABLE, SCOPE_GLOBAL, slug, title, tags,
                content);
        if (result.success()) {
            publishMemoryChanged(true);
        }
        return result;
    }

    @AgentDoc(value = "Saves or overwrites a project topic note with structured frontmatter in <projectDataDir>/memory/notes/<slug>.md.", examples = "memoryService.saveProjectNote('swing-html-tables', 'Swing HTML 3.2 Table Layout Rules', 'ui, swing, html', 'Use HTML tables for layout.')")
    public EditorResult saveProjectNote(@AgentDoc("The note slug (lowercase alphanumeric and hyphens)") String slug,
            @AgentDoc("The note title") String title, @AgentDoc("Comma-separated tags") String tags,
            @AgentDoc("The Markdown note body content") String content) {
        EditorResult result = saveNote(resolveProjectSubdir(MEMORY_DIR), NO_ACTIVE_PROJECT, SCOPE_PROJECT, slug, title,
                tags, content);
        if (result.success()) {
            publishMemoryChanged(false);
        }
        return result;
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
        EditorResult result = deleteNote(getGlobalMemoryDir(), GLOBAL_DIR_UNAVAILABLE, slug);
        if (result.success()) {
            publishMemoryChanged(true);
        }
        return result;
    }

    @AgentDoc(value = "Deletes an obsolete project topic note from <projectDataDir>/memory/notes/<slug>.md.", examples = "memoryService.deleteProjectNote('old-note')")
    public EditorResult deleteProjectNote(@AgentDoc("The note slug to delete") String slug) {
        EditorResult result = deleteNote(resolveProjectSubdir(MEMORY_DIR), NO_ACTIVE_PROJECT, slug);
        if (result.success()) {
            publishMemoryChanged(false);
        }
        return result;
    }

    @AgentDoc(value = "Returns a list of formatted summary lines for each global note in ~/.roxycode/memory/notes/*.md.", examples = "memoryService.listGlobalNotes()")
    public List<String> listGlobalNotes() {
        return listNotes(getGlobalMemoryDir());
    }

    @AgentDoc(value = "Returns a list of formatted summary lines for each project note in <projectDataDir>/memory/notes/*.md.", examples = "memoryService.listProjectNotes()")
    public List<String> listProjectNotes() {
        return listNotes(resolveProjectSubdir(MEMORY_DIR));
    }

    public List<NoteDetail> listGlobalNoteDetails() {
        return listNoteDetails(getGlobalMemoryDir());
    }

    public List<NoteDetail> listProjectNoteDetails() {
        return listNoteDetails(resolveProjectSubdir(MEMORY_DIR));
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
        return readArchivedFile(resolveProjectSubdir(ARCHIVE_TOPICS_SUBDIR), filename, TYPE_TOPIC);
    }

    private void publishMemoryChanged(boolean globalScope) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new MemoryChangedEvent(globalScope));
        }
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

    private static Path resolveNotePath(Path dir, String sanitizedSlug) {
        return dir.resolve(NOTES_SUBDIR).resolve(sanitizedSlug + MD_EXTENSION);
    }

    private static String readNote(Path dir, String slug) {
        String sanitized = sanitizeSlug(slug);
        if (dir == null) {
            return "";
        }
        return readFileIfExists(resolveNotePath(dir, sanitized));
    }

    private static NoteDetail buildNoteDetail(Path file) {
        String filename = file.getFileName().toString();
        String slug = StringUtils.removeEndIgnoreCase(filename, MD_EXTENSION);
        String rawMarkdown = readFileIfExists(file);
        Map<String, String> fm = FrontmatterUtils.parseFrontmatter(rawMarkdown);
        String title = StringUtils.defaultIfBlank(fm.get(KEY_TITLE), slug).trim();
        String tags = StringUtils.defaultString(fm.get(KEY_TAGS)).trim();
        String updated = StringUtils.defaultString(fm.get(KEY_UPDATED_AT)).trim();
        String body = extractNoteBody(rawMarkdown, title);
        return new NoteDetail(slug, title, tags, updated, body, rawMarkdown);
    }

    static String extractNoteBody(String rawMarkdown, String title) {
        String stripped = FrontmatterUtils.stripFrontmatter(rawMarkdown).stripLeading();
        if (StringUtils.isNotBlank(title)) {
            String expectedHeader = "# " + title.trim();
            if (stripped.startsWith(expectedHeader)) {
                int afterHeader = expectedHeader.length();
                if (afterHeader == stripped.length()) {
                    return "";
                }
                char nextChar = stripped.charAt(afterHeader);
                if (nextChar == LF || nextChar == CR) {
                    stripped = stripped.substring(afterHeader).stripLeading();
                }
            }
        }
        return stripped.stripTrailing();
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

    private Path resolveArchivedTopicPath(String filename) {
        if (projectService == null || !projectService.hasActiveProject()) {
            return null;
        }
        Path archiveDir = resolveProjectSubdir(ARCHIVE_TOPICS_SUBDIR);
        if (archiveDir == null) {
            return null;
        }
        String cleanName = sanitizeArchiveFilename(filename);
        Path target = archiveDir.resolve(cleanName).toAbsolutePath().normalize();
        if (!target.startsWith(archiveDir)) {
            throw new IllegalArgumentException("Invalid archived topic filename: " + filename);
        }
        return target;
    }

    /**
     * Renames an archived Q&amp;A topic by updating its frontmatter {@code title} and leading {@code # Topic:} heading
     * on disk, then publishes an {@link ArchiveUpdatedEvent}.
     *
     * @param filename
     *            the archived topic filename
     * @param newTitle
     *            the new topic title
     * @return the {@link EditorResult} containing the cleaned title on success or an error hint on failure
     */
    public EditorResult renameArchivedTopic(String filename, String newTitle) {
        if (projectService == null || !projectService.hasActiveProject()) {
            return new EditorResult(false, "", NO_ACTIVE_PROJECT);
        }
        String cleanedTitle = cleanTopicTitle(newTitle);
        if (cleanedTitle.isEmpty()) {
            return new EditorResult(false, "", "Topic title must not be blank.");
        }
        try {
            Path target = resolveArchivedTopicPath(filename);
            if (target == null || !Files.isRegularFile(target)) {
                return new EditorResult(false, "", "Archived topic not found: " + filename);
            }
            String rawMarkdown = Files.readString(target, StandardCharsets.UTF_8);
            Map<String, String> existingFm = FrontmatterUtils.parseFrontmatter(rawMarkdown);
            Map<String, Object> updatedFm = new LinkedHashMap<>(existingFm);
            updatedFm.put(KEY_TITLE, cleanedTitle);

            String body = FrontmatterUtils.stripFrontmatter(rawMarkdown).stripLeading();
            String updatedBody;
            if (body.startsWith(TOPIC_HEADING_PREFIX.trim())) {
                int newlineIdx = body.indexOf(LF);
                if (newlineIdx >= 0) {
                    updatedBody = TOPIC_HEADING_PREFIX + cleanedTitle + body.substring(newlineIdx);
                } else {
                    updatedBody = TOPIC_HEADING_PREFIX + cleanedTitle + NL;
                }
            } else {
                updatedBody = TOPIC_HEADING_PREFIX + cleanedTitle + NL + NL + body;
            }
            String updatedMarkdown = FrontmatterUtils.formatFrontmatter(updatedFm) + updatedBody;
            Files.writeString(target, updatedMarkdown, StandardCharsets.UTF_8);
            if (eventPublisher != null) {
                eventPublisher.publishEvent(
                        new ArchiveUpdatedEvent(ArchiveUpdatedEvent.TYPE_TOPIC, target.getFileName().toString()));
            }
            return new EditorResult(true, cleanedTitle, "");
        } catch (IllegalArgumentException | IOException e) {
            return new EditorResult(false, "", "Failed to rename topic: " + e.getMessage());
        }
    }

    /**
     * Deletes an archived Q&amp;A topic Markdown file from disk and publishes an {@link ArchiveUpdatedEvent}.
     *
     * @param filename
     *            the archived topic filename
     * @return the {@link EditorResult} containing the deleted filename on success or an error hint on failure
     */
    public EditorResult deleteArchivedTopic(String filename) {
        if (projectService == null || !projectService.hasActiveProject()) {
            return new EditorResult(false, "", NO_ACTIVE_PROJECT);
        }
        try {
            Path target = resolveArchivedTopicPath(filename);
            if (target == null || !Files.isRegularFile(target)) {
                return new EditorResult(false, "", "Archived topic not found: " + filename);
            }
            String deletedName = target.getFileName().toString();
            Files.deleteIfExists(target);
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new ArchiveUpdatedEvent(ArchiveUpdatedEvent.TYPE_TOPIC, deletedName));
            }
            return new EditorResult(true, deletedName, "");
        } catch (IllegalArgumentException | IOException e) {
            return new EditorResult(false, "", "Failed to delete topic: " + e.getMessage());
        }
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
            String resolvedTitle = resolveTopicTitle(title, entries);
            PathUtils.ArchiveFileTarget archiveTarget = PathUtils.prepareArchiveTarget(archiveDir, isoTimestamp,
                    resolvedTitle, PlanService.MAX_PLAN_SLUG_LENGTH, TYPE_TOPIC);
            String projectPath = ProjectService.resolveNormalizedProjectPath(projectService);
            String resolvedUser = StringUtils.defaultIfBlank(user, "User").trim();
            String resolvedTimestamp = StringUtils.defaultIfBlank(isoTimestamp, Instant.now().toString()).trim();

            Map<String, Object> frontmatter = new LinkedHashMap<>();
            frontmatter.put("id", archiveTarget.id());
            frontmatter.put("type", TYPE_TOPIC);
            frontmatter.put("project", projectService.getProjectName());
            frontmatter.put("projectPath", projectPath);
            frontmatter.put(KEY_TITLE, resolvedTitle);
            frontmatter.put("createdBy", resolvedUser);
            frontmatter.put("archivedOn", resolvedTimestamp);
            frontmatter.put("messageCount", entries.size());

            StringBuilder md = new StringBuilder(FrontmatterUtils.formatFrontmatter(frontmatter));
            md.append(TOPIC_HEADING_PREFIX).append(resolvedTitle).append(NL).append(NL)
                    .append("## Conversation Transcript").append(NL);

            for (ChatTranscriptEntry entry : entries) {
                String speaker;
                if (ROLE_USER.equalsIgnoreCase(entry.role())) {
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

    public static String resolveTopicTitle(String customTitle, List<ChatTranscriptEntry> entries) {
        String cleaned = cleanTopicTitle(customTitle);
        if (!cleaned.isEmpty()) {
            return cleaned;
        }
        return deriveDefaultTopicTitle(entries);
    }

    public static String deriveDefaultTopicTitle(List<ChatTranscriptEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return DEFAULT_TOPIC_TITLE;
        }
        String fallback = "";
        for (ChatTranscriptEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            String cleaned = cleanTopicTitle(entry.content());
            if (!cleaned.isEmpty()) {
                if (ROLE_USER.equalsIgnoreCase(entry.role())) {
                    return cleaned;
                }
                if (fallback.isEmpty()) {
                    fallback = cleaned;
                }
            }
        }
        return StringUtils.defaultIfEmpty(fallback, DEFAULT_TOPIC_TITLE);
    }

    /**
     * Cleans a raw topic title by taking the first non-blank line, stripping Markdown/quote characters, collapsing
     * whitespace, and truncating to {@link #MAX_TOPIC_TITLE_LENGTH}.
     *
     * @param rawTitle
     *            the raw title text
     * @return the cleaned topic title, or an empty string if blank
     */
    public static String cleanTopicTitle(String rawTitle) {
        if (StringUtils.isBlank(rawTitle)) {
            return "";
        }
        String firstLine = rawTitle.lines().map(String::trim).filter(line -> !line.isEmpty()).findFirst().orElse("");
        if (firstLine.isEmpty()) {
            return "";
        }
        String cleaned = firstLine.replaceAll(STRIP_TITLE_CHARS_REGEX, " ").replaceAll("\\s+", " ")
                .replaceAll("^[^a-zA-Z0-9]+|[^a-zA-Z0-9]+$", "").trim();
        if (cleaned.length() > MAX_TOPIC_TITLE_LENGTH) {
            return cleaned.substring(0, MAX_TOPIC_TITLE_LENGTH).replaceAll("[^a-zA-Z0-9]+$", "").trim();
        }
        return cleaned;
    }

    public record ArchiveEntrySummary(String filename, String type, String title, String createdBy, String timestamp,
            int messageCount, String matchSnippet, long totalTokens, long cachedTokens, int cacheHitPercent,
            double estimatedCostUsd) {

        public ArchiveEntrySummary withMatchSnippet(String snippet) {
            return new ArchiveEntrySummary(filename, type, title, createdBy, timestamp, messageCount, snippet,
                    totalTokens, cachedTokens, cacheHitPercent, estimatedCostUsd);
        }
    }

    public List<ArchiveEntrySummary> listArchivedTopicSummaries() {
        return listArchivedEntrySummaries(resolveProjectSubdir(ARCHIVE_TOPICS_SUBDIR), TYPE_TOPIC);
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
        String title = StringUtils.defaultIfBlank(fm.get(KEY_TITLE), StringUtils.removeEnd(filename, MD_EXTENSION))
                .trim();
        String createdBy = StringUtils
                .firstNonBlank(fm.get("createdBy"), fm.get("completedBy"), fm.get("approvedBy"), "User").trim();
        String timestamp = StringUtils.defaultString(
                StringUtils.firstNonBlank(fm.get("archivedOn"), fm.get("completedOn"), fm.get("approvalTimestamp")))
                .trim();
        int messageCount = NumberUtils.toInt(StringUtils.trim(fm.get("messageCount")), 0);
        long totalTokens = NumberUtils.toLong(StringUtils.trim(fm.get("totalTokens")), 0L);
        long cachedTokens = NumberUtils.toLong(StringUtils.trim(fm.get("cachedTokens")), 0L);
        int cacheHitPercent = NumberUtils.toInt(StringUtils.trim(fm.get("cacheHitPercent")), 0);
        double estimatedCostUsd = NumberUtils.toDouble(StringUtils.trim(fm.get("estimatedCostUsd")), 0.0);
        return new ArchiveEntrySummary(filename, type, title, createdBy, timestamp, messageCount, "", totalTokens,
                cachedTokens, cacheHitPercent, estimatedCostUsd);
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
                String filePart = PathUtils.normalizeRelativePath(line.substring(0, colonIdx).trim());
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
        if (rawSlug.contains("..") || rawSlug.indexOf('/') >= 0 || rawSlug.indexOf('\\') >= 0) {
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
        if (trimmed.contains("..") || trimmed.indexOf('/') >= 0 || trimmed.indexOf('\\') >= 0) {
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
        frontmatter.put(KEY_TITLE, resolvedTitle);
        frontmatter.put(KEY_TAGS, resolvedTags);
        frontmatter.put(KEY_UPDATED_AT, Instant.now().toString());

        StringBuilder md = new StringBuilder(FrontmatterUtils.formatFrontmatter(frontmatter));

        if (!body.startsWith("# ") && !body.startsWith("#\t")) {
            md.append("# ").append(resolvedTitle).append(NL).append(NL);
        }
        md.append(body);
        if (!body.endsWith(NL)) {
            md.append(NL);
        }

        return writeMarkdownFile(resolveNotePath(memoryRoot, sanitizedSlug), md.toString(),
                "Saved " + scope + " note: notes/" + sanitizedSlug + MD_EXTENSION);
    }

    private EditorResult deleteNote(Path dir, String missingMsg, String slug) {
        String sanitizedSlug = sanitizeSlug(slug);
        if (dir == null) {
            return new EditorResult(false, "", missingMsg);
        }
        Path notePath = resolveNotePath(dir, sanitizedSlug);
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

    private List<NoteDetail> listNoteDetails(Path memoryRoot) {
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
                    .map(MemoryService::buildNoteDetail).toList();
        } catch (IOException e) {
            log.warn("Failed to list note details in {}: {}", notesDir, e.getMessage());
            return List.of();
        }
    }

    private List<String> listNotes(Path memoryRoot) {
        List<NoteDetail> details = listNoteDetails(memoryRoot);
        List<String> summaries = new ArrayList<>(details.size());
        for (NoteDetail detail : details) {
            summaries.add(formatNoteSummaryLine(detail));
        }
        return summaries;
    }

    private static String formatNoteSummaryLine(NoteDetail detail) {
        String filename = detail.slug() + MD_EXTENSION;
        String updatedAtSuffix = "";
        if (!detail.updated().isEmpty()) {
            updatedAtSuffix = " (" + detail.updated() + ")";
        }
        return filename + " - " + '"' + detail.title() + '"' + " [" + detail.tags() + "]" + updatedAtSuffix;
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
        return ProjectService.resolveProjectDataPathSafely(projectService, subdir);
    }
}
