package org.roxycode.app.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.events.ContextChangedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ThinkingLevel;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
/**
 * Manages project configuration, data directories, and reference folder mounts.
 */
public class ProjectService {
    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);
    private static final String PROJECTS_SUBDIR = "projects";
    private static final int MAX_PROJECT_NAME_LENGTH = 40;
    private static final String DEFAULT_REF_ALIAS = "ref";
    private static final String CONTEXT_SEPARATOR = "--";
    private static final Pattern RG_LINE_PREFIX_PATTERN = Pattern.compile("^(.+?)([:-][0-9]+[:-].*)$", Pattern.DOTALL);
    static final String PROJECT_CONFIG_FILE = "project.toml";
    static final String PROJECT_CONTEXT_CONFIG_FILE = "context.toml";
    static final String ROXYCODE_TMP_DIR = ".roxycode/tmp";
    private static final String ROXYCODE_TMP_PREFIX = ".roxycode/tmp/";
    private static final String DATA_TMP_DIR = "tmp";
    private static final String NO_PROJECT_ROOT_MSG = "Project root is not set.";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProjectConfig(String geminiModel, String thinkingLevel, boolean buildConfirmed) {
        public static final ProjectConfig DEFAULT = new ProjectConfig("", "", false);

        public ProjectConfig {
            geminiModel = StringUtils.trimToEmpty(geminiModel);
            thinkingLevel = normalizeProjectThinkingLevel(thinkingLevel);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProjectContextConfig(List<String> folders, List<String> referenceFolders) {
        public static final ProjectContextConfig EMPTY = new ProjectContextConfig(List.of(), List.of());

        public ProjectContextConfig {
            folders = List.copyOf(Objects.requireNonNullElse(folders, List.of()));
            referenceFolders = List.copyOf(Objects.requireNonNullElse(referenceFolders, List.of()));
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

    private static String normalizeProjectThinkingLevel(String rawThinkingLevel) {
        if (StringUtils.isBlank(rawThinkingLevel)) {
            return "";
        }
        return ThinkingLevel.fromString(rawThinkingLevel).getId();
    }

    private final SettingsService settingsService;
    private final ApplicationEventPublisher eventPublisher;
    private volatile Path currentProjectRoot;

    public ProjectService(SettingsService settingsService, ApplicationEventPublisher eventPublisher) {
        this.settingsService = settingsService;
        this.eventPublisher = Objects.requireNonNullElse(eventPublisher, event -> {
        });
        String savedPath = settingsService.getSettings().getCurrentProjectPath();
        if (StringUtils.isNotEmpty(savedPath)) {
            this.currentProjectRoot = Paths.get(savedPath);
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
            settingsService.saveSettings();
            eventPublisher.publishEvent(new ProjectChangedEvent(newRoot));
        }
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

    private ProjectConfig readProjectConfig() {
        if (!hasActiveProject()) {
            return ProjectConfig.DEFAULT;
        }
        return PathUtils.readTomlOrDefault(resolveProjectDataPath(PROJECT_CONFIG_FILE), ProjectConfig.class,
                ProjectConfig.DEFAULT, AiConfig.SHARED_TOML_MAPPER);
    }

    private void writeProjectConfig(ProjectConfig config) {
        if (!hasActiveProject()) {
            throw new IllegalStateException(NO_PROJECT_ROOT_MSG);
        }
        Path configFile = resolveProjectDataPath(PROJECT_CONFIG_FILE);
        try {
            PathUtils.writeToml(configFile, config, AiConfig.SHARED_TOML_MAPPER);
        } catch (IOException e) {
            log.error("Failed to save project config {}: {}", configFile, e.getMessage());
            throw new UncheckedIOException("Failed to save project config", e);
        }
    }

    /**
     * Returns the Gemini model configured for the current project.
     * 
     * @return the project-specific model name
     */
    public String getProjectGeminiModel() {
        return readProjectConfig().geminiModel();
    }

    /**
     * Sets the Gemini model for the current project.
     * 
     * @param geminiModel
     *            the model name
     */
    public void setProjectGeminiModel(String geminiModel) {
        if (!hasActiveProject()) {
            throw new IllegalStateException(NO_PROJECT_ROOT_MSG);
        }
        ProjectConfig existing = readProjectConfig();
        writeProjectConfig(new ProjectConfig(StringUtils.trimToEmpty(geminiModel), existing.thinkingLevel(),
                existing.buildConfirmed()));
        publishSettingsChangedSnapshot();
    }

    /**
     * Returns the thinking level configured for the current project.
     * 
     * @return the project-specific thinking level
     */
    public String getProjectThinkingLevel() {
        return readProjectConfig().thinkingLevel();
    }

    /**
     * Sets the thinking level for the current project.
     * 
     * @param thinkingLevel
     *            the thinking level
     */
    public void setProjectThinkingLevel(String thinkingLevel) {
        if (!hasActiveProject()) {
            throw new IllegalStateException(NO_PROJECT_ROOT_MSG);
        }
        ProjectConfig existing = readProjectConfig();
        writeProjectConfig(new ProjectConfig(existing.geminiModel(), normalizeProjectThinkingLevel(thinkingLevel),
                existing.buildConfirmed()));
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
        writeProjectConfig(new ProjectConfig(existing.geminiModel(), existing.thinkingLevel(), confirmed));
    }

    public String getEffectiveGeminiModel() {
        String projectModel = getProjectGeminiModel();
        if (!projectModel.isEmpty()) {
            return projectModel;
        }
        return SettingsService.resolveGlobalGeminiModel(settingsService);
    }

    public ThinkingLevel getEffectiveThinkingLevel() {
        String projectThinking = getProjectThinkingLevel();
        if (!projectThinking.isEmpty()) {
            return ThinkingLevel.fromString(projectThinking);
        }
        return SettingsService.resolveGlobalThinkingLevel(settingsService);
    }

    public static String resolveEffectiveGeminiModel(ProjectService projectService, SettingsService settingsService) {
        if (projectService != null) {
            return projectService.getEffectiveGeminiModel();
        }
        return SettingsService.resolveGlobalGeminiModel(settingsService);
    }

    public static ThinkingLevel resolveEffectiveThinkingLevel(ProjectService projectService,
            SettingsService settingsService) {
        if (projectService != null) {
            ThinkingLevel projectLevel = projectService.getEffectiveThinkingLevel();
            if (projectLevel != null) {
                return projectLevel;
            }
        }
        return SettingsService.resolveGlobalThinkingLevel(settingsService);
    }

    public static String resolveNormalizedProjectPath(ProjectService projectService) {
        if (projectService == null || projectService.getCurrentProjectRoot() == null) {
            return "";
        }
        return projectService.getCurrentProjectRoot().toAbsolutePath().normalize().toString();
    }

    static List<String> normalizeFolderList(List<String> folders) {
        if (folders == null) {
            return List.of();
        }
        return folders.stream().filter(f -> f != null && !f.isBlank()).map(String::trim).distinct().toList();
    }

    static List<String> getGlobalFolderList(SettingsService settingsService,
            Function<AppSettings, List<String>> getter) {
        if (settingsService == null || settingsService.getSettings() == null) {
            return List.of();
        }
        return normalizeFolderList(getter.apply(settingsService.getSettings()));
    }

    static void setGlobalFolderList(SettingsService settingsService, ApplicationEventPublisher eventPublisher,
            List<String> folders, BiConsumer<AppSettings, List<String>> setter) {
        if (settingsService == null) {
            return;
        }
        AppSettings settings = settingsService.getSettings();
        if (settings != null) {
            setter.accept(settings, normalizeFolderList(folders));
            settingsService.saveSettings();
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new ContextChangedEvent(true));
            }
        }
    }

    private ProjectContextConfig readProjectContextConfig() {
        if (!hasActiveProject()) {
            return ProjectContextConfig.EMPTY;
        }
        return PathUtils.readTomlOrDefault(resolveProjectDataPath(PROJECT_CONTEXT_CONFIG_FILE),
                ProjectContextConfig.class, ProjectContextConfig.EMPTY, AiConfig.SHARED_TOML_MAPPER);
    }

    private void writeProjectContextConfig(ProjectContextConfig config) {
        if (!hasActiveProject()) {
            throw new IllegalStateException("No active project");
        }
        Path configFile = resolveProjectDataPath(PROJECT_CONTEXT_CONFIG_FILE);
        try {
            PathUtils.writeToml(configFile, config, AiConfig.SHARED_TOML_MAPPER);
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new ContextChangedEvent(false));
            }
        } catch (IOException e) {
            log.error("Failed to save project context config {}: {}", configFile, e.getMessage());
            throw new UncheckedIOException("Failed to save project context config", e);
        }
    }

    public List<String> getGlobalContextFolders() {
        return getGlobalFolderList(settingsService, AppSettings::getGlobalContextFolders);
    }

    public void setGlobalContextFolders(List<String> folders) {
        setGlobalFolderList(settingsService, eventPublisher, folders, AppSettings::setGlobalContextFolders);
    }

    public List<String> getProjectContextFolders() {
        return normalizeFolderList(readProjectContextConfig().folders());
    }

    public void setProjectContextFolders(List<String> folders) {
        ProjectContextConfig existing = readProjectContextConfig();
        writeProjectContextConfig(new ProjectContextConfig(normalizeFolderList(folders), existing.referenceFolders()));
    }

    public List<String> getGlobalReferenceFolders() {
        return getGlobalFolderList(settingsService, AppSettings::getGlobalReferenceFolders);
    }

    public void setGlobalReferenceFolders(List<String> folders) {
        setGlobalFolderList(settingsService, eventPublisher, folders, AppSettings::setGlobalReferenceFolders);
    }

    public List<String> getProjectReferenceFolders() {
        return normalizeFolderList(readProjectContextConfig().referenceFolders());
    }

    public void setProjectReferenceFolders(List<String> folders) {
        ProjectContextConfig existing = readProjectContextConfig();
        writeProjectContextConfig(new ProjectContextConfig(existing.folders(), normalizeFolderList(folders)));
    }

    public List<ReferenceFolderMount> getReferenceFolderMounts() {
        return resolveReferenceFolderMounts(getProjectReferenceFolders(), getGlobalReferenceFolders(),
                getCurrentProjectRoot());
    }

    public List<ReferenceFolderMount> listGlobalReferenceFolderMounts() {
        Path activeRoot = null;
        if (hasActiveProject()) {
            activeRoot = getCurrentProjectRoot();
        }
        List<ReferenceFolderMount> allMounts = resolveReferenceFolderMountsInternal(getProjectReferenceFolders(),
                getGlobalReferenceFolders(), activeRoot, false);
        return allMounts.stream().filter(ReferenceFolderMount::globalScope).toList();
    }

    public List<ReferenceFolderMount> listProjectReferenceFolderMounts() {
        if (!hasActiveProject()) {
            return List.of();
        }
        return resolveReferenceFolderMounts(getProjectReferenceFolders(), List.of(), getCurrentProjectRoot());
    }

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

    static List<ReferenceFolderMount> resolveReferenceFolderMounts(List<String> projectFolders,
            List<String> globalFolders, Path projectRoot) {
        return resolveReferenceFolderMountsInternal(projectFolders, globalFolders, projectRoot, true);
    }

    static List<ReferenceFolderMount> resolveReferenceFolderMountsInternal(List<String> projectFolders,
            List<String> globalFolders, Path projectRoot, boolean deduplicateAcrossScopes) {
        List<ReferenceFolderMount> mounts = new ArrayList<>();
        Set<String> usedAliases = new HashSet<>();
        Set<Path> seenRootPaths = new HashSet<>();

        appendScopeMounts(projectFolders, false, projectRoot, mounts, usedAliases, seenRootPaths, true);
        if (!deduplicateAcrossScopes) {
            seenRootPaths.clear();
        }
        appendScopeMounts(globalFolders, true, projectRoot, mounts, usedAliases, seenRootPaths, true);
        return List.copyOf(mounts);
    }

    private static void appendScopeMounts(List<String> rawFolders, boolean globalScope, Path projectRoot,
            List<ReferenceFolderMount> targetMounts, Set<String> usedAliases, Set<Path> seenRootPaths,
            boolean deduplicatePaths) {
        if (rawFolders == null || rawFolders.isEmpty()) {
            return;
        }
        for (String rawFolder : rawFolders) {
            if (rawFolder == null || rawFolder.isBlank()) {
                continue;
            }
            String configured = rawFolder.trim();
            Path resolved = resolveMountFolderPath(configured, !globalScope, projectRoot);
            if (resolved == null) {
                continue;
            }
            if (deduplicatePaths && !seenRootPaths.add(resolved)) {
                continue;
            }
            String baseAlias = deriveBaseAlias(resolved);
            String uniqueAlias = allocateUniqueAlias(baseAlias, usedAliases);
            targetMounts.add(new ReferenceFolderMount(uniqueAlias, configured, resolved, globalScope));
        }
    }

    static Path resolveMountFolderPath(String folderStr, boolean resolveRelativeToProject, Path projectRoot) {
        try {
            Path path = Path.of(folderStr);
            if (!path.isAbsolute()) {
                if (!resolveRelativeToProject || projectRoot == null) {
                    return null;
                }
                path = projectRoot.resolve(path);
            }
            return path.toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            log.warn("Invalid reference folder path '{}': {}", folderStr, e.getMessage());
            return null;
        }
    }

    static String deriveBaseAlias(Path normalizedPath) {
        String rawName = "";
        if (normalizedPath != null && normalizedPath.getFileName() != null) {
            rawName = normalizedPath.getFileName().toString();
        }
        return PathUtils.sanitizeSlug(rawName, MAX_PROJECT_NAME_LENGTH, DEFAULT_REF_ALIAS);
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

    public static boolean isReferencePath(String relativePath) {
        if (relativePath == null) {
            return false;
        }
        String stripped = PathUtils.stripLeadingCurrentDir(relativePath);
        return stripped.startsWith("@");
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
            if (ROXYCODE_TMP_DIR.equals(unix) || unix.startsWith(ROXYCODE_TMP_PREFIX)) {
                Path tmpRoot = resolveProjectDataPath(DATA_TMP_DIR);
                String subPath = StringUtils.defaultIfEmpty(StringUtils.removeStart(unix, ROXYCODE_TMP_PREFIX), ".");
                if (ROXYCODE_TMP_DIR.equals(unix)) {
                    subPath = ".";
                }
                return resolveWithinSandbox(tmpRoot, subPath);
            }
        }
        return resolveWithinSandbox(projectRoot, relativePath);
    }

    public Path resolveReadPath(String relativePath) {
        if (isReferencePath(relativePath)) {
            ParsedReferencePath parsed = parseReferencePath(relativePath);
            return resolveWithinSandbox(parsed.mount().rootPath(), parsed.subPath());
        }
        return resolvePath(relativePath);
    }

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

    public Path getProjectDataDir() {
        Path projectRoot = getCurrentProjectRoot();
        if (projectRoot == null) {
            throw new IllegalStateException(NO_PROJECT_ROOT_MSG);
        }
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        String dirName = sanitizeProjectName(normalizedRoot) + "-" + computeProjectHash(normalizedRoot);
        Path roxyHome = Objects.requireNonNullElseGet(settingsService.getRoxyHomeDir(),
                SettingsService::getDefaultRoxyHomeDir);
        return roxyHome.toAbsolutePath().normalize().resolve(PROJECTS_SUBDIR).resolve(dirName);
    }

    public Path resolveProjectDataPath(String relativePath) {
        return resolveWithinSandbox(getProjectDataDir(), relativePath);
    }

    private static Path resolveWithinSandbox(Path sandboxRoot, String relativePath) {
        Path root = sandboxRoot.toAbsolutePath().normalize();
        Path resolved = root.resolve(Objects.requireNonNullElse(relativePath, ".")).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Path traversal attempt detected: " + relativePath);
        }
        return resolved;
    }

    public static String sanitizeProjectName(Path projectRoot) {
        String rawName = "";
        if (projectRoot != null && projectRoot.getFileName() != null) {
            rawName = projectRoot.getFileName().toString();
        }
        return PathUtils.sanitizeSlug(rawName, MAX_PROJECT_NAME_LENGTH, "project");
    }

    static String computeProjectHash(Path normalizedRoot) {
        return PathUtils.computeSha256Hex(normalizedRoot.toString(), 4);
    }
}
