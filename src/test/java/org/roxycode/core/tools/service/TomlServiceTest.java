package org.roxycode.core.tools.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.core.Sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TomlServiceTest {

    @TempDir
    Path tempDir;

    private Sandbox sandbox;
    private TomlService tomlService;

    @BeforeEach
    void setUp() {
        sandbox = new Sandbox();
        sandbox.setRoot(tempDir.toString());
        tomlService = new TomlService();
    }

    @Test
    void readWrite() throws Exception {
        String filename = "test.toml";
        Path tempFile = tempDir.resolve(filename);
        
        ObjectMapper mapper = new TomlMapper();
        JsonNode expected = mapper.createObjectNode().put("foo", "bar");

        tomlService.write(tempFile, expected);
        JsonNode actual = tomlService.read(tempFile);
        
        assertEquals(expected, actual);
    }
}
