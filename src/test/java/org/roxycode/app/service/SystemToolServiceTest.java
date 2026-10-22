package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.roxycode.app.model.SystemTool;
import org.roxycode.app.service.SystemToolService.DockerVersionInfo;

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

    @Test
    void testDetectDockerToolDetectedWithVersionAndApiVersion() {
        SystemToolService service =
                new SystemToolService(() -> new DockerVersionInfo("28.0.1", "1.48"), () -> true);

        SystemTool dockerTool = service.detectDockerTool();
        assertEquals("Docker", dockerTool.name());
        assertEquals("Detected", dockerTool.status());
        assertEquals("Docker 28.0.1 (API 1.48)", dockerTool.version());
        assertTrue(service.checkDockerAvailable());
    }

    @Test
    void testDetectDockerToolErrorOnApiVersionMismatchOrHandshakeFailure() {
        SystemToolService service =
                new SystemToolService(
                        () -> {
                            throw new IllegalStateException(
                                    "Could not connect to Docker",
                                    new RuntimeException(
                                            "client version 1.32 is too old. Minimum supported API version is 1.44"));
                        },
                        () -> false);

        SystemTool dockerTool = service.detectDockerTool();
        assertEquals("Docker", dockerTool.name());
        assertEquals("Error", dockerTool.status());
        assertTrue(dockerTool.version().contains("client version 1.32 is too old"));
        assertFalse(service.checkDockerAvailable());
    }

    @Test
    void testDetectDockerToolMissingWhenDockerNotInstalled() {
        SystemToolService service =
                new SystemToolService(
                        () -> {
                            throw new IllegalStateException(
                                    "Could not find a valid Docker environment. Please see logs and check configuration");
                        },
                        () -> false);

        SystemTool dockerTool = service.detectDockerTool();
        assertEquals("Docker", dockerTool.name());
        assertEquals("Missing", dockerTool.status());
        assertEquals("Not found", dockerTool.version());
        assertFalse(service.checkDockerAvailable());
    }

    @Test
    void testDetectDockerToolErrorWhenCliPresentButTestcontainersCannotConnect() {
        SystemToolService service =
                new SystemToolService(
                        () -> {
                            throw new IllegalStateException(
                                    "Could not find a valid Docker environment. Please see logs and check configuration");
                        },
                        () -> true);

        SystemTool dockerTool = service.detectDockerTool();
        assertEquals("Docker", dockerTool.name());
        assertEquals("Error", dockerTool.status());
        assertTrue(dockerTool.version().contains("Could not find a valid Docker environment"));
        assertFalse(service.checkDockerAvailable());
    }
}
