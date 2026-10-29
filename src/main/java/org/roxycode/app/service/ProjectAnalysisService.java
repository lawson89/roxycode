package org.roxycode.app.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.util.PathUtils;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class ProjectAnalysisService {

    private final GrepService grepService;
    private final Cache<Boolean, String> dominantLanguageCache = Caffeine.newBuilder().maximumSize(1).build();

    public ProjectAnalysisService(GrepService grepService) {
        this.grepService = grepService;
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        dominantLanguageCache.invalidateAll();
    }

    public String getDominantLanguage() {
        return dominantLanguageCache.get(Boolean.TRUE, k -> computeDominantLanguage());
    }

    private String computeDominantLanguage() {
        List<String> files = grepService.listFiles(null);
        if (files == null || files.isEmpty())
            return "Unknown";

        Map<String, Integer> extensionCounts = new HashMap<>();
        for (String filePath : files) {
            String ext = extractExtension(filePath);
            if (ext != null)
                extensionCounts.merge(ext, 1, Integer::sum);
        }
        if (extensionCounts.isEmpty())
            return "Unknown";

        String dominantExt = Collections.max(extensionCounts.entrySet(), Map.Entry.comparingByValue()).getKey();
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

    static String extractExtension(String filePath) {
        String ext = PathUtils.getLowerExtension(filePath, false);
        return ext.isEmpty() ? null : "." + ext;
    }
}
