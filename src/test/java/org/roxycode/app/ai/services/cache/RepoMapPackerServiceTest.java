package org.roxycode.app.ai.services.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.ProjectService;

class RepoMapPackerServiceTest {

    @TempDir Path tempDir;

    private ProjectService projectService;
    private ObjectMapper objectMapper;
    private GrepService grepService;
    private ProjectCacheMetaService metaService;
    private RepoMapPackerService service;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        objectMapper = new ObjectMapper();
        grepService = mock(GrepService.class);
        metaService = mock(ProjectCacheMetaService.class);
        service = new RepoMapPackerService(projectService, objectMapper, grepService, metaService);
        lenient().when(projectService.hasActiveProject()).thenReturn(true);
        lenient().when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);
    }

    private String ctagsLine(String name, String path, int line) throws IOException {
        return objectMapper.writeValueAsString(Map.of("name", name, "path", path, "line", line))
                + "\n";
    }

    private String ctagsLineWithKind(String name, String path, int line, String kind)
            throws IOException {
        return objectMapper.writeValueAsString(
                        Map.of("name", name, "path", path, "line", line, "kind", kind))
                + "\n";
    }

    @Test
    void testNoActiveProject() {
        when(projectService.hasActiveProject()).thenReturn(false);
        EditorResult result = service.generateRepoMap();
        assertFalse(result.success());
        assertEquals("No active project", result.errorHint());
    }

    @Test
    void testUnifiedFlatSortedOutputSourceOnlySkeletonsAndBoilerplateFiltering()
            throws IOException {
        String nl = System.lineSeparator();
        Path srcDir = tempDir.resolve("src/main/java/demo");
        Path testDir = tempDir.resolve("src/test/java/demo");
        Files.createDirectories(srcDir);
        Files.createDirectories(testDir);

        Path smallJava =
                Files.writeString(
                        srcDir.resolve("SmallService.java"),
                        String.join(
                                nl,
                                "package demo;",
                                "import java.util.List;",
                                "public class SmallService {",
                                "    private int secretBodyLine = 42;",
                                "    public void execute() {}",
                                "}",
                                ""));
        Files.writeString(
                testDir.resolve("SmallServiceTest.java"),
                "package demo;\nclass SmallServiceTest { void testIt() {} }\n");
        Files.writeString(
                tempDir.resolve("README.md"), "# Project Title" + nl + "Some markdown prose");
        Files.writeString(tempDir.resolve("pom.xml"), "<project><groupId>demo</groupId></project>");
        Files.writeString(srcDir.resolve("Empty.java"), "");

        // Return unsorted list to verify lexicographical sorting
        when(grepService.listFiles(null))
                .thenReturn(
                        List.of(
                                "src/test/java/demo/SmallServiceTest.java",
                                "src/main/java/demo/SmallService.java",
                                "pom.xml",
                                "README.md",
                                "src/main/java/demo/Empty.java"));

        List<List<String>> ctagsCommands = new ArrayList<>();
        service.setCtagsRunner(
                cmd -> {
                    ctagsCommands.add(cmd);
                    return new ProcessResult(
                            0,
                            ctagsLineWithKind("demo", smallJava.toString(), 1, "package")
                                    + ctagsLineWithKind("List", smallJava.toString(), 2, "import")
                                    + ctagsLineWithKind("ns", smallJava.toString(), 1, "namespace")
                                    + ctagsLineWithKind(
                                            "SmallService", smallJava.toString(), 3, "class")
                                    + ctagsLineWithKind(
                                            "execute", smallJava.toString(), 5, "method"),
                            "",
                            false);
                });

        EditorResult result = service.generateRepoMap();
        assertTrue(result.success());
        String content = result.content();

        String expectedMap =
                String.join(
                        "\n",
                        "# Repo Map Snapshot",
                        "README.md",
                        "pom.xml",
                        "src/main/java/demo/Empty.java",
                        "src/main/java/demo/SmallService.java",
                        "  3: public class SmallService {",
                        "  5: public void execute() {}",
                        "src/test/java/demo/SmallServiceTest.java",
                        "");
        assertEquals(expectedMap, content);

        for (String forbidden :
                List.of(
                        "## Project Tree",
                        "--- ",
                        "1: package demo;",
                        "2: import java.util.List;",
                        "secretBodyLine",
                        "Some markdown prose",
                        "testIt")) {
            assertFalse(content.contains(forbidden), "Should not contain: " + forbidden);
        }

        // Only SmallService.java should be passed to ctags (not README.md, pom.xml, Empty.java, or
        // SmallServiceTest.java)
        assertEquals(1, ctagsCommands.size());
        assertEquals(6, ctagsCommands.get(0).size());
        assertTrue(ctagsCommands.get(0).get(5).endsWith("SmallService.java"));
    }

    @Test
    void testSourceAndTestClassificationHelpers() {
        for (String src :
                List.of(
                        "src/main/java/App.java",
                        "app/main.py",
                        "web/index.ts",
                        "web/Component.tsx",
                        "web/script.js",
                        "web/View.jsx",
                        "cmd/server.go",
                        "src/lib.rs",
                        "native/core.c",
                        "native/core.cpp",
                        "native/core.h",
                        "native/core.hpp",
                        "Program.cs",
                        "app.rb",
                        "index.php",
                        "Main.kt",
                        "build.gradle.kts",
                        "App.swift",
                        "Main.scala")) {
            assertTrue(RepoMapPackerService.isSourceFile(src), "Expected source: " + src);
            assertTrue(
                    RepoMapPackerService.shouldGenerateSkeleton(src), "Expected skeleton: " + src);
        }

        for (String nonSrc :
                List.of(
                        "README.md",
                        "pom.xml",
                        "settings.toml",
                        "application.properties",
                        "styles.css",
                        "package.json",
                        "config.yml",
                        "config.yaml",
                        "mvnw",
                        "LICENSE",
                        "")) {
            assertFalse(
                    RepoMapPackerService.isSourceFile(nonSrc), "Expected non-source: " + nonSrc);
            assertFalse(RepoMapPackerService.shouldGenerateSkeleton(nonSrc));
        }
        assertFalse(RepoMapPackerService.isSourceFile(null));

        for (String testPath :
                List.of(
                        "src/test/java/org/roxycode/Helper.java",
                        "module/src/test/java/Helper.java",
                        "test/unit/helper.py",
                        "pkg/test/helper.go",
                        "tests/integration/run.py",
                        "app/tests/run.py",
                        "__tests__/App.tsx",
                        "src/__tests__/App.tsx",
                        "src/main/java/MyTest.java",
                        "src/main/java/MyTests.java",
                        "src/main/java/MyIT.java",
                        "src/test_runner.py",
                        "src/runner_test.py",
                        "pkg/server_test.go",
                        "src/app.test.ts",
                        "src/app.spec.ts",
                        "src/app.test.js",
                        "src/app.spec.js")) {
            assertTrue(
                    RepoMapPackerService.isTestFile(testPath), "Expected test file: " + testPath);
            assertFalse(
                    RepoMapPackerService.shouldGenerateSkeleton(testPath),
                    "Expected no skeleton for test: " + testPath);
        }
        assertFalse(RepoMapPackerService.isTestFile("src/main/java/ContestService.java"));
        assertFalse(RepoMapPackerService.isTestFile(null));
    }

    @Test
    void testLoadsPersistedRepoCacheOnStartupAndInvalidatesOnFileChanged() throws IOException {
        when(metaService.loadRepoCache()).thenReturn(Optional.of("# Persisted Repo Map"));

        EditorResult firstResult = service.generateRepoMap();
        assertTrue(firstResult.success());
        assertEquals("# Persisted Repo Map", firstResult.content());
        verify(grepService, never()).listFiles(null);

        Path testFile = Files.writeString(tempDir.resolve("Fresh.java"), "public class Fresh {}\n");
        when(grepService.listFiles(null)).thenReturn(List.of("Fresh.java"));
        service.setCtagsRunner(
                cmd -> new ProcessResult(0, ctagsLine("Fresh", testFile.toString(), 1), "", false));

        service.handleFileChanged(new FileChangedEvent("Fresh.java"));
        EditorResult secondResult = service.generateRepoMap();
        assertTrue(secondResult.success());
        assertTrue(secondResult.content().contains("Fresh.java\n  1: public class Fresh {}"));
        verify(grepService, times(1)).listFiles(null);
    }

    @Test
    void testBatchedCtagsExecutionAndPerFileSkeletonCache() throws IOException {
        when(metaService.loadRepoCache()).thenReturn(Optional.empty());

        Path largeA = tempDir.resolve("LargeA.java");
        Path largeB = tempDir.resolve("LargeB.java");
        String nl = System.lineSeparator();
        String padding = "// " + "x".repeat(2200) + nl;
        Files.writeString(largeA, "public class LargeA {" + nl + padding + "}" + nl);
        Files.writeString(largeB, "public class LargeB {" + nl + padding + "}" + nl);

        when(grepService.listFiles(null)).thenReturn(List.of("LargeA.java", "LargeB.java"));

        List<List<String>> ctagsCommands = new ArrayList<>();
        service.setCtagsRunner(
                cmd -> {
                    ctagsCommands.add(cmd);
                    StringBuilder jsonOut = new StringBuilder();
                    for (int i = 5; i < cmd.size(); i++) {
                        jsonOut.append(ctagsLine("Cls", cmd.get(i), 1));
                    }
                    return new ProcessResult(0, jsonOut.toString(), "", false);
                });

        EditorResult first = service.generateRepoMap();
        assertTrue(first.success());
        assertTrue(first.content().contains("  1: public class LargeA {"));
        assertTrue(first.content().contains("  1: public class LargeB {"));
        assertEquals(1, ctagsCommands.size());
        assertEquals(7, ctagsCommands.get(0).size());

        Files.writeString(largeA, "public class LargeAUpdated {" + nl + padding + "}" + nl);
        service.handleFileChanged(new FileChangedEvent("LargeA.java"));

        EditorResult second = service.generateRepoMap();
        assertTrue(second.success());
        assertTrue(second.content().contains("  1: public class LargeAUpdated {"));
        assertTrue(second.content().contains("  1: public class LargeB {"));
        assertEquals(2, ctagsCommands.size());
        assertEquals(6, ctagsCommands.get(1).size());
        assertTrue(ctagsCommands.get(1).get(5).endsWith("LargeA.java"));
    }

    @Test
    void testExcludeBinaryFilesAndKnownBinaryExtensionsFastPath() throws IOException {
        assertTrue(RepoMapPackerService.hasKnownBinaryExtension(Path.of("logo.PNG")));
        assertTrue(RepoMapPackerService.hasKnownBinaryExtension(Path.of("lib/app.jar")));
        assertTrue(RepoMapPackerService.hasKnownBinaryExtension(Path.of("archive.tar.gz")));
        assertFalse(RepoMapPackerService.hasKnownBinaryExtension(Path.of("Main.java")));
        assertFalse(RepoMapPackerService.hasKnownBinaryExtension(Path.of("Makefile")));
        assertFalse(RepoMapPackerService.hasKnownBinaryExtension(null));

        Files.writeString(tempDir.resolve("test.txt"), "Hello Text");
        Files.write(tempDir.resolve("custom.dat"), new byte[] {0, 1, 2, 3});
        when(grepService.listFiles(null))
                .thenReturn(List.of("test.txt", "custom.dat", "assets/icon.png", "dist/app.jar"));

        EditorResult result = service.generateRepoMap();
        assertTrue(result.success());
        assertTrue(result.content().contains("test.txt"));
        assertFalse(result.content().contains("custom.dat"));
        assertFalse(result.content().contains("icon.png"));
        assertFalse(result.content().contains("app.jar"));
    }

    @Test
    void testConcurrentInvalidationDuringGenerationDoesNotCacheStaleMap() throws IOException {
        Path testFile =
                Files.writeString(tempDir.resolve("Versioned.java"), "public class Version1 {}\n");
        service.setCtagsRunner(
                cmd ->
                        new ProcessResult(
                                0, ctagsLine("Versioned", testFile.toString(), 1), "", false));

        AtomicBoolean invalidateDuringList = new AtomicBoolean(true);
        when(grepService.listFiles(null))
                .thenAnswer(
                        invocation -> {
                            if (invalidateDuringList.compareAndSet(true, false)) {
                                service.handleFileChanged(new FileChangedEvent("Versioned.java"));
                            }
                            return List.of("Versioned.java");
                        });

        EditorResult firstResult = service.generateRepoMap();
        assertTrue(firstResult.success());
        assertTrue(firstResult.content().contains("  1: public class Version1 {}"));
        verify(metaService, never()).saveRepoCache(anyString());

        Files.writeString(testFile, "public class Version2 {}\n");
        service.handleFileChanged(new FileChangedEvent("Versioned.java"));
        EditorResult secondResult = service.generateRepoMap();
        assertTrue(secondResult.success());
        assertTrue(secondResult.content().contains("  1: public class Version2 {}"));
        verify(grepService, times(2)).listFiles(null);
        verify(metaService, times(1)).saveRepoCache(secondResult.content());

        EditorResult thirdResult = service.generateRepoMap();
        assertTrue(thirdResult.success());
        assertEquals(secondResult.content(), thirdResult.content());
        verify(grepService, times(2)).listFiles(null);
    }
}
