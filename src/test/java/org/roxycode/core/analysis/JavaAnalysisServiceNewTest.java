
package org.roxycode.core.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JavaAnalysisServiceNewTest {

    @Test
    void testGenerateSkeletonToFile(@TempDir Path tempDir) throws IOException {
        JavaAnalysisService service = new JavaAnalysisService();
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
}
