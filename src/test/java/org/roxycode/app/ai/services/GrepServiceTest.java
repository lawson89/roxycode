package org.roxycode.app.ai.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

    private static final String NL = "\n";

    @TempDir
    Path tempDir;

    private ProjectService projectService;
    private FileReadService fileReadService;
    private GrepService grepService;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);
        lenient().when(projectService.resolvePath(any())).thenCallRealMethod();
        lenient().when(projectService.resolveReadPath(any())).thenCallRealMethod();
        lenient().when(projectService.resolveRelativePath(any())).thenCallRealMethod();
        lenient().when(projectService.resolveSearchTarget(any())).thenCallRealMethod();
        lenient().when(projectService.findReferenceFolderMount(any())).thenCallRealMethod();
        fileReadService = new FileReadService(projectService);
        grepService = new GrepService(projectService, fileReadService);
    }

    private static Arguments rgCase(Function<GrepService, EditorResult> fn, String cmd, String out, String expected) {
        return Arguments.of(fn, List.of(cmd.split(" ")), out, expected);
    }

    @Test
    void testGrepSuccessAndRelativePaths() throws IOException {
        Files.writeString(tempDir.resolve("test.txt"), "hello world");
        try (MockedStatic<ProcessRunner> runnerMock = mockStatic(ProcessRunner.class)) {
            runnerMock.when(() -> ProcessRunner.run(anyList(), any(), anyBoolean()))
                    .thenReturn(new ProcessResult(0, "./test.txt:1:hello world" + NL, "", false));
            EditorResult result = grepService.grep("hello world", ".", "*.txt");
            assertTrue(result.success());
            assertTrue(result.content().contains("test.txt:1:hello world"));
            assertFalse(result.content().startsWith("./"));
        }
    }

    static Stream<Arguments> grepCommandVariants() {
        String ctxOut = String.join(NL, "src/Sample.java-1-line 1 before", "src/Sample.java-2-line 2 before",
                "src/Sample.java:3:@AgentDoc", "src/Sample.java-4-public void targetMethod() {");
        String fixOut = "src/Notes.java:1:1:// FIXME: urgent";
        return Stream.of(
                rgCase(s -> s.grepWithContext("@AgentDoc", "src", "*.java", 2),
                        "rg -n -C 2 --no-heading --color=never -g *.java -- @AgentDoc src", ctxOut,
                        "src/Sample.java:3:@AgentDoc"),
                rgCase(s -> s.grepWithContext("@AgentDoc", 50), "rg -n -C 20 --no-heading --color=never -- @AgentDoc .",
                        ctxOut, "line 1 before"),
                rgCase(s -> s.grepLiteral("std::vector<int>"),
                        "rg --vimgrep --no-heading --color=never -F -- std::vector<int> .",
                        "src/Code.cpp:1:1:std::vector<int>", "std::vector<int>"),
                rgCase(s -> s.grepLiteral("-Dtest=MyTest", "src", "*.cpp"),
                        "rg --vimgrep --no-heading --color=never -F -g *.cpp -- -Dtest=MyTest src",
                        "src/Code.cpp:1:1:-Dtest=MyTest", "-Dtest=MyTest"),
                rgCase(s -> s.grepIgnoreCase("fixme"), "rg --vimgrep --no-heading --color=never -i -- fixme .", fixOut,
                        "FIXME: urgent"),
                rgCase(s -> s.grepIgnoreCase("urgent", "src", "*.java"),
                        "rg --vimgrep --no-heading --color=never -i -g *.java -- urgent src", fixOut, "FIXME: urgent"));
    }

    @ParameterizedTest
    @MethodSource("grepCommandVariants")
    void testGrepCommandVariantsAndOutput(Function<GrepService, EditorResult> invocation, List<String> expectedCommand,
            String sampleStdout, String expectedSnippet) throws IOException {
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
                manyLines.append(NL);
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
        Files.writeString(tempDir.resolve("root.txt"), "root");
        Files.writeString(Files.createDirectories(tempDir.resolve("sub")).resolve("nested.txt"), "nested");
        try (MockedStatic<ProcessRunner> runnerMock = mockStatic(ProcessRunner.class)) {
            runnerMock.when(() -> ProcessRunner.run(anyList(), any(), anyBoolean()))
                    .thenReturn(new ProcessResult(0, "./root.txt" + NL + "sub\\nested.txt" + NL, "", false));
            List<String> files = grepService.listFiles(null);
            assertTrue(files.containsAll(List.of("root.txt", "sub/nested.txt")));
            for (String file : files) {
                assertFalse(Path.of(file).isAbsolute());
                assertFalse(file.startsWith("./"));
            }
        }
    }

    @Test
    void testFindMatchingFilesAndReadMatchingFiles() throws IOException {
        Path srcDir = Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(srcDir.resolve("One.java"), "class One {}");
        Files.writeString(srcDir.resolve("Two.java"), "class Two {}");

        assertTrue(grepService.findMatchingFiles("").isEmpty());
        assertTrue(grepService.findMatchingFiles(null).isEmpty());

        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            String rawOut = String.join(NL, "./src/One.java", "src\\Two.java", "src/One.java") + NL;
            runner.when(() -> ProcessRunner.run(anyList(), eq(tempDir), eq(true)))
                    .thenReturn(new ProcessResult(0, rawOut, "", false));

            List<String> matched = grepService.findMatchingFiles("class", "src", "*.java");
            assertEquals(List.of("src/One.java", "src/Two.java"), matched);
            runner.verify(() -> ProcessRunner.run(
                    eq(List.of("rg", "-l", "--color=never", "-g", "*.java", "--", "class", "src")), eq(tempDir),
                    eq(true)));

            Map<String, String> contents = grepService.readMatchingFiles("class", "src", "*.java");
            assertEquals(2, contents.size());
            assertEquals("class One {}", contents.get("src/One.java"));
            assertEquals("class Two {}", contents.get("src/Two.java"));
        }

        when(projectService.hasActiveProject()).thenReturn(false);
        EditorResult noProjectResult = grepService.grep("pattern", "src", null);
        assertFalse(noProjectResult.success());
        assertEquals("No active project", noProjectResult.errorHint());
        assertTrue(grepService.findMatchingFiles("pattern").isEmpty());
        assertTrue(grepService.readMatchingFiles("pattern").isEmpty());
    }

    @Test
    void testReadMatchingFilesCapsAtMaxFiles() throws IOException {
        StringBuilder stdout = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            String name = "File" + i + ".java";
            Files.writeString(tempDir.resolve(name), "content " + i);
            stdout.append(name).append(NL);
        }
        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            runner.when(() -> ProcessRunner.run(anyList(), eq(tempDir), eq(true)))
                    .thenReturn(new ProcessResult(0, stdout.toString(), "", false));

            Map<String, String> contents = grepService.readMatchingFiles("content");
            assertEquals(GrepService.MAX_READ_MATCHING_FILES + 1, contents.size());
            assertEquals("content 1", contents.get("File1.java"));
            assertEquals("content 10", contents.get("File10.java"));
            assertFalse(contents.containsKey("File11.java"));
            assertTrue(contents.get(FileReadService.TRUNCATED_KEY)
                    .contains("Matched 12 files; showing first 10 files (2 more matching file(s) omitted)."));
        }
    }

    @Test
    void testGrepWordMatchesUsesWordBoundaryOrLiteralFlag() {
        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            runner.when(() -> ProcessRunner.run(anyList(), eq(tempDir), eq(false)))
                    .thenReturn(new ProcessResult(0, "src/App.java:10:5:app.executeJexl();" + NL, "", false));

            List<String> wordHits = grepService.grepWordMatches("executeJexl", "src", true);
            assertEquals(List.of("src/App.java:10:5:app.executeJexl();"), wordHits);
            runner.verify(() -> ProcessRunner.run(
                    eq(List.of("rg", "--vimgrep", "--no-heading", "--color=never", "-w", "--", "executeJexl", "src")),
                    eq(tempDir), eq(false)));

            grepService.grepWordMatches("operator+", ".", false);
            runner.verify(() -> ProcessRunner.run(
                    eq(List.of("rg", "--vimgrep", "--no-heading", "--color=never", "-F", "--", "operator+", ".")),
                    eq(tempDir), eq(false)));
        }
    }

    @Test
    void testReferenceFolderGrepListFilesFindMatchingAndReadMatchingPrefixOutputsWithAlias() throws IOException {
        Path refRoot = Files.createDirectories(tempDir.resolve("ext-billing"));
        Path refSrc = Files.createDirectories(refRoot.resolve("src"));
        Files.writeString(refSrc.resolve("Invoice.java"), "public class Invoice {}");
        ProjectService.ReferenceFolderMount mount = new ProjectService.ReferenceFolderMount("billing-service",
                refRoot.toString(), refRoot, false);
        when(projectService.getReferenceFolderMounts()).thenReturn(List.of(mount));

        try (MockedStatic<ProcessRunner> runner = mockStatic(ProcessRunner.class)) {
            runner.when(() -> ProcessRunner.run(eq(List.of("rg", "--files", "--color=never")), eq(refRoot), eq(false)))
                    .thenReturn(new ProcessResult(0, "./src/Invoice.java" + NL, "", false));
            assertEquals(List.of("@billing-service/src/Invoice.java"), grepService.listFiles("@billing-service"));

            runner.when(() -> ProcessRunner.run(
                    eq(List.of("rg", "-l", "--color=never", "-g", "*.java", "--", "Invoice", "src")), eq(refRoot),
                    eq(true))).thenReturn(new ProcessResult(0, "src/Invoice.java" + NL, "", false));

            List<String> matched = grepService.findMatchingFiles("Invoice", "@billing-service/src", "*.java");
            assertEquals(List.of("@billing-service/src/Invoice.java"), matched);

            Map<String, String> readMatched = grepService.readMatchingFiles("Invoice", "@billing-service/src",
                    "*.java");
            assertEquals(Map.of("@billing-service/src/Invoice.java", "public class Invoice {}"), readMatched);

            String bs = String.valueOf((char) 92);
            String snippetWithSpecialChars = "public class Invoice { String p = '" + bs + "s+ https://x.io/../y'; }";
            runner.when(() -> ProcessRunner.run(eq(List.of("rg", "--vimgrep", "--no-heading", "--color=never", "-g",
                    "*.java", "--", "Invoice", "src")), eq(refRoot), eq(true)))
                    .thenReturn(new ProcessResult(0, "./src" + bs + "Invoice.java:1:14:" + snippetWithSpecialChars + NL,
                            "", false));
            EditorResult grepRes = grepService.grep("Invoice", "@billing-service/src", "*.java");
            assertTrue(grepRes.success());
            assertEquals("@billing-service/src/Invoice.java:1:14:" + snippetWithSpecialChars, grepRes.content());

            runner.when(() -> ProcessRunner.run(
                    eq(List.of("rg", "--vimgrep", "--no-heading", "--color=never", "-w", "--", "Invoice", ".")),
                    eq(refRoot), eq(false))).thenReturn(
                            new ProcessResult(0, "./src/Invoice.java:1:14:public class Invoice {}" + NL, "", false));
            assertEquals(List.of("@billing-service/src/Invoice.java:1:14:public class Invoice {}"),
                    grepService.grepWordMatches("Invoice", "@billing-service", true));
        }
    }

}