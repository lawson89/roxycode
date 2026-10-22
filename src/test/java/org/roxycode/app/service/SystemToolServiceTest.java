package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.roxycode.app.model.SystemTool;

class SystemToolServiceTest {

    @Test
    void testDetectToolsIncludesDockerAndJava() throws Exception {
        SystemToolService service = new SystemToolService();
        CompletableFuture<List<SystemTool>> future = service.detectTools();
        List<SystemTool> tools = future.get();

        assertNotNull(tools);
        assertFalse(tools.isEmpty());

        assertTrue(tools.stream().anyMatch(t -> t.name().equals("Java")));
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("Docker")));

        for (SystemTool tool : tools) {
            assertNotNull(tool.name());
            assertNotNull(tool.status());
            assertNotNull(tool.version());
            assertNotNull(tool.description());
        }

        Boolean dockerAvailable = service.isDockerAvailable().get();
        assertNotNull(dockerAvailable);
        assertEquals(service.checkDockerAvailable(), dockerAvailable);
    }
}
