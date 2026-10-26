package org.roxycode.app.ai.services.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
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
        projectService = Mockito.mock(ProjectService.class);
        objectMapper = new ObjectMapper();
        grepService = Mockito.mock(GrepService.class);
        metaService = Mockito.mock(ProjectCacheMetaService.class);
        service = new RepoMapPackerService(projectService, objectMapper, grepService, metaService);
    }

    @Test
    void testNoActiveProject() {
        when(projectService.hasActiveProject()).thenReturn(false);
        EditorResult result = service.generateRepoMap();
        assertFalse(result.success());
        assertEquals("No active project", result.errorHint());
    }

    @Test
    void testSmallCodeFilesUseSkeletonsAndNoSkeletonFilesAreOmittedFromSections()
            throws IOException {
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);

        String nl = System.lineSeparator();
        Path smallJava = tempDir.resolve("SmallService.java");
        Files.writeString(
                smallJava,
                "package demo;"
                        + nl
                        + "public class SmallService {"
                        + nl
                        + "    private int secretBodyLine = 42;"
                        + nl
                        + "    public void execute() {}"
                        + nl
                        + "}"
                        + nl);

        Path readme = tempDir.resolve("README.md");
        Files.writeString(readme, "# Project Title" + nl + "Some markdown prose");

        Path emptyFile = tempDir.resolve("Empty.java");
        Files.writeString(emptyFile, "");

        when(grepService.listFiles(null))
                .thenReturn(List.of("SmallService.java", "README.md", "Empty.java"));

        List<List<String>> ctagsCommands = new ArrayList<>();
        service.setCtagsRunner(
                cmd -> {
                    ctagsCommands.add(cmd);
                    String line1 =
                            objectMapper.writeValueAsString(
                                    java.util.Map.of(
                                            "name",
                                            "SmallService",
                                            "path",
                                            smallJava.toString(),
                                            "line",
                                            2));
                    String line2 =
                            objectMapper.writeValueAsString(
                                    java.util.Map.of(
                                            "name",
                                            "execute",
                                            "path",
                                            smallJava.toString(),
                                            "line",
                                            4));
                    return new ProcessResult(0, line1 + nl + line2 + nl, "", false);
                });

        EditorResult result = service.generateRepoMap();
        assertTrue(result.success());
        String content = result.content();

        assertTrue(content.contains("# Repo Map Snapshot"));
        assertTrue(content.contains("## Project Tree"));
        // All non-binary files appear in the Project Tree
        assertTrue(content.contains("SmallService.java"));
        assertTrue(content.contains("README.md"));
        assertTrue(content.contains("Empty.java"));

        // Small (< 2KB) Java file uses ctags skeleton rather than raw content dump
        assertTrue(content.contains("--- SmallService.java ---"));
        assertTrue(content.contains("2: public class SmallService {"));
        assertTrue(content.contains("4: public void execute() {}"));
        assertFalse(
                content.contains("secretBodyLine"),
                "Small file should use ctags skeleton and not dump raw non-skeleton lines");

        // Files without skeletons (README.md, Empty.java) must not emit per-file blocks or omitted
        // notices
        assertFalse(
                content.contains("--- README.md ---"),
                "Files without skeletons must not emit a per-file section");
        assertFalse(content.contains("Some markdown prose"));
        assertFalse(content.contains("--- Empty.java ---"));
        assertFalse(content.contains("Full content omitted"));

        // Empty 0-byte file should not be passed to ctags
        assertEquals(1, ctagsCommands.size());
        assertFalse(
                ctagsCommands.get(0).stream().anyMatch(arg -> arg.endsWith("Empty.java")),
                "0-byte files should be skipped during ctags prefetch");
    }

    @Test
    void testLoadsPersistedRepoCacheOnStartupAndInvalidatesOnFileChanged() throws IOException {
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);
        when(metaService.loadRepoCache()).thenReturn(Optional.of("# Persisted Repo Map"));

        EditorResult firstResult = service.generateRepoMap();
        assertTrue(firstResult.success());
        assertEquals("# Persisted Repo Map", firstResult.content());
        verify(grepService, never()).listFiles(null);

        // File change invalidates persisted cache eligibility and triggers fresh generation
        Path testFile = tempDir.resolve("Fresh.java");
        Files.writeString(testFile, "public class Fresh {}\n");
        when(grepService.listFiles(null)).thenReturn(List.of("Fresh.java"));
        service.setCtagsRunner(
                cmd ->
                        new ProcessResult(
                                0,
                                objectMapper.writeValueAsString(
                                                java.util.Map.of(
                                                        "name",
                                                        "Fresh",
                                                        "path",
                                                        testFile.toString(),
                                                        "line",
                                                        1))
                                        + "\n",
                                "",
                                false));

        service.handleFileChanged(new FileChangedEvent("Fresh.java"));
        EditorResult secondResult = service.generateRepoMap();
        assertTrue(secondResult.success());
        assertTrue(secondResult.content().contains("1: public class Fresh {}"));
        verify(grepService, times(1)).listFiles(null);
    }

    @Test
    void testBatchedCtagsExecutionAndPerFileSkeletonCache() throws IOException {
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);
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
                        jsonOut.append(
                                        objectMapper.writeValueAsString(
                                                java.util.Map.of(
                                                        "name",
                                                        "Cls",
                                                        "path",
                                                        cmd.get(i),
                                                        "line",
                                                        1)))
                                .append(nl);
                    }
                    return new ProcessResult(0, jsonOut.toString(), "", false);
                });

        EditorResult first = service.generateRepoMap();
        assertTrue(first.success());
        assertTrue(first.content().contains("1: public class LargeA {"));
        assertTrue(first.content().contains("1: public class LargeB {"));
        assertEquals(
                1,
                ctagsCommands.size(),
                "Multiple uncached files should be processed in a single batched ctags call");
        assertEquals(7, ctagsCommands.get(0).size());

        // Modify only LargeA.java and fire FileChangedEvent
        Files.writeString(largeA, "public class LargeAUpdated {" + nl + padding + "}" + nl);
        service.handleFileChanged(new FileChangedEvent("LargeA.java"));

        EditorResult second = service.generateRepoMap();
        assertTrue(second.success());
        assertTrue(second.content().contains("1: public class LargeAUpdated {"));
        assertTrue(second.content().contains("1: public class LargeB {"));
        assertEquals(
                2,
                ctagsCommands.size(),
                "Second generation should only invoke ctags for the modified file while reusing cached skeleton for LargeB.java");
        assertEquals(6, ctagsCommands.get(1).size());
        assertTrue(ctagsCommands.get(1).get(5).endsWith("LargeA.java"));
    }

    @Test
    void testExcludeBinaryFilesAndKnownBinaryExtensionsFastPath() throws IOException {
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);

        assertTrue(RepoMapPackerService.hasKnownBinaryExtension(Path.of("logo.PNG")));
        assertTrue(RepoMapPackerService.hasKnownBinaryExtension(Path.of("lib/app.jar")));
        assertTrue(RepoMapPackerService.hasKnownBinaryExtension(Path.of("archive.tar.gz")));
        assertFalse(RepoMapPackerService.hasKnownBinaryExtension(Path.of("Main.java")));
        assertFalse(RepoMapPackerService.hasKnownBinaryExtension(Path.of("Makefile")));
        assertFalse(RepoMapPackerService.hasKnownBinaryExtension(null));

        // Create a text file
        Path textFile = tempDir.resolve("test.txt");
        Files.writeString(textFile, "Hello Text");

        // Create a binary file with an unknown extension (contains NUL byte, exercises fallback)
        Path unknownBinaryFile = tempDir.resolve("custom.dat");
        Files.write(unknownBinaryFile, new byte[] {0, 1, 2, 3});

        // Known binary extension file (does not even need to be read from disk)
        when(grepService.listFiles(null))
                .thenReturn(List.of("test.txt", "custom.dat", "assets/icon.png", "dist/app.jar"));

        EditorResult result = service.generateRepoMap();
        assertTrue(result.success());
        assertTrue(result.content().contains("test.txt"));
        assertFalse(result.content().contains("custom.dat"), "NUL-byte file should be excluded");
        assertFalse(
                result.content().contains("icon.png"),
                "Known binary extension .png should be excluded");
        assertFalse(
                result.content().contains("app.jar"),
                "Known binary extension .jar should be excluded");
    }

    @Test
    void testConcurrentInvalidationDuringGenerationDoesNotCacheStaleMap() throws IOException {
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);

        Path testFile = tempDir.resolve("Versioned.java");
        Files.writeString(testFile, "public class Version1 {}\n");

        service.setCtagsRunner(
                cmd ->
                        new ProcessResult(
                                0,
                                objectMapper.writeValueAsString(
                                                java.util.Map.of(
                                                        "name",
                                                        "Versioned",
                                                        "path",
                                                        testFile.toString(),
                                                        "line",
                                                        1))
                                        + "\n",
                                "",
                                false));

        AtomicBoolean invalidateDuringList = new AtomicBoolean(true);
        when(grepService.listFiles(null))
                .thenAnswer(
                        invocation -> {
                            if (invalidateDuringList.compareAndSet(true, false)) {
                                // Simulate a concurrent FileChangedEvent while generateRepoMap() is
                                // mid-execution
                                service.handleFileChanged(new FileChangedEvent("Versioned.java"));
                            }
                            return List.of("Versioned.java");
                        });

        EditorResult firstResult = service.generateRepoMap();
        assertTrue(firstResult.success());
        assertTrue(firstResult.content().contains("1: public class Version1 {}"));
        // Stale generation must not be persisted to metaService
        verify(metaService, never()).saveRepoCache(Mockito.anyString());

        // Update file content; next call must regenerate instead of returning stale cache
        Files.writeString(testFile, "public class Version2 {}\n");
        service.handleFileChanged(new FileChangedEvent("Versioned.java"));
        EditorResult secondResult = service.generateRepoMap();
        assertTrue(secondResult.success());
        assertTrue(secondResult.content().contains("1: public class Version2 {}"));
        verify(grepService, times(2)).listFiles(null);
        verify(metaService, times(1)).saveRepoCache(secondResult.content());

        // Third call without invalidation should use the cached map
        EditorResult thirdResult = service.generateRepoMap();
        assertTrue(thirdResult.success());
        assertEquals(secondResult.content(), thirdResult.content());
        verify(grepService, times(2)).listFiles(null);
    }
}
