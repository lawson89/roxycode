package org.roxycode.app.ai.services.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService.RepoMapSnapshot;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.ui.SwingTestUtils;

class RepoMapPackerServiceTest {

    @TempDir
    Path tempDir;

    private ProjectService projectService;
    private ObjectMapper objectMapper;
    private GrepService grepService;
    private RepoMapPackerService service;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        objectMapper = new ObjectMapper();
        grepService = mock(GrepService.class);
        service = new RepoMapPackerService(projectService, objectMapper, grepService);
        lenient().when(projectService.hasActiveProject()).thenReturn(true);
        lenient().when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);
        lenient().when(projectService.getProjectName()).thenReturn("test-project");
    }

    private String ctagsLine(String name, String path, int line) {
        try {
            return objectMapper.writeValueAsString(Map.of("name", name, "path", path, "line", line)) + "\n";
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String ctagsLineWithPattern(String name, String path, int line, String kind, String pattern) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("path", path);
            map.put("line", line);
            map.put("kind", kind);
            if (pattern != null) {
                map.put("pattern", pattern);
            }
            return objectMapper.writeValueAsString(map) + "\n";
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void testUnifiedFlatSortedOutputSourceOnlySkeletonsAndBoilerplateFiltering() throws IOException {
        String nl = System.lineSeparator();
        Path srcDir = Files.createDirectories(tempDir.resolve("src/main/java/demo"));
        Path testDir = Files.createDirectories(tempDir.resolve("src/test/java/demo"));

        Path smallJava = Files.writeString(srcDir.resolve("SmallService.java"),
                String.join(nl, "package demo;", "import java.util.List;", "public class SmallService {",
                        "    private int secretBodyLine = 42;", "    public void execute() {}// comment",
                        "    public String submitPlan(", "            String title) { return title; }", "}", ""));
        Files.writeString(testDir.resolve("SmallServiceTest.java"),
                "package demo;" + nl + "class SmallServiceTest { void testIt() {} }" + nl);
        Files.writeString(tempDir.resolve("README.md"), "# Project Title" + nl + "Some markdown prose");
        Files.writeString(tempDir.resolve("pom.xml"), "<project><groupId>demo</groupId></project>");
        Files.writeString(srcDir.resolve("Empty.java"), "");

        when(grepService.listFiles(null)).thenReturn(List.of("src/test/java/demo/SmallServiceTest.java",
                "src/main/java/demo/SmallService.java", "pom.xml", "README.md", "src/main/java/demo/Empty.java"));

        List<List<String>> ctagsCommands = new ArrayList<>();
        service.setCtagsRunner(cmd -> {
            ctagsCommands.add(cmd);
            return new ProcessResult(0,
                    ctagsLineWithPattern("demo", smallJava.toString(), 1, "package", null)
                            + ctagsLineWithPattern("List", smallJava.toString(), 2, "import", null)
                            + ctagsLineWithPattern("ns", smallJava.toString(), 1, "namespace", null)
                            + ctagsLineWithPattern("SmallService", smallJava.toString(), 3, "class", null)
                            + ctagsLineWithPattern("execute", smallJava.toString(), 5, "method", null)
                            + ctagsLineWithPattern("submitPlan", smallJava.toString(), 6, "method", null),
                    "", false);
        });

        EditorResult result = service.generateRepoMap();
        assertTrue(result.success());
        String content = result.content();

        assertEquals(String.join(String.valueOf((char) 10), "# Repo Map Snapshot", "README.md", "pom.xml",
                "src/main/java/demo/Empty.java", "src/main/java/demo/SmallService.java",
                "  3: public class SmallService {", "  5: public void execute() {}// comment",
                "  6: public String submitPlan(", "src/test/java/demo/SmallServiceTest.java", ""), content);
        SwingTestUtils.assertContainsNone(content, "## Project Tree", "--- ", "1: package demo;",
                "2: import java.util.List;", "secretBodyLine", "Some markdown prose", "testIt");
        assertEquals(1, ctagsCommands.size());
        assertEquals(7, ctagsCommands.get(0).size());
        assertTrue(ctagsCommands.get(0).contains("--fields=+n"));
        assertTrue(ctagsCommands.get(0).contains("--pattern-length-limit=0"));
        assertTrue(ctagsCommands.get(0).get(6).endsWith("SmallService.java"));

        Optional<RepoMapSnapshot> snapshot = service.getCachedSnapshot();
        assertTrue(snapshot.isPresent());
        assertEquals("test-project", snapshot.get().projectName());
        assertEquals(content, snapshot.get().content());
        assertTrue(snapshot.get().estimatedTokens() > 0);
    }

    @Test
    void testSourceAndTestClassificationHelpers() {
        for (String src : List.of("src/main/java/App.java", "app/main.py", "web/Component.tsx", "cmd/server.go")) {
            assertTrue(RepoMapPackerService.isSourceFile(src));
            assertTrue(RepoMapPackerService.shouldGenerateSkeleton(src));
        }

        for (String nonSrc : List.of("README.md", "pom.xml", "settings.toml", "")) {
            assertFalse(RepoMapPackerService.isSourceFile(nonSrc));
            assertFalse(RepoMapPackerService.shouldGenerateSkeleton(nonSrc));
        }
        assertFalse(RepoMapPackerService.isSourceFile(null));

        for (String testPath : List.of("src/test/java/org/roxycode/Helper.java", "tests/integration/run.py",
                "__tests__/App.tsx", "src/main/java/MyTest.java", "src/test_runner.py", "src/app.spec.ts")) {
            assertTrue(RepoMapPackerService.isTestFile(testPath));
            assertFalse(RepoMapPackerService.shouldGenerateSkeleton(testPath));
        }
        assertFalse(RepoMapPackerService.isTestFile("src/main/java/ContestService.java"));
        assertFalse(RepoMapPackerService.isTestFile(null));
    }

    @Test
    void testRegeneratesOnStartupProjectChangedAndFileChanged() throws IOException {
        Path testFile = Files.writeString(tempDir.resolve("Fresh.java"), "public class Fresh {}\n");
        when(grepService.listFiles(null)).thenReturn(List.of("Fresh.java"));
        service.setCtagsRunner(cmd -> new ProcessResult(0, ctagsLine("Fresh", testFile.toString(), 1), "", false));

        EditorResult firstResult = service.generateRepoMap();
        assertTrue(firstResult.success());
        assertTrue(firstResult.content().contains("Fresh.java\n  1: public class Fresh {}"));
        verify(grepService, times(1)).listFiles(null);

        Files.writeString(testFile, "public class FreshUpdated {}\n");
        assertTrue(service.generateRepoMap().content().contains("public class Fresh {}"));
        verify(grepService, times(1)).listFiles(null);

        service.handleFileChanged(new FileChangedEvent("Fresh.java"));
        assertTrue(service.getCachedSnapshot().isEmpty());
        EditorResult secondResult = service.generateRepoMap();
        assertTrue(secondResult.success());
        assertTrue(secondResult.content().contains("Fresh.java\n  1: public class FreshUpdated {}"));
        verify(grepService, times(2)).listFiles(null);

        Files.writeString(testFile, "public class FreshAfterProjectChange {}\n");
        service.handleProjectChanged(new ProjectChangedEvent(tempDir));
        assertTrue(service.getCachedSnapshot().isEmpty());
        EditorResult projectChangeResult = service.generateRepoMap();
        assertTrue(projectChangeResult.success());
        assertTrue(projectChangeResult.content().contains("Fresh.java\n  1: public class FreshAfterProjectChange {}"));
        verify(grepService, times(3)).listFiles(null);

        Files.writeString(testFile, "public class FreshForced {}\n");
        EditorResult forcedResult = service.forceRegenerateRepoMap();
        assertTrue(forcedResult.success());
        assertTrue(forcedResult.content().contains("Fresh.java\n  1: public class FreshForced {}"));
        assertTrue(service.getCachedSnapshot().isPresent());
        verify(grepService, times(4)).listFiles(null);
    }

    @Test
    void testBatchedCtagsExecutionAndPerFileSkeletonCache() throws IOException {
        String nl = System.lineSeparator();
        String padding = "// " + "x".repeat(2200) + nl;
        Path largeA = Files.writeString(tempDir.resolve("LargeA.java"),
                "public class LargeA {" + nl + padding + "}" + nl);
        Files.writeString(tempDir.resolve("LargeB.java"), "public class LargeB {" + nl + padding + "}" + nl);
        when(grepService.listFiles(null)).thenReturn(List.of("LargeA.java", "LargeB.java"));

        List<List<String>> ctagsCommands = new ArrayList<>();
        service.setCtagsRunner(cmd -> {
            ctagsCommands.add(cmd);
            StringBuilder jsonOut = new StringBuilder();
            for (int i = 6; i < cmd.size(); i++) {
                jsonOut.append(ctagsLine("Cls", cmd.get(i), 1));
            }
            return new ProcessResult(0, jsonOut.toString(), "", false);
        });

        EditorResult first = service.generateRepoMap();
        assertTrue(first.success());
        SwingTestUtils.assertContainsAll(first.content(), "  1: public class LargeA {", "  1: public class LargeB {");
        assertEquals(1, ctagsCommands.size());
        assertEquals(8, ctagsCommands.get(0).size());

        Files.writeString(largeA, "public class LargeAUpdated {" + nl + padding + "}" + nl);
        service.handleFileChanged(new FileChangedEvent("LargeA.java"));

        EditorResult second = service.generateRepoMap();
        assertTrue(second.success());
        SwingTestUtils.assertContainsAll(second.content(), "  1: public class LargeAUpdated {",
                "  1: public class LargeB {");
        assertEquals(2, ctagsCommands.size());
        assertEquals(7, ctagsCommands.get(1).size());
        assertTrue(ctagsCommands.get(1).get(6).endsWith("LargeA.java"));
    }

    @Test
    void testConcurrentInvalidationDuringGenerationDoesNotCacheStaleMap() throws IOException {
        Path testFile = Files.writeString(tempDir.resolve("Versioned.java"), "public class Version1 {}\n");
        service.setCtagsRunner(cmd -> new ProcessResult(0, ctagsLine("Versioned", testFile.toString(), 1), "", false));

        AtomicBoolean invalidateDuringList = new AtomicBoolean(true);
        when(grepService.listFiles(null)).thenAnswer(invocation -> {
            if (invalidateDuringList.compareAndSet(true, false)) {
                service.handleFileChanged(new FileChangedEvent("Versioned.java"));
            }
            return List.of("Versioned.java");
        });

        EditorResult firstResult = service.generateRepoMap();
        assertTrue(firstResult.success());
        assertTrue(firstResult.content().contains("  1: public class Version1 {}"));
        assertTrue(service.getCachedSnapshot().isEmpty());

        Files.writeString(testFile, "public class Version2 {}\n");
        service.handleFileChanged(new FileChangedEvent("Versioned.java"));
        EditorResult secondResult = service.generateRepoMap();
        assertTrue(secondResult.success());
        assertTrue(secondResult.content().contains("  1: public class Version2 {}"));
        verify(grepService, times(2)).listFiles(null);
        assertTrue(service.getCachedSnapshot().isPresent());
        assertEquals(secondResult.content(), service.getCachedSnapshot().get().content());

        EditorResult thirdResult = service.generateRepoMap();
        assertTrue(thirdResult.success());
        assertEquals(secondResult.content(), thirdResult.content());
        verify(grepService, times(2)).listFiles(null);
    }

}
