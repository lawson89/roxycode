package org.roxycode.app.service;

import org.junit.jupiter.api.Test;
import org.roxycode.app.model.SystemTool;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class SystemToolServiceTest {

    @Test
    void testDetectTools() throws Exception {
        SystemToolService service = new SystemToolService();
        CompletableFuture<List<SystemTool>> future = service.detectTools();
        List<SystemTool> tools = future.get();

        assertNotNull(tools);
        assertFalse(tools.isEmpty());
        
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("Java")));
        
        for (SystemTool tool : tools) {
            assertNotNull(tool.name());
            assertNotNull(tool.status());
            assertNotNull(tool.version());
            assertNotNull(tool.description());
        }
    }
}