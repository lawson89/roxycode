package org.roxycode.app.service;

import org.roxycode.app.ai.services.GrepService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            
            // Ensure the dot is actually an extension and not a hidden file without an extension (like .gitignore)
            if (lastDot > lastSlash + 1 && lastDot < filePath.length() - 1) {
                String ext = filePath.substring(lastDot).toLowerCase();
                extensionCounts.put(ext, extensionCounts.getOrDefault(ext, 0) + 1);
            }
        }

        if (extensionCounts.isEmpty()) {
            return "Unknown";
        }

        String dominantExt = extensionCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");

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