package org.roxycode.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.core.RoxyProjectService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class CodebasePackerServiceTest {

    @Test
    void testEstimateTokenCount(@TempDir Path tempDir) throws IOException {
        CodebasePackerService service = new CodebasePackerService(mock(RoxyProjectService.class));
        
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "12345678"); // 8 bytes
        
        assertEquals(2, service.estimateTokenCount(testFile));
        
        assertEquals(0, service.estimateTokenCount(null));
        assertEquals(0, service.estimateTokenCount(tempDir.resolve("non-existent")));
    }
}
