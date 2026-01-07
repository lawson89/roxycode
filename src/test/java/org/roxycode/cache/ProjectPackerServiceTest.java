package org.roxycode.cache;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.core.RoxyProjectService;
import org.roxycode.core.Sandbox;
import org.roxycode.core.analysis.JavaAnalysisService;
import org.roxycode.core.tools.service.BuildToolService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectPackerServiceTest {

    @Test
    void testEstimateTokenCount(@TempDir Path tempDir) throws IOException {
        ProjectPackerService service = new ProjectPackerService(mock(RoxyProjectService.class), mock(Sandbox.class), mock(BuildToolService.class), mock(JavaAnalysisService.class), null);

        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "12345678"); // 8 bytes

        assertEquals(2, service.estimateTokenCount(testFile));

        assertEquals(0, service.estimateTokenCount(null));
        assertEquals(0, service.estimateTokenCount(tempDir.resolve("non-existent")));
    }

    @Test
    void testPackCodebaseIncludesJavaSkeleton(@TempDir Path tempDir) throws IOException {
        Path root = tempDir.resolve("root");
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("src/main/java/Test.java"), "public class Test { public void hello() {} }");

        RoxyProjectService roxyProjectService = mock(RoxyProjectService.class);
        Path cacheDir = tempDir.resolve("cache");
        Files.createDirectories(cacheDir);
        when(roxyProjectService.getRoxyCacheDir()).thenReturn(cacheDir);

        Sandbox sandbox = mock(Sandbox.class);
        when(sandbox.getRoot()).thenReturn(root);

        BuildToolService buildToolService = mock(BuildToolService.class);
        when(buildToolService.getProjectSummary()).thenReturn("Summary");

        ProjectPackerService service = new ProjectPackerService(
                roxyProjectService,
                sandbox,
                buildToolService,
                new JavaAnalysisService(),
                new TomlMapper()
        );

        String result = service.packCodebaseToString(root);
//        System.out.println(result);

        assertTrue(result.contains("java_skeleton"));
        assertTrue(result.contains("class Test"));
        assertTrue(result.contains("void hello()"));
    }
}
