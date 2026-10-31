package org.roxycode.app.model;

import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

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
        name = StringUtils.defaultString(name);
        version = StringUtils.defaultString(version);
        status = StringUtils.defaultString(status);
        ToolMetadata meta = KNOWN_METADATA.get(name);
        String defaultDesc = "";
        String defaultLicense = "";
        String defaultUrl = "";
        boolean defaultReq = false;
        if (meta != null) {
            defaultDesc = meta.description();
            defaultLicense = meta.license();
            defaultUrl = meta.websiteUrl();
            defaultReq = meta.required();
        }
        description = StringUtils.defaultIfBlank(description, defaultDesc);
        license = StringUtils.defaultIfBlank(license, defaultLicense);
        websiteUrl = StringUtils.defaultIfBlank(websiteUrl, defaultUrl);
        required = required || defaultReq;
    }

    public static SystemTool ofKnownTool(String name, String version, String status) {
        return new SystemTool(name, version, status, null, null, null, false);
    }
}
