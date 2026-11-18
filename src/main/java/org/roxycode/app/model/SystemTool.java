package org.roxycode.app.model;

import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

/** Represents a system tool required or used by RoxyCode (e.g., Ripgrep, Git, Docker). */
public record SystemTool(String name, String version, String status, String description, String license,
        String websiteUrl, boolean required) {

    public static final String TOOL_RIPGREP = "Ripgrep";
    public static final String TOOL_GIT = "Git";
    public static final String TOOL_CTAGS = "Universal Ctags";
    public static final String TOOL_DOCKER = "Docker";
    public static final String TOOL_BUBBLEWRAP = "Bubblewrap";
    public static final String TOOL_SEATBELT = "Seatbelt (sandbox-exec)";

    public static final List<String> REQUIRED_TOOL_NAMES = List.of(TOOL_RIPGREP, TOOL_CTAGS);

    private record ToolMetadata(String description, String license, String websiteUrl, boolean required) {
    }

    private static final ToolMetadata EMPTY_METADATA = new ToolMetadata("", "", "", false);

    private static final Map<String, ToolMetadata> KNOWN_METADATA = Map.of(TOOL_RIPGREP, new ToolMetadata(
            "Powers GrepService and MemoryService for fast regex, literal, and file-listing searches across the workspace while respecting .gitignore.",
            "Unlicense / MIT", "https://github.com/BurntSushi/ripgrep", true), TOOL_GIT,
            new ToolMetadata(
                    "Powers GitService for branch detection, porcelain status, working-tree and HEAD diffs, commit history, and line-level blame annotations.",
                    "GPL-2.0", "https://git-scm.com", false),
            TOOL_CTAGS,
            new ToolMetadata(
                    "Powers RepoMapPackerService to extract class, method, and symbol definitions across languages when building the repository map snapshot.",
                    "GPL-2.0", "https://ctags.io", true),
            TOOL_DOCKER,
            new ToolMetadata(
                    "Powers BuildContainerService via Testcontainers for isolated, reproducible container-based build and test execution.",
                    "Apache-2.0", "https://www.docker.com", false),
            TOOL_BUBBLEWRAP,
            new ToolMetadata("Unprivileged user-namespace sandboxing tool used for native Linux build isolation.",
                    "LGPL-2.0", "https://github.com/containers/bubblewrap", false),
            TOOL_SEATBELT,
            new ToolMetadata("macOS kernel sandbox utility (/usr/bin/sandbox-exec) used for native build isolation.",
                    "macOS System", "https://developer.apple.com/documentation/security/app-sandbox", false));

    public SystemTool {
        name = StringUtils.defaultString(name);
        version = StringUtils.defaultString(version);
        status = StringUtils.defaultString(status);
        ToolMetadata meta = KNOWN_METADATA.getOrDefault(name, EMPTY_METADATA);
        description = StringUtils.defaultIfBlank(description, meta.description());
        license = StringUtils.defaultIfBlank(license, meta.license());
        websiteUrl = StringUtils.defaultIfBlank(websiteUrl, meta.websiteUrl());
        required = required || meta.required();
    }

    public static SystemTool ofKnownTool(String name, String version, String status) {
        return new SystemTool(name, version, status, null, null, null, false);
    }
}