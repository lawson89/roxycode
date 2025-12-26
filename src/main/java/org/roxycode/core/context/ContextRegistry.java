package org.roxycode.core.context;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Singleton
public class ContextRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(ContextRegistry.class);

    public static class ContextInfo {
        public String description = "No description";
        public String inlineContext = null;
        public List<String> relatedFiles = new ArrayList<>();
    }

    private final Map<String, ContextInfo> availableContexts = new HashMap<>();

    public void loadContexts(String rootPath) {
        availableContexts.clear();
        Path contextPath = Paths.get(rootPath, "src/main/resources/context");

        if (!Files.exists(contextPath)) {
            LOG.info("ℹ️ No context directory found at: {}", contextPath);
            return;
        }

        LOG.info("🔍 Scanning for knowledge context in: {}", contextPath);

        try (Stream<Path> stream = Files.list(contextPath)) {
            stream.filter(p -> p.toString().endsWith(".toml"))
                    .forEach(this::parseContextFile);

            LOG.info("📚 Total Contexts Loaded: {}", availableContexts.size());
        } catch (IOException e) {
            LOG.error("Failed to list context files", e);
        }
    }

    private void parseContextFile(Path path) {
        try {
            LOG.debug("Parsing context file: {}", path.getFileName());

            String content = Files.readString(path);
            String fileName = "context/" + path.getFileName().toString();
            ContextInfo info = new ContextInfo();

            // 1. Extract Description
            info.description = extractField(content, "description");

            // 2. Extract Inline Context
            String ctx = extractField(content, "context");
            if (ctx != null && !ctx.isBlank()) {
                info.inlineContext = ctx;
            }

            // 3. Extract Files List (files = ["a", "b"])
            Matcher filesMatcher = Pattern.compile("files\\s*=\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(content);
            if (filesMatcher.find()) {
                String listContent = filesMatcher.group(1);
                for (String part : listContent.split(",")) {
                    String clean = part.trim().replaceAll("^\"|\"$", "").trim();
                    if (!clean.isEmpty()) {
                        info.relatedFiles.add(clean);
                    }
                }
            }

            availableContexts.put(fileName, info);

            // LOGGING THE SUCCESSFUL LOAD
            LOG.info("✅ Loaded Context: {} | Inline: {} | Files: {}",
                    fileName,
                    (info.inlineContext != null),
                    info.relatedFiles);

        } catch (IOException e) {
            LOG.error("❌ Failed to parse context file: {}", path, e);
        }
    }

    // Helper to extract string fields (supports """ and ")
    private String extractField(String content, String fieldName) {
        Matcher m = Pattern.compile(fieldName + "\\s*=\\s*(\"\"\"(.*?)\"\"\"|\"(.*?)\")", Pattern.DOTALL).matcher(content);
        if (m.find()) {
            // Group 2 is triple-quoted, Group 3 is single-quoted
            String raw = m.group(2) != null ? m.group(2) : m.group(3);
            return raw != null ? raw.trim() : null;
        }
        return null;
    }

    public String getContextMenu() {
        if (availableContexts.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("\n\n📚 Available Knowledge Contexts:\n");
        sb.append("(Use read_file to load these definitions)\n");

        availableContexts.forEach((fileName, info) -> {
            sb.append(String.format("- %s: %s\n", fileName, info.description.replace("\n", " ")));

            List<String> details = new ArrayList<>();
            if (info.inlineContext != null) details.add("Contains inline documentation");
            if (!info.relatedFiles.isEmpty()) details.add("Refers to: " + String.join(", ", info.relatedFiles));

            if (!details.isEmpty()) {
                sb.append("  [" + String.join(" | ", details) + "]\n");
            }
        });

        return sb.toString();
    }
}