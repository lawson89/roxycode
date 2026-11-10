package org.roxycode.app.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.util.PathUtils;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class ProjectAnalysisService {

    public static final Set<String> SOURCE_EXTENSIONS = Set
            .of("java,py,ts,tsx,js,jsx,go,rs,c,cpp,cc,cxx,h,hpp,cs,rb,php,kt,kts,swift,scala".split(","));
    public static final List<String> SOURCE_DIR_MARKERS = List.of("src/main/java/", "src/main/kotlin/",
            "src/main/scala/", "src/main/groovy/", "src/main/", "src/", "lib/", "app/");
    public static final List<String> TEST_DIR_MARKERS = List.of("src/test/java/", "src/test/kotlin/", "src/test/scala/",
            "src/test/groovy/", "src/test/", "tests/", "test/", "__tests__/", "spec/");
    public static final List<String> TEST_FILE_SUFFIXES = List.of(
            "Test.java,Tests.java,IT.java,_test.py,_test.go,.test.ts,.spec.ts,.test.tsx,.spec.tsx,.test.js,.spec.js,.test.jsx,.spec.jsx"
                    .split(","));

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

    public static boolean isTestFile(String relPath) {
        if (relPath == null || relPath.isBlank()) {
            return false;
        }
        String normalized = PathUtils.normalizeRelativePath(relPath);
        for (String marker : TEST_DIR_MARKERS) {
            if (normalized.startsWith(marker) || normalized.contains("/" + marker)) {
                return true;
            }
        }
        String fileName = PathUtils.getFileName(normalized);
        if (fileName.startsWith("test_") && fileName.endsWith(".py")) {
            return true;
        }
        return TEST_FILE_SUFFIXES.stream().anyMatch(fileName::endsWith);
    }

    public static boolean isSourceFile(String relPath) {
        if (relPath == null || relPath.isBlank()) {
            return false;
        }
        String ext = PathUtils.getLowerExtension(relPath, false);
        return !ext.isEmpty() && SOURCE_EXTENSIONS.contains(ext);
    }

    private String computeDominantLanguage() {
        List<String> files = grepService.listFiles(null);
        if (files == null || files.isEmpty()) {
            return "Unknown";
        }

        Map<String, Integer> extensionCounts = new HashMap<>();
        for (String filePath : files) {
            String ext = extractExtension(filePath);
            if (ext != null) {
                extensionCounts.merge(ext, 1, Integer::sum);
            }
        }
        if (extensionCounts.isEmpty()) {
            return "Unknown";
        }

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
        if (ext.isEmpty()) {
            return null;
        }
        return "." + ext;
    }
}
