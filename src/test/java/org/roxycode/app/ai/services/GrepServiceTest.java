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

    private static Arguments rgCase(
            Function<GrepService, EditorResult> fn, String cmd, String out, String expected) {
        return Arguments.of(fn, List.of(cmd.split(" ")), out, expected);
    }

    @Test
    void testGrepSuccessAndRelativePaths() throws IOException {
        Path testFile = Files.writeString(tempDir.resolve("test.txt"), "hello world");
        try (MockedStatic<ProcessRunner> runnerMock = mockStatic(ProcessRunner.class)) {
            runnerMock
                    .when(() -> ProcessRunner.run(anyList(), any(), anyBoolean()))
                    .thenReturn(new ProcessResult(0, testFile + ":1:hello world\n", "", false));
            EditorResult result = grepService.grep("hello world", ".", "*.txt");
            assertTrue(result.success());
            assertTrue(result.content().contains("test.txt:1:hello world"));
            assertFalse(result.content().startsWith(tempDir.toAbsolutePath().toString()));
        }
    }

    static Stream<Arguments> grepCommandVariants() {
        String ctxOut =
                String.join(
                        "\n",
                        "src/Sample.java-1-line 1 before",
                        "src/Sample.java-2-line 2 before",
                        "src/Sample.java:3:@AgentDoc",
                        "src/Sample.java-4-public void targetMethod() {");
        String fixOut = "src/Notes.java:1:1:// FIXME: urgent";
        return Stream.of(
                rgCase(
                        s -> s.grepWithContext("@AgentDoc", "src", "*.java", 2),
                        "rg -n -C 2 --no-heading --color=never -g *.java -- @AgentDoc src",
                        ctxOut,
                        "src/Sample.java:3:@AgentDoc"),
                rgCase(
                        s -> s.grepWithContext("@AgentDoc", 50),
                        "rg -n -C 20 --no-heading --color=never -- @AgentDoc .",
                        ctxOut,
                        "line 1 before"),
                rgCase(
                        s -> s.grepWithContext("@AgentDoc", "src", 3),
                        "rg -n -C 3 --no-heading --color=never -- @AgentDoc src",
                        ctxOut,
                        "src/Sample.java:3:@AgentDoc"),
                rgCase(
                        s -> s.grepLiteral("std::vector<int>"),
                        "rg --vimgrep --no-heading --color=never -F -- std::vector<int> .",
                        "src/Code.cpp:1:1:std::vector<int>",
                        "std::vector<int>"),
                rgCase(
                        s -> s.grepLiteral("foo(bar[0])", "src"),
                        "rg --vimgrep --no-heading --color=never -F -- foo(bar[0]) src",
                        "src/Code.cpp:1:1:foo(bar[0])",
                        "foo(bar[0])"),
                rgCase(
                        s -> s.grepLiteral("-Dtest=MyTest", "src", "*.cpp"),
                        "rg --vimgrep --no-heading --color=never -F -g *.cpp -- -Dtest=MyTest src",
                        "src/Code.cpp:1:1:-Dtest=MyTest",
                        "-Dtest=MyTest"),
                rgCase(
                        s -> s.grepIgnoreCase("fixme"),
                        "rg --vimgrep --no-heading --color=never -i -- fixme .",
                        fixOut,
                        "FIXME: urgent"),
                rgCase(
                        s -> s.grepIgnoreCase("urgent", "src"),
                        "rg --vimgrep --no-heading --color=never -i -- urgent src",
                        fixOut,
                        "FIXME: urgent"),
                rgCase(
                        s -> s.grepIgnoreCase("urgent", "src", "*.java"),
                        "rg --vimgrep --no-heading --color=never -i -g *.java -- urgent src",
                        fixOut,
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
            if (i > 1) manyLines.append('\n');
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
        Path rootFile = Files.writeString(tempDir.resolve("root.txt"), "root");
        Path nestedFile =
                Files.writeString(
                        Files.createDirectories(tempDir.resolve("sub")).resolve("nested.txt"),
                        "nested");
        try (MockedStatic<ProcessRunner> runnerMock = mockStatic(ProcessRunner.class)) {
            runnerMock
                    .when(() -> ProcessRunner.run(anyList(), any(), anyBoolean()))
                    .thenReturn(
                            new ProcessResult(0, rootFile + "\n" + nestedFile + "\n", "", false));
            List<String> files = grepService.listFiles(null);
            assertTrue(files.containsAll(List.of("root.txt", "sub/nested.txt")));
            for (String file : files) {
                assertFalse(Path.of(file).isAbsolute());
                assertFalse(file.startsWith(tempDir.toAbsolutePath().toString()));
            }
        }
    }

    @Test
    void testPathTraversalProtection() {
        assertThrows(SecurityException.class, () -> grepService.grep("pattern", "../secret", null));
    }

    @Test
    void testNoProjectRoot() {
        when(projectService.hasActiveProject()).thenReturn(false);
        EditorResult result = grepService.grep("pattern", "src", null);
        assertFalse(result.success());
        assertEquals("No active project", result.errorHint());
    }
}
