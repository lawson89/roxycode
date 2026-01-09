package org.roxycode.core.analysis;

import org.junit.jupiter.api.Test;
import com.github.javaparser.ParserConfiguration;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JavaAnalysisServiceNewTest {
    private JavaSourceAnalysisService createService() {
        JavaSourceAnalysisService service = new JavaSourceAnalysisService();
        service.parserConfiguration = new com.github.javaparser.ParserConfiguration()
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_21);
        return service;
    }

    @Test
    void testGenerateSkeletonToFile(@TempDir Path tempDir) throws IOException {
        JavaSourceAnalysisService service = createService();
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        
        Path javaFile = sourceDir.resolve("Hello.java");
        Files.writeString(javaFile, "public class Hello { public void sayHi() {} }");
        
        Path outputFile = tempDir.resolve("output.txt");
        
        service.generateSkeletonToFile(sourceDir, outputFile);
        
        assertTrue(Files.exists(outputFile));
        String content = Files.readString(outputFile);
        assertTrue(content.contains("class Hello"));
        assertTrue(content.contains("sayHi()"));
    }

    @Test
    void testGenerateSkeletonToString(@TempDir Path tempDir) throws IOException {
        JavaSourceAnalysisService service = createService();
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        
        Path javaFile = sourceDir.resolve("Hello.java");
        Files.writeString(javaFile, "public class Hello { public void sayHi() {} }");
        
        String content = service.generateSkeletonToString(sourceDir);
        
        assertNotNull(content);
        assertTrue(content.contains("class Hello"));
        assertTrue(content.contains("sayHi()"));
    }

    @Test
    void testJavadocInSkeleton(@TempDir Path tempDir) throws IOException {
        JavaSourceAnalysisService service = createService();
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);

        Path javaFile = sourceDir.resolve("Test.java");
        Files.writeString(javaFile, "package test;\n" +
            "/**\n" +
            " * Class doc.\n" +
            " * Line 2.\n" +
            " */\n" +
            "public class Test {\n" +
            "    /** Field doc. */\n" +
            "    public String name;\n" +
            "\n" +
            "    /**\n" +
            "     * Method doc.\n" +
            "     */\n" +
            "    public void run() {}\n" +
            "}");

        String content = service.generateSkeletonToString(sourceDir);

        assertTrue(content.contains("/**"));
        assertTrue(content.contains("* Class doc."));
        assertTrue(content.contains("* Line 2."));
        assertTrue(content.contains("*/"));
        assertTrue(content.contains("String name;"));
    }

    @Test
    void testTextBlockSupport(@TempDir Path tempDir) throws IOException {
        JavaSourceAnalysisService service = createService();
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);

        Path javaFile = sourceDir.resolve("TextBlock.java");
        Files.writeString(javaFile, "public class TextBlock { String s = \"\"\"\n        hello\n        \"\"\"; }");

        String content = service.generateSkeletonToString(sourceDir);
        assertTrue(content.contains("class TextBlock"));
    }

    @Test
    void testRecordSupport(@TempDir Path tempDir) throws IOException {
        JavaSourceAnalysisService service = createService();
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);

        Path javaFile = sourceDir.resolve("Point.java");
        Files.writeString(javaFile, "public record Point(int x, int y) { }");

        String content = service.generateSkeletonToString(sourceDir);
        assertTrue(content.contains("record Point"));
        assertTrue(content.contains("int x"));
        assertTrue(content.contains("int y"));
    }
}
