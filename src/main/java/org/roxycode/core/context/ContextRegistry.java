package org.roxycode.core.context;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /**
     * Loads context definitions from the specified directory.
     * @param contextDir The absolute path to the 'context' folder.
     */
    public void loadContexts(Path contextDir) {
        availableContexts.clear();

        if (!Files.exists(contextDir)) {
            LOG.warn("⚠️ Context directory not found at: {}", contextDir);
            return;
        }

        LOG.info("🔍 Scanning for knowledge context in: {}", contextDir);

        try (Stream<Path> stream = Files.list(contextDir)) {
            stream.filter(p -> p.toString().endsWith(".toml"))
                    .forEach(this::parseContextFile);

            LOG.info("📚 Total Contexts Loaded: {}", availableContexts.size());
        } catch (IOException e) {
            LOG.error("Failed to list context files", e);
        }
    }

    private void parseContextFile(Path path) {
        try {
            String content = Files.readString(path);
            String fileName = path.getFileName().toString(); // Store just filename
            ContextInfo info = new ContextInfo();

            info.description = extractField(content, "description");
            String ctx = extractField(content, "context");
            if (ctx != null && !ctx.isBlank()) info.inlineContext = ctx;

            Matcher filesMatcher = Pattern.compile("files\\s*=\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(content);
            if (filesMatcher.find()) {
                String listContent = filesMatcher.group(1);
                for (String part : listContent.split(",")) {
                    String clean = part.trim().replaceAll("^\"|\"$", "").trim();
                    if (!clean.isEmpty()) info.relatedFiles.add(clean);
                }
            }

            availableContexts.put(fileName, info);
            LOG.info("✅ Loaded Context: {}", fileName);

        } catch (IOException e) {
            LOG.error("❌ Failed to parse context file: {}", path, e);
        }
    }

    private String extractField(String content, String fieldName) {
        Matcher m = Pattern.compile(fieldName + "\\s*=\\s*(\"\"\"(.*?)\"\"\"|\"(.*?)\")", Pattern.DOTALL).matcher(content);
        if (m.find()) {
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
            if (!details.isEmpty()) sb.append("  [" + String.join(" | ", details) + "]\n");
        });
        return sb.toString();
    }
}