package org.roxycode.app.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class ProjectAnalysisService {

    private final ProjectService projectService;
    private static final Set<String> IGNORED_DIRS = Set.of(".git", "node_modules", "target", "build", "dist", ".roxycode");

    public ProjectAnalysisService(ProjectService projectService) {
        this.projectService = projectService;
    }

    public String getDominantLanguage() {
        if (!projectService.hasActiveProject()) {
            return "Unknown";
        }

        Path root = projectService.getCurrentProjectRoot();
        Map<String, Integer> extensionCounts = new HashMap<>();

        try (Stream<Path> stream = Files.walk(root, 5)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> {
                      for (Path part : root.relativize(p)) {
                          if (IGNORED_DIRS.contains(part.toString())) {
                              return false;
                          }
                      }
                      return true;
                  })
                  .forEach(p -> {
                      String name = p.getFileName().toString();
                      int lastDot = name.lastIndexOf('.');
                      if (lastDot > 0 && lastDot < name.length() - 1) {
                          String ext = name.substring(lastDot);
                          extensionCounts.put(ext, extensionCounts.getOrDefault(ext, 0) + 1);
                      }
                  });
        } catch (IOException e) {
            return "Unknown";
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
            default -> "Unknown (" + dominantExt + ")";
        };
    }
}