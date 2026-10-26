package org.roxycode.app.service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.events.ProjectChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class ProjectAnalysisService {

    private final GrepService grepService;
    private volatile String cachedDominantLanguage;

    public ProjectAnalysisService(GrepService grepService) {
        this.grepService = grepService;
    }

    @EventListener
    @SuppressWarnings("PMD.NullAssignment")
    public synchronized void onProjectChanged(ProjectChangedEvent event) {
        this.cachedDominantLanguage = null;
    }

    public String getDominantLanguage() {
        String cached = this.cachedDominantLanguage;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (this.cachedDominantLanguage != null) {
                return this.cachedDominantLanguage;
            }
            String computed = computeDominantLanguage();
            this.cachedDominantLanguage = computed;
            return computed;
        }
    }

    private String computeDominantLanguage() {
        List<String> files = grepService.listFiles(null);
        if (files == null || files.isEmpty()) {
            return "Unknown";
        }

        Map<String, Integer> extensionCounts = new HashMap<>();

        for (String filePath : files) {
            int lastDot = filePath.lastIndexOf('.');
            int lastSlash = filePath.lastIndexOf('/');

            // Ensure the dot is actually an extension and not a hidden file without an
            // extension (like
            // .gitignore)
            if (lastDot > lastSlash + 1 && lastDot < filePath.length() - 1) {
                String ext = filePath.substring(lastDot).toLowerCase(Locale.ROOT);
                extensionCounts.merge(ext, 1, Integer::sum);
            }
        }

        if (extensionCounts.isEmpty()) {
            return "Unknown";
        }

        String dominantExt =
                Collections.max(extensionCounts.entrySet(), Map.Entry.comparingByValue()).getKey();

        return switch (dominantExt) {
            case ".java" -> "Java";
            case ".py" -> "Python";
            case ".ts" -> "TypeScript";
            case ".js" -> "JavaScript";
            case ".go" -> "Go";
            case ".rs" -> "Rust";
            case ".cpp", ".cc", ".cxx" -> "C++";
            case ".c" -> "C";
            case ".cs" -> "C#";
            case ".rb" -> "Ruby";
            case ".php" -> "PHP";
            case ".sh" -> "Shell";
            case ".kt" -> "Kotlin";
            case ".swift" -> "Swift";
            default -> "Unknown (" + dominantExt + ")";
        };
    }
}
