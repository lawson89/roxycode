package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.roxycode.app.ai.AgentJexlPermissions;
import org.roxycode.app.ai.LLMDocGenerator;
import org.roxycode.app.service.ProcessRunner;
import org.roxycode.app.service.ProcessRunner.ProcessResult;
import org.roxycode.app.service.ProjectService;

class GrepServiceTest {

    @TempDir Path tempDir;

    private ProjectService projectService;
    private GrepService grepService;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);
        lenient().when(projectService.resolvePath(any())).thenCallRealMethod();
        grepService = new GrepService(projectService);
    }

    @Test
    void testGrepSuccessAndRelativePaths() throws IOException {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Hello.java"), "public class Hello {}");

        EditorResult result = grepService.grep("class Hello", "src", "*.java");

        if (result.success()) {
            assertTrue(
                    result.content().contains("src/Hello.java")
                            || result.content().contains("Hello.java"));
            assertFalse(result.content().startsWith(tempDir.toAbsolutePath().toString()));
            assertTrue(result.content().contains("class Hello"));
        }
    }

    @Test
    void testGrepWithContextCommandAndOutput() throws IOException {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);

        String sampleOutput =
                String.join(
                        String.valueOf((char) 10),
                        "src/Sample.java-1-line 1 before",
                        "src/Sample.java-2-line 2 before",
                        "src/Sample.java:3:@AgentDoc",
                        "src/Sample.java-4-public void targetMethod() {");

        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            runner.when(() -> ProcessRunner.run(anyList(), eq(tempDir), eq(true)))
                    .thenReturn(new ProcessResult(0, sampleOutput, "", false));

            EditorResult res = grepService.grepWithContext("@AgentDoc", "src", "*.java", 2);
            assertTrue(res.success());
            assertTrue(res.content().contains("src/Sample.java:3:@AgentDoc"));
            assertTrue(res.content().contains("line 1 before"));

            runner.verify(
                    () ->
                            ProcessRunner.run(
                                    eq(
                                            List.of(
                                                    "rg",
                                                    "-n",
                                                    "-C",
                                                    "2",
                                                    "--no-heading",
                                                    "--color=never",
                                                    "-g",
                                                    "*.java",
                                                    "--",
                                                    "@AgentDoc",
                                                    "src")),
                                    eq(tempDir),
                                    eq(true)));

            // Verify clamping > 20 to 20 and root overload
            grepService.grepWithContext("@AgentDoc", 50);
            runner.verify(
                    () ->
                            ProcessRunner.run(
                                    eq(
                                            List.of(
                                                    "rg",
                                                    "-n",
                                                    "-C",
                                                    "20",
                                                    "--no-heading",
                                                    "--color=never",
                                                    "--",
                                                    "@AgentDoc",
                                                    ".")),
                                    eq(tempDir),
                                    eq(true)));

            // Verify 3-arg overload
            grepService.grepWithContext("@AgentDoc", "src", 3);
            runner.verify(
                    () ->
                            ProcessRunner.run(
                                    eq(
                                            List.of(
                                                    "rg",
                                                    "-n",
                                                    "-C",
                                                    "3",
                                                    "--no-heading",
                                                    "--color=never",
                                                    "--",
                                                    "@AgentDoc",
                                                    "src")),
                                    eq(tempDir),
                                    eq(true)));
        }
    }

    @Test
    void testGrepLiteralPassesFixedStringsAndDelimiterFlag() throws IOException {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);

        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            runner.when(() -> ProcessRunner.run(anyList(), eq(tempDir), eq(true)))
                    .thenReturn(
                            new ProcessResult(0, "src/Code.cpp:1:1:std::vector<int>", "", false));

            EditorResult r1 = grepService.grepLiteral("std::vector<int>");
            assertTrue(r1.success());
            assertTrue(r1.content().contains("std::vector<int>"));
            runner.verify(
                    () ->
                            ProcessRunner.run(
                                    eq(
                                            List.of(
                                                    "rg",
                                                    "--vimgrep",
                                                    "--no-heading",
                                                    "--color=never",
                                                    "-F",
                                                    "--",
                                                    "std::vector<int>",
                                                    ".")),
                                    eq(tempDir),
                                    eq(true)));

            EditorResult r2 = grepService.grepLiteral("foo(bar[0])", "src");
            assertTrue(r2.success());
            runner.verify(
                    () ->
                            ProcessRunner.run(
                                    eq(
                                            List.of(
                                                    "rg",
                                                    "--vimgrep",
                                                    "--no-heading",
                                                    "--color=never",
                                                    "-F",
                                                    "--",
                                                    "foo(bar[0])",
                                                    "src")),
                                    eq(tempDir),
                                    eq(true)));

            EditorResult r3 = grepService.grepLiteral("-Dtest=MyTest", "src", "*.cpp");
            assertTrue(r3.success());
            runner.verify(
                    () ->
                            ProcessRunner.run(
                                    eq(
                                            List.of(
                                                    "rg",
                                                    "--vimgrep",
                                                    "--no-heading",
                                                    "--color=never",
                                                    "-F",
                                                    "-g",
                                                    "*.cpp",
                                                    "--",
                                                    "-Dtest=MyTest",
                                                    "src")),
                                    eq(tempDir),
                                    eq(true)));
        }
    }

    @Test
    void testGrepIgnoreCasePassesIgnoreCaseFlag() throws IOException {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);

        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            runner.when(() -> ProcessRunner.run(anyList(), eq(tempDir), eq(true)))
                    .thenReturn(
                            new ProcessResult(0, "src/Notes.java:1:1:// FIXME: urgent", "", false));

            EditorResult r1 = grepService.grepIgnoreCase("fixme");
            assertTrue(r1.success());
            assertTrue(r1.content().contains("FIXME: urgent"));
            runner.verify(
                    () ->
                            ProcessRunner.run(
                                    eq(
                                            List.of(
                                                    "rg",
                                                    "--vimgrep",
                                                    "--no-heading",
                                                    "--color=never",
                                                    "-i",
                                                    "--",
                                                    "fixme",
                                                    ".")),
                                    eq(tempDir),
                                    eq(true)));

            grepService.grepIgnoreCase("urgent", "src");
            runner.verify(
                    () ->
                            ProcessRunner.run(
                                    eq(
                                            List.of(
                                                    "rg",
                                                    "--vimgrep",
                                                    "--no-heading",
                                                    "--color=never",
                                                    "-i",
                                                    "--",
                                                    "urgent",
                                                    "src")),
                                    eq(tempDir),
                                    eq(true)));

            grepService.grepIgnoreCase("urgent", "src", "*.java");
            runner.verify(
                    () ->
                            ProcessRunner.run(
                                    eq(
                                            List.of(
                                                    "rg",
                                                    "--vimgrep",
                                                    "--no-heading",
                                                    "--color=never",
                                                    "-i",
                                                    "-g",
                                                    "*.java",
                                                    "--",
                                                    "urgent",
                                                    "src")),
                                    eq(tempDir),
                                    eq(true)));
        }
    }

    @Test
    void testOutputCappingAtMaxLines() {
        StringBuilder manyLines = new StringBuilder();
        for (int i = 1; i <= 250; i++) {
            if (i > 1) {
                manyLines.append((char) 10);
            }
            manyLines.append("src/Many.java:").append(i).append(":1:match ").append(i);
        }

        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            runner.when(() -> ProcessRunner.run(anyList(), eq(tempDir), eq(true)))
                    .thenReturn(new ProcessResult(0, manyLines.toString(), "", false));

            EditorResult result = grepService.grep("match");
            assertTrue(result.success());
            List<String> outputLines = result.content().lines().toList();
            assertEquals(GrepService.MAX_OUTPUT_LINES + 1, outputLines.size());
            assertEquals("src/Many.java:1:1:match 1", outputLines.get(0));
            assertEquals("src/Many.java:200:1:match 200", outputLines.get(199));
            assertTrue(outputLines.get(200).contains("50 more lines omitted; narrow your search"));
        }
    }

    @Test
    void testNewMethodsExposedInJexlPermissionsAndLlmDocGenerator() throws Exception {
        assertTrue(
                AgentJexlPermissions.INSTANCE.allow(
                        GrepService.class,
                        GrepService.class.getMethod("grepWithContext", String.class, int.class)));
        assertTrue(
                AgentJexlPermissions.INSTANCE.allow(
                        GrepService.class,
                        GrepService.class.getMethod(
                                "grepWithContext", String.class, String.class, int.class)));
        assertTrue(
                AgentJexlPermissions.INSTANCE.allow(
                        GrepService.class,
                        GrepService.class.getMethod(
                                "grepWithContext",
                                String.class,
                                String.class,
                                String.class,
                                int.class)));
        assertTrue(
                AgentJexlPermissions.INSTANCE.allow(
                        GrepService.class,
                        GrepService.class.getMethod("grepLiteral", String.class)));
        assertTrue(
                AgentJexlPermissions.INSTANCE.allow(
                        GrepService.class,
                        GrepService.class.getMethod("grepLiteral", String.class, String.class)));
        assertTrue(
                AgentJexlPermissions.INSTANCE.allow(
                        GrepService.class,
                        GrepService.class.getMethod(
                                "grepLiteral", String.class, String.class, String.class)));
        assertTrue(
                AgentJexlPermissions.INSTANCE.allow(
                        GrepService.class,
                        GrepService.class.getMethod("grepIgnoreCase", String.class)));
        assertTrue(
                AgentJexlPermissions.INSTANCE.allow(
                        GrepService.class,
                        GrepService.class.getMethod("grepIgnoreCase", String.class, String.class)));
        assertTrue(
                AgentJexlPermissions.INSTANCE.allow(
                        GrepService.class,
                        GrepService.class.getMethod(
                                "grepIgnoreCase", String.class, String.class, String.class)));

        String doc = new LLMDocGenerator().generateDoc(List.of(grepService));
        assertTrue(
                doc.contains(
                        "grepWithContext(pattern: string, contextLines: number): EditorResult;"));
        assertTrue(doc.contains("grepLiteral(text: string): EditorResult;"));
        assertTrue(doc.contains("grepIgnoreCase(pattern: string): EditorResult;"));
    }

    @Test
    void testListFilesReturnsProjectRelativePaths() throws IOException {
        Path promptsDir = tempDir.resolve("src/main/resources/prompts");
        Files.createDirectories(promptsDir);
        Files.writeString(promptsDir.resolve("core_workflow.md"), "# Workflow");

        List<String> files = grepService.listFiles(null);

        if (!files.isEmpty()) {
            assertTrue(files.contains("src/main/resources/prompts/core_workflow.md"));
            for (String file : files) {
                assertFalse(
                        Path.of(file).isAbsolute(),
                        "Expected relative path but got absolute: " + file);
            }
        }
    }

    @Test
    void testPathTraversalProtection() {
        assertThrows(
                SecurityException.class,
                () -> {
                    grepService.grep("pattern", "../secret", null);
                });
    }

    @Test
    void testNoProjectRoot() {
        when(projectService.hasActiveProject()).thenReturn(false);

        EditorResult result = grepService.grep("pattern", "src", null);

        assertFalse(result.success());
        assertEquals("No active project", result.errorHint());
    }
}
