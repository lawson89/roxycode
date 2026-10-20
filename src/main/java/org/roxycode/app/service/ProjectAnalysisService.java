package org.roxycode.app.service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.roxycode.app.ai.services.GrepService;
import org.springframework.stereotype.Service;

@Service
public class ProjectAnalysisService {

    private final GrepService grepService;

    public ProjectAnalysisService(GrepService grepService) {
        this.grepService = grepService;
    }

    public String getDominantLanguage() {
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
                extensionCounts.put(ext, extensionCounts.getOrDefault(ext, 0) + 1);
            }
        }

        if (extensionCounts.isEmpty()) {
            return "Unknown";
        }

        String dominantExt = "";
        int maxCount = -1;
        for (var entry : extensionCounts.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                dominantExt = entry.getKey();
            }
        }

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
