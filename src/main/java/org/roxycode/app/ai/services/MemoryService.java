package org.roxycode.app.ai.services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
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
@AgentDoc("Provides unified Global and Project Agent Memory (memories.json with always-on summaries and on-demand details, archived plans, archived Q&A topics, and unified RipGrep search) across all workflow phases.")
public class MemoryService {
    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    public static final String SCOPE_GLOBAL = "global";
    public static final String SCOPE_PROJECT = "project";
    static final String MEMORY_DIR = "memory";
    static final String MEMORIES_FILE = "memories.json";
    static final String ARCHIVE_PLANS_SUBDIR = PlanService.ARCHIVE_PLANS_SUBDIR;
    public static final String ARCHIVE_TOPICS_SUBDIR = "archive/topics";
    public static final String DEFAULT_TOPIC_TITLE = "Codebase Q&A Topic";
    public static final int MAX_TOPIC_TITLE_LENGTH = 80;
    public static final int MAX_SLUG_LENGTH = 50;
    private static final int SEARCH_CONTEXT_LINES = 2;
    private static final String STRIP_TITLE_CHARS_REGEX = "[#*_~`\"']";
    private static final char LF = '\n';
    private static final String NL = String.valueOf(LF);
    private static final String TYPE_TOPIC = "topic";
    private static final String TYPE_PLAN = "plan";
    private static final String KEY_TITLE = "title";
    private static final String ROLE_USER = ChatTranscriptEntry.ROLE_USER;
    private static final String NO_ACTIVE_PROJECT = "No active project";
    private static final String GLOBAL_DIR_UNAVAILABLE = "Global memory directory is unavailable";
    private static final String MD_EXTENSION = ".md";
    private static final String TOPIC_HEADING_PREFIX = "# Topic: ";
    private static final String PLAN_HEADING_PREFIX = "# Implementation Plan: ";
    private static final String DOC_SCOPE = "The memory scope: 'global' or 'project'";
    private static final String DOC_ID_UPSERT = "The memory id/slug (lowercase alphanumeric and hyphens)";
    private static final String DOC_SUMMARY = "The concise always-on summary";
    private static final String DOC_DETAILS = "Optional extended Markdown details (read on demand)";

    /**
     * A single persisted memory entry in {@code memories.json}. Provides both record accessors and JavaBean getters for
     * JEXL evaluation.
     */
    public record MemoryEntry(String id, String summary, List<String> tags, String details, String updatedAt) {
        public MemoryEntry {
            id = StringUtils.defaultString(id).trim();
            summary = StringUtils.defaultString(summary).trim();
            if (tags == null) {
                tags = List.of();
            } else {
                tags = List.copyOf(tags);
            }
            details = StringUtils.defaultString(details).stripTrailing();
            updatedAt = StringUtils.defaultString(updatedAt).trim();
        }

        public String getId() {
            return id;
        }

        public String getSummary() {
            return summary;
        }

        public List<String> getTags() {
            return tags;
        }

        public String getDetails() {
            return details;
        }

        public String getUpdatedAt() {
            return updatedAt;
        }
    }

    /**
     * Lightweight metadata view of a memory entry for prompt injection and listing. Provides both record accessors and
     * JavaBean getters for JEXL evaluation.
     */
    public record MemoryMetadata(String scope, String id, String summary, List<String> tags, boolean hasDetails,
            String updatedAt) {
        public MemoryMetadata {
            scope = StringUtils.defaultString(scope).trim();
            id = StringUtils.defaultString(id).trim();
            summary = StringUtils.defaultString(summary).trim();
            if (tags == null) {
                tags = List.of();
            } else {
                tags = List.copyOf(tags);
            }
            updatedAt = StringUtils.defaultString(updatedAt).trim();
        }

        public String getScope() {
            return scope;
        }

        public String getId() {
            return id;
        }

        public String getSummary() {
            return summary;
        }

        public List<String> getTags() {
            return tags;
        }

        public boolean getHasDetails() {
            return hasDetails;
        }

        public boolean isHasDetails() {
            return hasDetails;
        }

        public String getUpdatedAt() {
            return updatedAt;
        }
    }

    /**
     * JSON root wrapper for {@code memories.json}.
     */
    public record MemoryStoreFile(List<MemoryEntry> memories) {
        public MemoryStoreFile {
            if (memories == null) {
                memories = List.of();
            } else {
                memories = List.copyOf(memories);
            }
        }
    }

    private final SettingsService settingsService;
    private final ProjectService projectService;
    private final ApplicationEventPublisher eventPublisher;
    private final Object storeLock = new Object();

    @Autowired
    public MemoryService(SettingsService settingsService, ProjectService projectService,
            @Nullable ApplicationEventPublisher eventPublisher) {
        this.settingsService = settingsService;
        this.projectService = projectService;
        this.eventPublisher = eventPublisher;
    }

    @AgentDoc(value = "Lists metadata (scope, id, summary, tags, hasDetails, updatedAt) for all memories in the given scope ('global' or 'project').", examples = "memoryService.list('project')")
    public List<MemoryMetadata> list(@AgentDoc(DOC_SCOPE) String scope) {
        String normalizedScope = normalizeScope(scope);
        Path dir = resolveScopeDir(normalizedScope);
        if (dir == null) {
            return List.of();
        }
        List<MemoryEntry> entries = loadEntriesForDir(dir);
        List<MemoryMetadata> metadata = new ArrayList<>(entries.size());
        for (MemoryEntry entry : entries) {
            metadata.add(toMetadata(normalizedScope, entry));
        }
        return List.copyOf(metadata);
    }

    @AgentDoc(value = "Lists metadata for all memories across both 'global' and 'project' scopes.", examples = "memoryService.listAll()")
    public List<MemoryMetadata> listAll() {
        List<MemoryMetadata> all = new ArrayList<>();
        all.addAll(list(SCOPE_GLOBAL));
        all.addAll(list(SCOPE_PROJECT));
        return List.copyOf(all);
    }

    @AgentDoc(value = "Reads a single full memory entry (including extended details) from the given scope ('global' or 'project') by id. Returns null if not found.", examples = "memoryService.read('project', 'swing-html-tables')")
    public @Nullable MemoryEntry read(@AgentDoc(DOC_SCOPE) String scope, @AgentDoc("The memory id/slug") String id) {
        String normalizedScope = normalizeScope(scope);
        String sanitizedId = sanitizeSlug(id);
        Path dir = resolveScopeDir(normalizedScope);
        if (dir == null) {
            return null;
        }
        List<MemoryEntry> entries = loadEntriesForDir(dir);
        for (MemoryEntry entry : entries) {
            if (entry.id().equals(sanitizedId)) {
                return entry;
            }
        }
        return null;
    }

    @AgentDoc(value = "Reads all full memory entries (including extended details) in the given scope ('global' or 'project').", examples = "memoryService.readAll('global')")
    public List<MemoryEntry> readAll(@AgentDoc(DOC_SCOPE) String scope) {
        String normalizedScope = normalizeScope(scope);
        Path dir = resolveScopeDir(normalizedScope);
        if (dir == null) {
            return List.of();
        }
        return loadEntriesForDir(dir);
    }

    @AgentDoc(value = "Creates or updates a concise memory entry (summary only) in the given scope ('global' or 'project').", examples = "memoryService.put('global', 'no-ternary', 'Never use ternary operators; always use explicit if/else blocks.')")
    public EditorResult put(@AgentDoc(DOC_SCOPE) String scope, @AgentDoc(DOC_ID_UPSERT) String id,
            @AgentDoc(DOC_SUMMARY) String summary) {
        return put(scope, id, summary, List.of(), "");
    }

    @AgentDoc(value = "Creates or updates a full memory entry (summary, tags, and optional Markdown details) in the given scope ('global' or 'project').", examples = "memoryService.put('project', 'swing-html-tables', 'Use HTML 3.2 tables for Swing layouts.', ['ui', 'swing'], '## Details\nUse HTML tables.')")
    public EditorResult put(@AgentDoc(DOC_SCOPE) String scope, @AgentDoc(DOC_ID_UPSERT) String id,
            @AgentDoc(DOC_SUMMARY) String summary, @AgentDoc("Optional array of tags") @Nullable String[] tags,
            @AgentDoc(DOC_DETAILS) @Nullable String details) {
        List<String> tagList;
        if (tags == null) {
            tagList = List.of();
        } else {
            tagList = Arrays.asList(tags);
        }
        return put(scope, id, summary, tagList, details);
    }

    @AgentDoc(value = "Creates or updates a full memory entry (summary, tags, and optional Markdown details) in the given scope ('global' or 'project').", examples = "memoryService.put('project', 'swing-html-tables', 'Use HTML 3.2 tables for Swing layouts.', ['ui', 'swing'], '## Details\nUse HTML tables.')")
    public EditorResult put(@AgentDoc(DOC_SCOPE) String scope, @AgentDoc(DOC_ID_UPSERT) String id,
            @AgentDoc(DOC_SUMMARY) String summary, @AgentDoc("Optional list of tags") @Nullable List<String> tags,
            @AgentDoc(DOC_DETAILS) @Nullable String details) {
        String normalizedScope = normalizeScope(scope);
        String sanitizedId = sanitizeSlug(id);
        String cleanedSummary = StringUtils.defaultString(summary).trim();
        if (cleanedSummary.isEmpty()) {
            return new EditorResult(false, "", "Memory summary must not be blank");
        }
        Path dir = resolveScopeDir(normalizedScope);
        if (dir == null) {
            return new EditorResult(false, "", missingScopeMessage(normalizedScope));
        }

        List<String> normalizedTags = normalizeTags(tags);
        String cleanedDetails = StringUtils.defaultString(details).stripTrailing();
        MemoryEntry updatedEntry = new MemoryEntry(sanitizedId, cleanedSummary, normalizedTags, cleanedDetails,
                Instant.now().toString());

        synchronized (storeLock) {
            List<MemoryEntry> existing = new ArrayList<>(loadEntriesForDir(dir));
            boolean replaced = false;
            for (int i = 0; i < existing.size(); i++) {
                if (existing.get(i).id().equals(sanitizedId)) {
                    existing.set(i, updatedEntry);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                existing.add(updatedEntry);
            }
            EditorResult writeResult = writeStore(dir, existing,
                    "Saved " + normalizedScope + " memory: " + sanitizedId);
            if (writeResult.success()) {
                publishMemoryChanged(SCOPE_GLOBAL.equals(normalizedScope));
            }
            return writeResult;
        }
    }

    @AgentDoc(value = "Deletes a memory entry by id from the given scope ('global' or 'project').", examples = "memoryService.delete('project', 'obsolete-rule')")
    public EditorResult delete(@AgentDoc(DOC_SCOPE) String scope, @AgentDoc("The memory id/slug to delete") String id) {
        String normalizedScope = normalizeScope(scope);
        String sanitizedId = sanitizeSlug(id);
        Path dir = resolveScopeDir(normalizedScope);
        if (dir == null) {
            return new EditorResult(false, "", missingScopeMessage(normalizedScope));
        }

        synchronized (storeLock) {
            List<MemoryEntry> existing = new ArrayList<>(loadEntriesForDir(dir));
            boolean removed = existing.removeIf(entry -> entry.id().equals(sanitizedId));
            if (!removed) {
                return new EditorResult(false, "", "Memory not found in " + normalizedScope + " scope: " + sanitizedId);
            }
            EditorResult writeResult = writeStore(dir, existing,
                    "Deleted " + normalizedScope + " memory: " + sanitizedId);
            if (writeResult.success()) {
                publishMemoryChanged(SCOPE_GLOBAL.equals(normalizedScope));
            }
            return writeResult;
        }
    }

    @AgentDoc(value = "Searches memories across both 'global' and 'project' scopes by case-insensitive regex or substring across id, summary, tags, and details.", examples = "memoryService.find('caffeine')")
    public List<MemoryMetadata> find(@AgentDoc("The search query or regex pattern") String query) {
        List<MemoryMetadata> results = new ArrayList<>();
        results.addAll(find(SCOPE_GLOBAL, query));
        results.addAll(find(SCOPE_PROJECT, query));
        return List.copyOf(results);
    }

    @AgentDoc(value = "Searches memories within the given scope ('global' or 'project') by case-insensitive regex or substring across id, summary, tags, and details.", examples = "memoryService.find('project', 'swing')")
    public List<MemoryMetadata> find(@AgentDoc(DOC_SCOPE) String scope,
            @AgentDoc("The search query or regex pattern") String query) {
        String normalizedScope = normalizeScope(scope);
        if (StringUtils.isBlank(query)) {
            return list(normalizedScope);
        }
        String trimmedQuery = query.trim();
        Pattern compiledPattern = compileSearchPattern(trimmedQuery);
        List<MemoryEntry> entries = readAll(normalizedScope);
        List<MemoryMetadata> matches = new ArrayList<>();
        for (MemoryEntry entry : entries) {
            if (matchesEntry(entry, trimmedQuery, compiledPattern)) {
                matches.add(toMetadata(normalizedScope, entry));
            }
        }
        return List.copyOf(matches);
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

    private static String normalizeScope(String scope) {
        if (StringUtils.isBlank(scope)) {
            throw new IllegalArgumentException("Memory scope must be 'global' or 'project'");
        }
        String normalized = scope.trim().toLowerCase(Locale.ROOT);
        if (SCOPE_GLOBAL.equals(normalized) || SCOPE_PROJECT.equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("Invalid memory scope: '" + scope + "'. Must be 'global' or 'project'.");
    }

    private static String missingScopeMessage(String normalizedScope) {
        if (SCOPE_GLOBAL.equals(normalizedScope)) {
            return GLOBAL_DIR_UNAVAILABLE;
        }
        return NO_ACTIVE_PROJECT;
    }

    private @Nullable Path resolveScopeDir(String normalizedScope) {
        if (SCOPE_GLOBAL.equals(normalizedScope)) {
            return getGlobalMemoryDir();
        }
        return resolveProjectSubdir(MEMORY_DIR);
    }

    private List<MemoryEntry> loadEntriesForDir(Path dir) {
        synchronized (storeLock) {
            Path storePath = dir.resolve(MEMORIES_FILE);
            MemoryStoreFile store = PathUtils.readJsonOrDefault(storePath, MemoryStoreFile.class,
                    new MemoryStoreFile(List.of()));
            return sortEntries(store.memories());
        }
    }

    private static List<MemoryEntry> sortEntries(List<MemoryEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<MemoryEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(MemoryEntry::id, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(sorted);
    }

    private static EditorResult writeStore(Path dir, List<MemoryEntry> entries, String successMessage) {
        Path storePath = dir.resolve(MEMORIES_FILE);
        try {
            PathUtils.writeJson(storePath, new MemoryStoreFile(sortEntries(entries)));
            return new EditorResult(true, successMessage, null);
        } catch (IOException e) {
            log.error("Failed to write memories store {}: {}", storePath, e.getMessage());
            return new EditorResult(false, "", "Failed to write memories store: " + e.getMessage());
        }
    }

    private static MemoryMetadata toMetadata(String scope, MemoryEntry entry) {
        return new MemoryMetadata(scope, entry.id(), entry.summary(), entry.tags(),
                StringUtils.isNotBlank(entry.details()), entry.updatedAt());
    }

    private static @Nullable Pattern compileSearchPattern(String query) {
        try {
            return Pattern.compile(query, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    private static boolean matchesEntry(MemoryEntry entry, String query, @Nullable Pattern pattern) {
        if (matchesField(entry.id(), query, pattern) || matchesField(entry.summary(), query, pattern)
                || matchesField(entry.details(), query, pattern)) {
            return true;
        }
        for (String tag : entry.tags()) {
            if (matchesField(tag, query, pattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesField(String value, String query, @Nullable Pattern pattern) {
        if (StringUtils.isEmpty(value)) {
            return false;
        }
        if (pattern != null && pattern.matcher(value).find()) {
            return true;
        }
        return StringUtils.containsIgnoreCase(value, query);
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

    private Path resolveArchivedFilePath(String subdir, String filename, String label) {
        if (projectService == null || !projectService.hasActiveProject()) {
            return null;
        }
        Path archiveDir = resolveProjectSubdir(subdir);
        if (archiveDir == null) {
            return null;
        }
        String cleanName = sanitizeArchiveFilename(filename);
        Path target = archiveDir.resolve(cleanName).toAbsolutePath().normalize();
        if (!target.startsWith(archiveDir)) {
            throw new IllegalArgumentException("Invalid archived " + label + " filename: " + filename);
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
        return renameArchivedEntry(ARCHIVE_TOPICS_SUBDIR, filename, newTitle, TOPIC_HEADING_PREFIX, "Topic", TYPE_TOPIC,
                ArchiveUpdatedEvent.TYPE_TOPIC);
    }

    /**
     * Renames an archived implementation plan by updating its frontmatter {@code title} and leading
     * {@code # Implementation Plan:} heading on disk, then publishes an {@link ArchiveUpdatedEvent}.
     *
     * @param filename
     *            the archived plan filename
     * @param newTitle
     *            the new plan title
     * @return the {@link EditorResult} containing the cleaned title on success or an error hint on failure
     */
    public EditorResult renameArchivedPlan(String filename, String newTitle) {
        return renameArchivedEntry(ARCHIVE_PLANS_SUBDIR, filename, newTitle, PLAN_HEADING_PREFIX, "Plan", TYPE_PLAN,
                ArchiveUpdatedEvent.TYPE_PLAN);
    }

    /**
     * Deletes an archived Q&amp;A topic Markdown file from disk and publishes an {@link ArchiveUpdatedEvent}.
     *
     * @param filename
     *            the archived topic filename
     * @return the {@link EditorResult} containing the deleted filename on success or an error hint on failure
     */
    public EditorResult deleteArchivedTopic(String filename) {
        return deleteArchivedEntry(ARCHIVE_TOPICS_SUBDIR, filename, TYPE_TOPIC, ArchiveUpdatedEvent.TYPE_TOPIC);
    }

    /**
     * Deletes an archived implementation plan Markdown file from disk and publishes an {@link ArchiveUpdatedEvent}.
     *
     * @param filename
     *            the archived plan filename
     * @return the {@link EditorResult} containing the deleted filename on success or an error hint on failure
     */
    public EditorResult deleteArchivedPlan(String filename) {
        return deleteArchivedEntry(ARCHIVE_PLANS_SUBDIR, filename, TYPE_PLAN, ArchiveUpdatedEvent.TYPE_PLAN);
    }

    private EditorResult renameArchivedEntry(String subdir, String filename, String newTitle, String headingPrefix,
            String capitalizedLabel, String label, String eventType) {
        if (projectService == null || !projectService.hasActiveProject()) {
            return new EditorResult(false, "", NO_ACTIVE_PROJECT);
        }
        String cleanedTitle = cleanTopicTitle(newTitle);
        if (cleanedTitle.isEmpty()) {
            return new EditorResult(false, "", capitalizedLabel + " title must not be blank.");
        }
        try {
            Path target = resolveArchivedFilePath(subdir, filename, label);
            if (target == null || !Files.isRegularFile(target)) {
                return new EditorResult(false, "", "Archived " + label + " not found: " + filename);
            }
            String rawMarkdown = Files.readString(target, StandardCharsets.UTF_8);
            Map<String, String> existingFm = FrontmatterUtils.parseFrontmatter(rawMarkdown);
            Map<String, Object> updatedFm = new LinkedHashMap<>(existingFm);
            updatedFm.put(KEY_TITLE, cleanedTitle);

            String body = FrontmatterUtils.stripFrontmatter(rawMarkdown).stripLeading();
            String updatedBody;
            if (body.startsWith(headingPrefix.trim())) {
                int newlineIdx = body.indexOf(LF);
                if (newlineIdx >= 0) {
                    updatedBody = headingPrefix + cleanedTitle + body.substring(newlineIdx);
                } else {
                    updatedBody = headingPrefix + cleanedTitle + NL;
                }
            } else {
                updatedBody = headingPrefix + cleanedTitle + NL + NL + body;
            }
            String updatedMarkdown = FrontmatterUtils.formatFrontmatter(updatedFm) + updatedBody;
            Files.writeString(target, updatedMarkdown, StandardCharsets.UTF_8);
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new ArchiveUpdatedEvent(eventType, target.getFileName().toString()));
            }
            return new EditorResult(true, cleanedTitle, "");
        } catch (IllegalArgumentException | IOException e) {
            return new EditorResult(false, "", "Failed to rename " + label + ": " + e.getMessage());
        }
    }

    private EditorResult deleteArchivedEntry(String subdir, String filename, String label, String eventType) {
        if (projectService == null || !projectService.hasActiveProject()) {
            return new EditorResult(false, "", NO_ACTIVE_PROJECT);
        }
        try {
            Path target = resolveArchivedFilePath(subdir, filename, label);
            if (target == null || !Files.isRegularFile(target)) {
                return new EditorResult(false, "", "Archived " + label + " not found: " + filename);
            }
            String deletedName = target.getFileName().toString();
            Files.deleteIfExists(target);
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new ArchiveUpdatedEvent(eventType, deletedName));
            }
            return new EditorResult(true, deletedName, "");
        } catch (IllegalArgumentException | IOException e) {
            return new EditorResult(false, "", "Failed to delete " + label + ": " + e.getMessage());
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
            throw new IllegalArgumentException("Memory id must not be blank");
        }
        if (rawSlug.contains("..") || rawSlug.indexOf('/') >= 0 || rawSlug.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Invalid memory id (path traversal not allowed): " + rawSlug);
        }
        String trimmed = StringUtils.removeEndIgnoreCase(rawSlug.trim(), MD_EXTENSION).trim();
        String sanitized = PathUtils.sanitizeSlug(trimmed, MAX_SLUG_LENGTH, "");
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("Memory id must contain alphanumeric characters: " + rawSlug);
        }
        return sanitized;
    }

    public static String deriveSlugFromSummary(String summary) {
        if (StringUtils.isBlank(summary)) {
            return "memory";
        }
        String[] words = summary.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(words.length, 6);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(words[i]);
        }
        return PathUtils.sanitizeSlug(sb.toString(), MAX_SLUG_LENGTH, "memory");
    }

    public static List<String> normalizeTags(@Nullable List<String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String tag : rawTags) {
            if (StringUtils.isNotBlank(tag)) {
                String cleaned = tag.trim().toLowerCase(Locale.ROOT);
                if (!cleaned.isEmpty()) {
                    normalized.add(cleaned);
                }
            }
        }
        return List.copyOf(normalized);
    }

    public static List<String> parseTagsString(@Nullable String rawTags) {
        if (StringUtils.isBlank(rawTags)) {
            return List.of();
        }
        String[] parts = rawTags.split(",");
        List<String> list = new ArrayList<>(parts.length);
        for (String part : parts) {
            list.add(part);
        }
        return normalizeTags(list);
    }

    public static String formatTagsDisplay(@Nullable List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        return String.join(", ", tags);
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
