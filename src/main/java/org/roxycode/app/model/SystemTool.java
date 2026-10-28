package org.roxycode.app.model;

import java.util.List;
import java.util.Map;

public record SystemTool(String name, String version, String status, String description, String license,
        String websiteUrl, boolean required) {

    public static final String TOOL_RIPGREP = "Ripgrep";
    public static final String TOOL_GIT = "Git";
    public static final String TOOL_CTAGS = "Universal Ctags";
    public static final String TOOL_DOCKER = "Docker";

    public static final List<String> REQUIRED_TOOL_NAMES = List.of(TOOL_RIPGREP, TOOL_GIT, TOOL_CTAGS);

    public record ToolMetadata(String description, String license, String websiteUrl, boolean required) {
    }

    private static final Map<String, ToolMetadata> KNOWN_METADATA = Map.of(TOOL_RIPGREP, new ToolMetadata(
            "Powers GrepService and MemoryService for fast regex, literal, and file-listing searches across the workspace while respecting .gitignore.",
            "Unlicense / MIT", "https://github.com/BurntSushi/ripgrep", true), TOOL_GIT,
            new ToolMetadata(
                    "Powers GitService for branch detection, porcelain status, working-tree and HEAD diffs, commit history, and line-level blame annotations.",
                    "GPL-2.0", "https://git-scm.com", true),
            TOOL_CTAGS,
            new ToolMetadata(
                    "Powers RepoMapPackerService to extract class, method, and symbol definitions across languages when building the repository map snapshot.",
                    "GPL-2.0", "https://ctags.io", true),
            TOOL_DOCKER,
            new ToolMetadata(
                    "Powers BuildContainerService via Testcontainers for isolated, reproducible container-based build and test execution.",
                    "Apache-2.0", "https://www.docker.com", false));

    public SystemTool {
        name = name != null ? name : "";
        version = version != null ? version : "";
        status = status != null ? status : "";
        description = (description != null && !description.isBlank()) ? description : defaultDescriptionFor(name);
        license = (license != null && !license.isBlank()) ? license : defaultLicenseFor(name);
        websiteUrl = (websiteUrl != null && !websiteUrl.isBlank()) ? websiteUrl : defaultWebsiteUrlFor(name);
        required = required || defaultRequiredFor(name);
    }

    public SystemTool(String name, String version, String status, String description) {
        this(name, version, status, description, defaultLicenseFor(name), defaultWebsiteUrlFor(name),
                defaultRequiredFor(name));
    }

    public SystemTool(String name, String version, String status, String description, String license,
            String websiteUrl) {
        this(name, version, status, description, license, websiteUrl, defaultRequiredFor(name));
    }

    public static SystemTool ofKnownTool(String name, String version, String status) {
        return new SystemTool(name, version, status, defaultDescriptionFor(name), defaultLicenseFor(name),
                defaultWebsiteUrlFor(name), defaultRequiredFor(name));
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

    public static boolean defaultRequiredFor(String toolName) {
        ToolMetadata meta = metadataFor(toolName);
        return meta != null && meta.required();
    }
}
