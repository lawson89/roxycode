package org.roxycode.app.model;

import java.util.Map;
import java.util.Set;

public record SystemTool(
        String name,
        String version,
        String status,
        String description,
        String license,
        String websiteUrl) {

    public static final String TOOL_RIPGREP = "Ripgrep";
    public static final String TOOL_GIT = "Git";
    public static final String TOOL_CTAGS = "Universal Ctags";
    public static final String TOOL_JAVA = "Java";
    public static final String TOOL_DOCKER = "Docker";

    public record ToolMetadata(String description, String license, String websiteUrl) {}

    private static final Map<String, ToolMetadata> KNOWN_METADATA =
            Map.of(
                    TOOL_RIPGREP,
                    new ToolMetadata(
                            "Powers GrepService and MemoryService for fast regex, literal, and file-listing searches across the workspace while respecting .gitignore.",
                            "Unlicense / MIT",
                            "https://github.com/BurntSushi/ripgrep"),
                    TOOL_GIT,
                    new ToolMetadata(
                            "Powers GitService for branch detection, porcelain status, working-tree and HEAD diffs, commit history, and line-level blame annotations.",
                            "GPL-2.0",
                            "https://git-scm.com"),
                    TOOL_CTAGS,
                    new ToolMetadata(
                            "Powers RepoMapPackerService to extract class, method, and symbol definitions across languages when building the repository map snapshot.",
                            "GPL-2.0",
                            "https://ctags.io"),
                    TOOL_JAVA,
                    new ToolMetadata(
                            "Provides the JVM runtime environment executing RoxyCode and local Maven/Gradle JVM builds.",
                            "GPL-2.0 with Classpath Exception",
                            "https://adoptium.net"),
                    TOOL_DOCKER,
                    new ToolMetadata(
                            "Powers BuildContainerService via Testcontainers for isolated, reproducible container-based build and test execution.",
                            "Apache-2.0",
                            "https://www.docker.com"));

    private static final Set<String> LEGACY_DESCRIPTIONS =
            Set.of(
                    "Required for fast file searching.",
                    "Required for version control integration.",
                    "Required for code navigation and symbol extraction.",
                    "The runtime environment for this application.",
                    "Required for isolated build and test execution via Testcontainers.");

    public SystemTool {
        name = name != null ? name : "";
        version = version != null ? version : "";
        status = status != null ? status : "";
        description =
                (description != null && !description.isBlank())
                        ? description
                        : defaultDescriptionFor(name);
        license = (license != null && !license.isBlank()) ? license : defaultLicenseFor(name);
        websiteUrl =
                (websiteUrl != null && !websiteUrl.isBlank())
                        ? websiteUrl
                        : defaultWebsiteUrlFor(name);
    }

    public SystemTool(String name, String version, String status, String description) {
        this(
                name,
                version,
                status,
                description,
                defaultLicenseFor(name),
                defaultWebsiteUrlFor(name));
    }

    public static SystemTool ofKnownTool(String name, String version, String status) {
        return new SystemTool(
                name,
                version,
                status,
                defaultDescriptionFor(name),
                defaultLicenseFor(name),
                defaultWebsiteUrlFor(name));
    }

    public SystemTool withVersion(String newVersion) {
        return new SystemTool(name, newVersion, status, description, license, websiteUrl);
    }

    public SystemTool enrichLegacyMetadata() {
        return new SystemTool(
                name,
                version,
                status,
                LEGACY_DESCRIPTIONS.contains(description)
                        ? defaultDescriptionFor(name)
                        : description,
                license,
                websiteUrl);
    }

    private static ToolMetadata metadataFor(String toolName) {
        return toolName != null ? KNOWN_METADATA.get(toolName) : null;
    }

    public static String defaultDescriptionFor(String toolName) {
        ToolMetadata meta = metadataFor(toolName);
        return meta != null ? meta.description() : "";
    }

    public static String defaultLicenseFor(String toolName) {
        ToolMetadata meta = metadataFor(toolName);
        return meta != null ? meta.license() : "";
    }

    public static String defaultWebsiteUrlFor(String toolName) {
        ToolMetadata meta = metadataFor(toolName);
        return meta != null ? meta.websiteUrl() : "";
    }
}
