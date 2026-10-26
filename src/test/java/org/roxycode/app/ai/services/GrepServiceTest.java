package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
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
        lenient().when(projectService.resolveRelativePath(any())).thenCallRealMethod();
        grepService = new GrepService(projectService);
    }

    @Test
    void testGrepSuccessAndRelativePaths() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "hello world");

        try (MockedStatic<ProcessRunner> runnerMock = mockStatic(ProcessRunner.class)) {
            runnerMock
                    .when(() -> ProcessRunner.run(anyList(), any(), anyBoolean()))
                    .thenReturn(
                            new ProcessResult(
                                    0, testFile + ":1:hello world" + (char) 10, "", false));

            EditorResult result = grepService.grep("hello world", ".", "*.txt");

            assertTrue(result.success());
            assertTrue(result.content().contains("test.txt:1:hello world"));
            assertFalse(result.content().startsWith(tempDir.toAbsolutePath().toString()));
        }
    }

    static Stream<Arguments> grepCommandVariants() {
        String contextOutput =
                String.join(
                        String.valueOf((char) 10),
                        "src/Sample.java-1-line 1 before",
                        "src/Sample.java-2-line 2 before",
                        "src/Sample.java:3:@AgentDoc",
                        "src/Sample.java-4-public void targetMethod() {");
        return Stream.of(
                Arguments.of(
                        (Function<GrepService, EditorResult>)
                                s -> s.grepWithContext("@AgentDoc", "src", "*.java", 2),
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
                                "src"),
                        contextOutput,
                        "src/Sample.java:3:@AgentDoc"),
                Arguments.of(
                        (Function<GrepService, EditorResult>)
                                s -> s.grepWithContext("@AgentDoc", 50),
                        List.of(
                                "rg",
                                "-n",
                                "-C",
                                "20",
                                "--no-heading",
                                "--color=never",
                                "--",
                                "@AgentDoc",
                                "."),
                        contextOutput,
                        "line 1 before"),
                Arguments.of(
                        (Function<GrepService, EditorResult>)
                                s -> s.grepWithContext("@AgentDoc", "src", 3),
                        List.of(
                                "rg",
                                "-n",
                                "-C",
                                "3",
                                "--no-heading",
                                "--color=never",
                                "--",
                                "@AgentDoc",
                                "src"),
                        contextOutput,
                        "src/Sample.java:3:@AgentDoc"),
                Arguments.of(
                        (Function<GrepService, EditorResult>)
                                s -> s.grepLiteral("std::vector<int>"),
                        List.of(
                                "rg",
                                "--vimgrep",
                                "--no-heading",
                                "--color=never",
                                "-F",
                                "--",
                                "std::vector<int>",
                                "."),
                        "src/Code.cpp:1:1:std::vector<int>",
                        "std::vector<int>"),
                Arguments.of(
                        (Function<GrepService, EditorResult>)
                                s -> s.grepLiteral("foo(bar[0])", "src"),
                        List.of(
                                "rg",
                                "--vimgrep",
                                "--no-heading",
                                "--color=never",
                                "-F",
                                "--",
                                "foo(bar[0])",
                                "src"),
                        "src/Code.cpp:1:1:foo(bar[0])",
                        "foo(bar[0])"),
                Arguments.of(
                        (Function<GrepService, EditorResult>)
                                s -> s.grepLiteral("-Dtest=MyTest", "src", "*.cpp"),
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
                                "src"),
                        "src/Code.cpp:1:1:-Dtest=MyTest",
                        "-Dtest=MyTest"),
                Arguments.of(
                        (Function<GrepService, EditorResult>) s -> s.grepIgnoreCase("fixme"),
                        List.of(
                                "rg",
                                "--vimgrep",
                                "--no-heading",
                                "--color=never",
                                "-i",
                                "--",
                                "fixme",
                                "."),
                        "src/Notes.java:1:1:// FIXME: urgent",
                        "FIXME: urgent"),
                Arguments.of(
                        (Function<GrepService, EditorResult>)
                                s -> s.grepIgnoreCase("urgent", "src"),
                        List.of(
                                "rg",
                                "--vimgrep",
                                "--no-heading",
                                "--color=never",
                                "-i",
                                "--",
                                "urgent",
                                "src"),
                        "src/Notes.java:1:1:// FIXME: urgent",
                        "FIXME: urgent"),
                Arguments.of(
                        (Function<GrepService, EditorResult>)
                                s -> s.grepIgnoreCase("urgent", "src", "*.java"),
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
                                "src"),
                        "src/Notes.java:1:1:// FIXME: urgent",
                        "FIXME: urgent"));
    }

    @ParameterizedTest
    @MethodSource("grepCommandVariants")
    void testGrepCommandVariantsAndOutput(
            Function<GrepService, EditorResult> invocation,
            List<String> expectedCommand,
            String sampleStdout,
            String expectedSnippet)
            throws IOException {
        Files.createDirectories(tempDir.resolve("src"));

        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            runner.when(() -> ProcessRunner.run(anyList(), eq(tempDir), eq(true)))
                    .thenReturn(new ProcessResult(0, sampleStdout, "", false));

            EditorResult result = invocation.apply(grepService);
            assertTrue(result.success());
            assertTrue(result.content().contains(expectedSnippet));
            runner.verify(() -> ProcessRunner.run(eq(expectedCommand), eq(tempDir), eq(true)));
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
    void testListFilesReturnsProjectRelativePaths() throws IOException {
        Path subDir = tempDir.resolve("sub");
        Files.createDirectories(subDir);
        Path rootFile = tempDir.resolve("root.txt");
        Path nestedFile = subDir.resolve("nested.txt");
        Files.writeString(rootFile, "root");
        Files.writeString(nestedFile, "nested");

        try (MockedStatic<ProcessRunner> runnerMock = mockStatic(ProcessRunner.class)) {
            runnerMock
                    .when(() -> ProcessRunner.run(anyList(), any(), anyBoolean()))
                    .thenReturn(
                            new ProcessResult(
                                    0,
                                    rootFile + String.valueOf((char) 10) + nestedFile + (char) 10,
                                    "",
                                    false));

            List<String> files = grepService.listFiles(null);

            assertTrue(files.contains("root.txt"));
            assertTrue(files.contains("sub/nested.txt"));
            for (String file : files) {
                assertFalse(
                        Path.of(file).isAbsolute(),
                        "Expected relative path but got absolute: " + file);
                assertFalse(file.startsWith(tempDir.toAbsolutePath().toString()));
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
