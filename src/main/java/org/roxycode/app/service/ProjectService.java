package org.roxycode.app.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.model.AgentProfile;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Manages project configuration, data directories, and reference folder mounts.
 */
@Service
public class ProjectService {
    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);
    private static final String PROJECTS_SUBDIR = "projects";
    private static final int MAX_PROJECT_NAME_LENGTH = 40;
    private static final String DEFAULT_REF_ALIAS = "ref";
    private static final String CONTEXT_SEPARATOR = "--";
    private static final Pattern RG_LINE_PREFIX_PATTERN = Pattern.compile("^(.+?)([:-][0-9]+[:-].*)$", Pattern.DOTALL);
    static final String PROJECT_CONFIG_FILE = "project.toml";
    public static final String CONVENTION_CONTEXT_DIR = "context";
    public static final String PROJECT_REFS_REL_DIR = ".roxy/refs";
    public static final String GLOBAL_REFS_SUBDIR = "refs";
    public static final String GLOBAL_CONTEXT_ALIAS = "global-context";
    static final String ROXYCODE_TMP_DIR = ".roxycode/tmp";
    private static final String ROXYCODE_TMP_PREFIX = ".roxycode/tmp/";
    private static final String DATA_TMP_DIR = "tmp";
    private static final String NO_PROJECT_ROOT_MSG = "Project root is not set.";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProjectConfig(String agentProfile, boolean buildConfirmed) {
        public static final ProjectConfig DEFAULT = new ProjectConfig("", false);

        public ProjectConfig {
            agentProfile = normalizeProjectAgentProfile(agentProfile);
        }
    }

    public record ReferenceFolderMount(String alias, String configuredPath, Path rootPath, boolean globalScope) {
        public ReferenceFolderMount {
            alias = StringUtils.removeStart(StringUtils.trimToEmpty(alias), "@");
            configuredPath = StringUtils.trimToEmpty(configuredPath);
            if (rootPath != null) {
                rootPath = rootPath.toAbsolutePath().normalize();
            }
        }

        public String aliasPrefix() {
            return "@" + alias;
        }

        @Override
        public String toString() {
            return "@" + alias + " (" + configuredPath + ")";
        }
    }

    public record ResolvedSearchTarget(Path workingDir, String relativeTarget, String prefixAlias) {
        public ResolvedSearchTarget {
            if (workingDir != null) {
                workingDir = workingDir.toAbsolutePath().normalize();
            }
            relativeTarget = StringUtils.defaultIfBlank(relativeTarget, ".");
            prefixAlias = StringUtils.trimToEmpty(prefixAlias);
        }

        public boolean isReferenceMount() {
            return !prefixAlias.isEmpty();
        }

        public String formatResultPath(String rawRelativePath) {
            String norm = PathUtils.normalizeRelativePath(StringUtils.trimToEmpty(rawRelativePath));
            if (prefixAlias.isEmpty()) {
                return norm;
            }
            if (norm.isEmpty() || ".".equals(norm)) {
                return prefixAlias;
            }
            if (norm.equals(prefixAlias) || norm.startsWith(prefixAlias + "/")) {
                return norm;
            }
            return prefixAlias + "/" + norm;
        }

        public String formatMatchLine(String rawLine) {
            if (rawLine == null) {
                return "";
            }
            String trimmed = rawLine.trim();
            if (CONTEXT_SEPARATOR.equals(trimmed)) {
                return CONTEXT_SEPARATOR;
            }
            Matcher matcher = RG_LINE_PREFIX_PATTERN.matcher(rawLine);
            if (matcher.matches()) {
                return formatResultPath(matcher.group(1)) + matcher.group(2);
            }
            return formatResultPath(trimmed);
        }
    }

    private record ParsedReferencePath(ReferenceFolderMount mount, String subPath) {
    }

    private static String normalizeProjectAgentProfile(String rawAgentProfile) {
        if (StringUtils.isBlank(rawAgentProfile)) {
            return "";
        }
        return AgentProfile.fromString(rawAgentProfile).getId();
    }

    private record FileStamp(Instant lastModified, long size) {
    }

    private record CachedProjectConfig(Path filePath, FileStamp stamp, ProjectConfig config) {
    }

    private final SettingsService settingsService;
    private final ApplicationEventPublisher eventPublisher;
    private volatile Path currentProjectRoot;
    private volatile CachedProjectConfig cachedProjectConfig;

    public ProjectService(SettingsService settingsService, ApplicationEventPublisher eventPublisher) {
        this.settingsService = settingsService;
        this.eventPublisher = Objects.requireNonNullElse(eventPublisher, event -> {
        });
        String savedPath = settingsService.getSettings().getCurrentProjectPath();
        if (StringUtils.isNotEmpty(savedPath)) {
            this.currentProjectRoot = Paths.get(savedPath);
            recordRecentProjectInSettings(this.currentProjectRoot);
        }
    }

    /**
     * Returns the current project root path.
     * 
     * @return the project root path, or null if no project is active
     */
    public Path getCurrentProjectRoot() {
        return currentProjectRoot;
    }

    /**
     * Checks if there is an active project.
     * 
     * @return true if an active project exists
     */
    public boolean hasActiveProject() {
        return currentProjectRoot != null;
    }

    /**
     * Sets the current project root path and publishes a ProjectChangedEvent.
     * 
     * @param newRoot
     *            the new project root path
     */
    public void setCurrentProjectRoot(Path newRoot) {
        if (newRoot != null) {
            this.currentProjectRoot = newRoot;
            settingsService.getSettings().setCurrentProjectPath(newRoot.toAbsolutePath().toString());
            recordRecentProjectInSettings(newRoot);
            settingsService.saveSettings();
            eventPublisher.publishEvent(new ProjectChangedEvent(newRoot));
        }
    }

    /**
     * Returns up to {@link AppSettings#MAX_RECENT_PROJECTS} recent project paths in most-recently-used order.
     *
     * @return the list of recent project paths
     */
    public List<Path> getRecentProjects() {
        if (settingsService == null || settingsService.getSettings() == null) {
            return List.of();
        }
        List<Path> result = collectNormalizedRecentPaths(settingsService.getSettings().getRecentProjectPaths(), null);
        if (result.isEmpty() && currentProjectRoot != null) {
            return List.of(currentProjectRoot.toAbsolutePath().normalize());
        }
        return List.copyOf(result);
    }

    private void recordRecentProjectInSettings(Path projectRoot) {
        if (projectRoot == null || settingsService == null || settingsService.getSettings() == null) {
            return;
        }
        AppSettings settings = settingsService.getSettings();
        List<Path> updated = collectNormalizedRecentPaths(settings.getRecentProjectPaths(),
                projectRoot.toAbsolutePath().normalize());
        List<String> updatedStrings = new ArrayList<>(updated.size());
        for (Path p : updated) {
            updatedStrings.add(p.toString());
        }
        settings.setRecentProjectPaths(updatedStrings);
    }

    private static List<Path> collectNormalizedRecentPaths(List<String> rawPaths, Path prependedPath) {
        List<Path> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (prependedPath != null && seen.add(prependedPath.toString())) {
            result.add(prependedPath);
        }
        if (rawPaths != null) {
            for (String raw : rawPaths) {
                if (StringUtils.isNotBlank(raw) && result.size() < AppSettings.MAX_RECENT_PROJECTS) {
                    Path normalized = Paths.get(raw.trim()).toAbsolutePath().normalize();
                    if (seen.add(normalized.toString())) {
                        result.add(normalized);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Returns the name of the current project derived from the root path.
     * 
     * @return the project name, or "No Project" if none active
     */
    public String getProjectName() {
        Path projectRoot = getCurrentProjectRoot();
        if (projectRoot == null) {
            return "No Project";
        }
        Path fileName = projectRoot.getFileName();
        return Objects.requireNonNullElse(fileName, projectRoot).toString();
    }

    private static FileStamp readFileStamp(Path path) {
        try {
            if (Files.exists(path)) {
                return new FileStamp(Files.getLastModifiedTime(path).toInstant(), Files.size(path));
            }
        } catch (IOException e) {
            log.debug("Failed to read file stamp for {}: {}", path, e.getMessage());
        }
        return new FileStamp(Instant.EPOCH, -1L);
    }

    private ProjectConfig readProjectConfig() {
        if (!hasActiveProject()) {
            return ProjectConfig.DEFAULT;
        }
        Path configFile = resolveProjectDataPath(PROJECT_CONFIG_FILE);
        FileStamp stamp = readFileStamp(configFile);
        CachedProjectConfig currentCache = this.cachedProjectConfig;
        if (currentCache != null && currentCache.filePath().equals(configFile) && currentCache.stamp().equals(stamp)) {
            return currentCache.config();
        }
        ProjectConfig loaded = PathUtils.readTomlOrDefault(configFile, ProjectConfig.class, ProjectConfig.DEFAULT,
                AiConfig.SHARED_TOML_MAPPER);
        this.cachedProjectConfig = new CachedProjectConfig(configFile, stamp, loaded);
        return loaded;
    }

    private void writeProjectConfig(ProjectConfig config) {
        if (!hasActiveProject()) {
            throw new IllegalStateException(NO_PROJECT_ROOT_MSG);
        }
        Path configFile = resolveProjectDataPath(PROJECT_CONFIG_FILE);
        try {
            PathUtils.writeToml(configFile, config, AiConfig.SHARED_TOML_MAPPER);
            this.cachedProjectConfig = new CachedProjectConfig(configFile, readFileStamp(configFile), config);
        } catch (IOException e) {
            log.error("Failed to save project config {}: {}", configFile, e.getMessage());
            throw new UncheckedIOException("Failed to save project config", e);
        }
    }

    /**
     * Returns the Agent Profile ID configured for the current project, or {@code ""} if inheriting the global default.
     *
     * @return the project-specific Agent Profile ID, or empty string
     */
    public String getProjectAgentProfile() {
        return readProjectConfig().agentProfile();
    }

    /**
     * Sets the Agent Profile override for the current project (pass {@code ""} or null to inherit the global default).
     *
     * @param agentProfile
     *            the project Agent Profile ID or name
     */
    public void setProjectAgentProfile(String agentProfile) {
        if (!hasActiveProject()) {
            throw new IllegalStateException(NO_PROJECT_ROOT_MSG);
        }
        ProjectConfig existing = readProjectConfig();
        writeProjectConfig(new ProjectConfig(normalizeProjectAgentProfile(agentProfile), existing.buildConfirmed()));
        publishSettingsChangedSnapshot();
    }

    private void publishSettingsChangedSnapshot() {
        AppSettings currentSettings = null;
        if (settingsService != null && settingsService.getSettings() != null) {
            currentSettings = settingsService.getSettings().copy();
        }
        eventPublisher.publishEvent(new SettingsChangedEvent(currentSettings));
    }

    /**
     * Checks if the build configuration has been confirmed by the user.
     * 
     * @return true if confirmed
     */
    public boolean isBuildConfirmed() {
        return readProjectConfig().buildConfirmed();
    }

    /**
     * Sets whether the build configuration is confirmed.
     * 
     * @param confirmed
     *            true if confirmed
     */
    public void setBuildConfirmed(boolean confirmed) {
        if (!hasActiveProject()) {
            throw new IllegalStateException(NO_PROJECT_ROOT_MSG);
        }
        ProjectConfig existing = readProjectConfig();
        writeProjectConfig(new ProjectConfig(existing.agentProfile(), confirmed));
        publishSettingsChangedSnapshot();
    }

    /**
     * Resolves the effective {@link AgentProfile} for the current project, falling back to the global setting.
     *
     * @return the effective {@link AgentProfile}
     */
    public AgentProfile getEffectiveAgentProfile() {
        String projectProfile = getProjectAgentProfile();
        if (!projectProfile.isEmpty()) {
            return AgentProfile.fromString(projectProfile);
        }
        return SettingsService.resolveGlobalAgentProfile(settingsService);
    }

    /**
     * Resolves the effective Gemini model API name for the current project and given workflow phase.
     *
     * @param phase
     *            the workflow phase (may be null)
     * @return the resolved Gemini model API name
     */
    public String getEffectiveGeminiModel(WorkflowPhase phase) {
        return getEffectiveAgentProfile().resolveModelForPhase(phase);
    }

    /**
     * Resolves the effective {@link AgentProfile} from the given project and settings services.
     *
     * @param projectService
     *            the project service (may be null)
     * @param settingsService
     *            the settings service (may be null)
     * @return the effective {@link AgentProfile}
     */
    public static AgentProfile resolveEffectiveAgentProfile(ProjectService projectService,
            SettingsService settingsService) {
        if (projectService != null) {
            AgentProfile profile = projectService.getEffectiveAgentProfile();
            if (profile != null) {
                return profile;
            }
        }
        return SettingsService.resolveGlobalAgentProfile(settingsService);
    }

    public static String resolveNormalizedProjectPath(ProjectService projectService) {
        if (projectService == null || projectService.getCurrentProjectRoot() == null) {
            return "";
        }
        return projectService.getCurrentProjectRoot().toAbsolutePath().normalize().toString();
    }

    /**
     * Returns the convention-based global context directory ({@code ~/.roxycode/context}).
     *
     * @return the global context directory path, or {@code null} if settings are unavailable
     */
    public Path getGlobalContextDir() {
        if (settingsService == null) {
            return null;
        }
        Path homeDir = settingsService.getRoxyHomeDir();
        if (homeDir == null) {
            return null;
        }
        return homeDir.resolve(CONVENTION_CONTEXT_DIR).toAbsolutePath().normalize();
    }

    /**
     * Returns the convention-based project context directory ({@code <project>/context}).
     *
     * @return the project context directory path, or {@code null} if no project is active
     */
    public Path getProjectContextDir() {
        if (currentProjectRoot == null) {
            return null;
        }
        return currentProjectRoot.resolve(CONVENTION_CONTEXT_DIR).toAbsolutePath().normalize();
    }

    /**
     * Returns the convention-based global reference mounts directory ({@code ~/.roxycode/refs}).
     *
     * @return the global refs directory path, or {@code null} if settings are unavailable
     */
    public Path getGlobalRefsDir() {
        if (settingsService == null) {
            return null;
        }
        Path homeDir = settingsService.getRoxyHomeDir();
        if (homeDir == null) {
            return null;
        }
        return homeDir.resolve(GLOBAL_REFS_SUBDIR).toAbsolutePath().normalize();
    }

    /**
     * Returns the convention-based project reference mounts directory ({@code <project>/.roxy/refs}).
     *
     * @return the project refs directory path, or {@code null} if no project is active
     */
    public Path getProjectRefsDir() {
        if (currentProjectRoot == null) {
            return null;
        }
        return currentProjectRoot.resolve(PROJECT_REFS_REL_DIR).toAbsolutePath().normalize();
    }

    /**
     * Gets the list of discovered convention-based reference folder mounts.
     *
     * @return the list of reference folder mounts
     */
    public List<ReferenceFolderMount> getReferenceFolderMounts() {
        return resolveConventionReferenceMounts(getProjectRefsDir(), getGlobalRefsDir(), getGlobalContextDir(), true);
    }

    /**
     * Lists the global reference folder mounts (from {@code ~/.roxycode/refs/*} and {@code @global-context}).
     *
     * @return the list of global reference folder mounts
     */
    public List<ReferenceFolderMount> listGlobalReferenceFolderMounts() {
        List<ReferenceFolderMount> allMounts = resolveConventionReferenceMounts(getProjectRefsDir(), getGlobalRefsDir(),
                getGlobalContextDir(), false);
        return allMounts.stream().filter(ReferenceFolderMount::globalScope).toList();
    }

    /**
     * Lists the project reference folder mounts (from {@code <project>/.roxy/refs/*}).
     *
     * @return the list of project reference folder mounts
     */
    public List<ReferenceFolderMount> listProjectReferenceFolderMounts() {
        if (!hasActiveProject()) {
            return List.of();
        }
        return resolveConventionReferenceMounts(getProjectRefsDir(), null, null, true);
    }

    /**
     * Finds a reference folder mount by its alias.
     *
     * @param alias
     *            the alias of the reference folder mount
     * @return the reference folder mount, or null if not found
     */
    public ReferenceFolderMount findReferenceFolderMount(String alias) {
        String cleanAlias = StringUtils.removeStart(StringUtils.trimToEmpty(alias), "@");
        if (cleanAlias.isEmpty()) {
            return null;
        }
        for (ReferenceFolderMount mount : getReferenceFolderMounts()) {
            if (mount.alias().equalsIgnoreCase(cleanAlias)) {
                return mount;
            }
        }
        return null;
    }

    static List<ReferenceFolderMount> resolveConventionReferenceMounts(Path projectRefsDir, Path globalRefsDir,
            Path globalContextDir, boolean deduplicateAcrossScopes) {
        List<ReferenceFolderMount> mounts = new ArrayList<>();
        Set<String> usedAliases = new HashSet<>();
        Set<Path> seenRootPaths = new HashSet<>();

        appendConventionRefDirectoryMounts(projectRefsDir, false, mounts, usedAliases, seenRootPaths);
        if (!deduplicateAcrossScopes) {
            seenRootPaths.clear();
        }
        appendConventionRefDirectoryMounts(globalRefsDir, true, mounts, usedAliases, seenRootPaths);

        if (globalContextDir != null && Files.isDirectory(globalContextDir)) {
            Path targetRealPath = resolveRealDirectoryPath(globalContextDir);
            if (seenRootPaths.add(targetRealPath)) {
                String uniqueAlias = allocateUniqueAlias(GLOBAL_CONTEXT_ALIAS, usedAliases);
                mounts.add(new ReferenceFolderMount(uniqueAlias,
                        globalContextDir.toAbsolutePath().normalize().toString(), targetRealPath, true));
            }
        }
        return List.copyOf(mounts);
    }

    private static void appendConventionRefDirectoryMounts(Path refsDir, boolean globalScope,
            List<ReferenceFolderMount> targetMounts, Set<String> usedAliases, Set<Path> seenRootPaths) {
        if (refsDir == null || !Files.isDirectory(refsDir)) {
            return;
        }
        List<Path> entries;
        try (Stream<Path> stream = Files.list(refsDir)) {
            entries = stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to scan reference directory '{}': {}", refsDir, e.getMessage());
            return;
        }
        for (Path entry : entries) {
            Path targetRealPath = resolveRealDirectoryPath(entry);
            if (!seenRootPaths.add(targetRealPath)) {
                continue;
            }
            String baseAlias = deriveBaseAlias(entry);
            String uniqueAlias = allocateUniqueAlias(baseAlias, usedAliases);
            targetMounts
                    .add(new ReferenceFolderMount(uniqueAlias, targetRealPath.toString(), targetRealPath, globalScope));
        }
    }

    private static Path resolveRealDirectoryPath(Path dir) {
        try {
            return dir.toRealPath().normalize();
        } catch (IOException e) {
            return dir.toAbsolutePath().normalize();
        }
    }

    static String deriveBaseAlias(Path normalizedPath) {
        return sanitizePathFileName(normalizedPath, DEFAULT_REF_ALIAS);
    }

    private static String allocateUniqueAlias(String baseAlias, Set<String> usedAliases) {
        if (usedAliases.add(baseAlias)) {
            return baseAlias;
        }
        int suffix = 2;
        while (true) {
            String candidate = baseAlias + "-" + suffix;
            if (usedAliases.add(candidate)) {
                return candidate;
            }
            suffix++;
        }
    }

    /**
     * Checks if a path is a reference path (starts with '@').
     *
     * @param relativePath
     *            the relative path to check
     * @return true if the path is a reference path, false otherwise
     */
    public static boolean isReferencePath(String relativePath) {
        if (relativePath == null) {
            return false;
        }
        String stripped = PathUtils.stripLeadingCurrentDir(relativePath);
        return stripped.startsWith("@");
    }

    /**
     * Strips the reference alias prefix from a path.
     *
     * @param normalizedPath
     *            the normalized path
     * @return the path without the reference alias prefix
     */
    public static String stripReferenceAliasPrefix(String normalizedPath) {
        if (!isReferencePath(normalizedPath)) {
            return normalizedPath;
        }
        String stripped = PathUtils.stripLeadingCurrentDir(normalizedPath);
        int slashIdx = stripped.indexOf('/');
        if (slashIdx < 0) {
            return "";
        }
        return stripped.substring(slashIdx + 1);
    }

    /**
     * Extracts the reference alias prefix from a path.
     *
     * @param normalizedPath
     *            the normalized path
     * @return the reference alias prefix
     */
    public static String extractReferenceAliasPrefix(String normalizedPath) {
        if (!isReferencePath(normalizedPath)) {
            return "";
        }
        String stripped = PathUtils.stripLeadingCurrentDir(normalizedPath);
        int slashIdx = stripped.indexOf('/');
        if (slashIdx < 0) {
            return stripped;
        }
        return stripped.substring(0, slashIdx);
    }

    private ParsedReferencePath parseReferencePath(String relativePath) {
        String stripped = PathUtils.stripLeadingCurrentDir(relativePath);
        if (!stripped.startsWith("@")) {
            throw new IllegalArgumentException("Not a reference folder path: " + relativePath);
        }
        String withoutAt = stripped.substring(1);
        int slashIdx = withoutAt.indexOf('/');
        String alias;
        String subPath;
        if (slashIdx < 0) {
            alias = withoutAt;
            subPath = ".";
        } else {
            alias = withoutAt.substring(0, slashIdx);
            subPath = StringUtils.defaultIfBlank(withoutAt.substring(slashIdx + 1), ".");
        }
        if (alias.isBlank() || ".".equals(alias) || "..".equals(alias)) {
            throw new IllegalArgumentException("Invalid reference folder path: " + relativePath);
        }
        ReferenceFolderMount mount = findReferenceFolderMount(alias);
        if (mount == null) {
            throw new IllegalArgumentException(
                    "Unknown reference folder alias '@" + alias + "' in path: " + relativePath);
        }
        return new ParsedReferencePath(mount, subPath);
    }

    /**
     * Resolves a path relative to the project root.
     *
     * @param relativePath
     *            the relative path to resolve
     * @return the resolved path
     */
    public Path resolvePath(String relativePath) {
        if (isReferencePath(relativePath)) {
            throw new SecurityException("Reference folder path is strictly read-only: " + relativePath);
        }
        Path projectRoot = getCurrentProjectRoot();
        if (projectRoot == null) {
            throw new IllegalStateException(NO_PROJECT_ROOT_MSG);
        }
        if (relativePath != null) {
            String unix = PathUtils.stripLeadingCurrentDir(relativePath);
            Path virtualTmp = resolveVirtualTmpPath(unix);
            if (virtualTmp != null) {
                return virtualTmp;
            }
        }
        Path resolved = resolveWithinSandbox(projectRoot, relativePath);
        Path refsRoot = projectRoot.resolve(PROJECT_REFS_REL_DIR).toAbsolutePath().normalize();
        if (resolved.equals(refsRoot) || resolved.startsWith(refsRoot)) {
            throw new SecurityException("Reference folder directory is strictly read-only: " + relativePath);
        }
        return resolved;
    }

    private Path resolveVirtualTmpPath(String unixPath) {
        if (unixPath == null) {
            return null;
        }
        if (ROXYCODE_TMP_DIR.equals(unixPath) || unixPath.startsWith(ROXYCODE_TMP_PREFIX)) {
            Path tmpRoot = resolveProjectDataPath(DATA_TMP_DIR);
            String subPath = StringUtils.defaultIfEmpty(StringUtils.removeStart(unixPath, ROXYCODE_TMP_PREFIX), ".");
            if (ROXYCODE_TMP_DIR.equals(unixPath)) {
                subPath = ".";
            }
            return resolveWithinSandbox(tmpRoot, subPath);
        }
        return null;
    }

    /**
     * Resolves a read-only path relative to the project root or a mounted reference folder.
     *
     * @param relativePath
     *            the relative path to resolve
     * @return the resolved path
     */
    public Path resolveReadPath(String relativePath) {
        if (isReferencePath(relativePath)) {
            ParsedReferencePath parsed = parseReferencePath(relativePath);
            return resolveWithinSandbox(parsed.mount().rootPath(), parsed.subPath());
        }
        return resolvePath(relativePath);
    }

    /**
     * Resolves a search target path.
     *
     * @param relativePath
     *            the relative path to resolve
     * @return the resolved search target
     */
    public ResolvedSearchTarget resolveSearchTarget(String relativePath) {
        if (isReferencePath(relativePath)) {
            ParsedReferencePath parsed = parseReferencePath(relativePath);
            Path root = parsed.mount().rootPath();
            Path resolved = resolveWithinSandbox(root, parsed.subPath());
            String relTarget = StringUtils
                    .defaultIfEmpty(PathUtils.normalizeRelativePath(root.relativize(resolved).toString()), ".");
            return new ResolvedSearchTarget(root, relTarget, parsed.mount().aliasPrefix());
        }
        Path projectRoot = getCurrentProjectRoot();
        if (projectRoot == null) {
            throw new IllegalStateException(NO_PROJECT_ROOT_MSG);
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        String relTarget = resolveRelativePath(relativePath);
        return new ResolvedSearchTarget(root, relTarget, "");
    }

    /**
     * Formats a path for display, falling back to the relative path if necessary.
     *
     * @param resolvedPath
     *            the resolved path
     * @param fallbackRelativePath
     *            the fallback relative path
     * @return the formatted display path
     */
    public String formatReadDisplayPath(Path resolvedPath, String fallbackRelativePath) {
        if (resolvedPath != null) {
            Path normalizedTarget = resolvedPath.toAbsolutePath().normalize();
            List<ReferenceFolderMount> mounts = Objects.requireNonNullElse(getReferenceFolderMounts(), List.of());
            for (ReferenceFolderMount mount : mounts) {
                if (mount != null && mount.rootPath() != null && normalizedTarget.startsWith(mount.rootPath())) {
                    String rel = PathUtils
                            .normalizeRelativePath(mount.rootPath().relativize(normalizedTarget).toString());
                    if (rel.isEmpty()) {
                        return mount.aliasPrefix();
                    }
                    return mount.aliasPrefix() + "/" + rel;
                }
            }
            Path root = getCurrentProjectRoot();
            if (root != null) {
                Path normalizedRoot = root.toAbsolutePath().normalize();
                if (normalizedTarget.startsWith(normalizedRoot)) {
                    String rel = PathUtils
                            .normalizeRelativePath(normalizedRoot.relativize(normalizedTarget).toString());
                    if (!rel.isEmpty()) {
                        return rel;
                    }
                }
            }
        }
        return PathUtils.normalizeRelativePath(StringUtils.trimToEmpty(fallbackRelativePath));
    }

    /**
     * Resolves a relative path to a normalized relative string.
     *
     * @param relativePath
     *            the relative path to resolve
     * @return the normalized relative path
     */
    public String resolveRelativePath(String relativePath) {
        if (isReferencePath(relativePath)) {
            return resolveSearchTarget(relativePath).relativeTarget();
        }
        Path projectRoot = getCurrentProjectRoot();
        if (projectRoot == null) {
            throw new IllegalStateException(NO_PROJECT_ROOT_MSG);
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        String target = StringUtils.defaultIfBlank(relativePath, ".").trim();
        Path resolved = resolveWithinSandbox(root, target);
        return StringUtils.defaultIfEmpty(PathUtils.normalizeRelativePath(root.relativize(resolved).toString()), ".");
    }

    /**
     * Gets the project data directory path.
     *
     * @return the project data directory path
     */
    public Path getProjectDataDir() {
        Path projectRoot = getCurrentProjectRoot();
        if (projectRoot == null) {
            throw new IllegalStateException(NO_PROJECT_ROOT_MSG);
        }
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        String dirName = sanitizeProjectName(normalizedRoot) + "-"
                + PathUtils.computeSha256Hex(normalizedRoot.toString(), 4);
        Path roxyHome = Objects.requireNonNullElseGet(settingsService.getRoxyHomeDir(),
                SettingsService::getDefaultRoxyHomeDir);
        return roxyHome.toAbsolutePath().normalize().resolve(PROJECTS_SUBDIR).resolve(dirName);
    }

    /**
     * Resolves a path relative to the project data directory.
     *
     * @param relativePath
     *            the relative path to resolve
     * @return the resolved path
     */
    public Path resolveProjectDataPath(String relativePath) {
        return resolveWithinSandbox(getProjectDataDir(), relativePath);
    }

    /**
     * Resolves a path relative to the active project's data directory, returning {@code null} if {@code projectService}
     * is null, has no active project, or resolution fails.
     *
     * @param projectService
     *            the project service instance (may be null)
     * @param relativePath
     *            the relative path within the project data directory
     * @return the resolved normalized path, or {@code null}
     */
    public static Path resolveProjectDataPathSafely(ProjectService projectService, String relativePath) {
        if (projectService == null || !projectService.hasActiveProject()) {
            return null;
        }
        try {
            Path resolved = projectService.resolveProjectDataPath(relativePath);
            if (resolved == null) {
                return null;
            }
            return resolved.toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static Path resolveWithinSandbox(Path sandboxRoot, String relativePath) {
        Path root = sandboxRoot.toAbsolutePath().normalize();
        Path resolved = root.resolve(Objects.requireNonNullElse(relativePath, ".")).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Path traversal attempt detected: " + relativePath);
        }
        return resolved;
    }

    /**
     * Sanitizes a project name for use in file paths.
     *
     * @param projectRoot
     *            the project root path
     * @return the sanitized project name
     */
    public static String sanitizeProjectName(Path projectRoot) {
        return sanitizePathFileName(projectRoot, "project");
    }

    private static String sanitizePathFileName(Path path, String fallback) {
        String rawName = "";
        if (path != null && path.getFileName() != null) {
            rawName = path.getFileName().toString();
        }
        return PathUtils.sanitizeSlug(rawName, MAX_PROJECT_NAME_LENGTH, fallback);
    }
}
