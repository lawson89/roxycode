package org.roxycode.core.tools.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.core.Sandbox;

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
        sandbox.setRoot(tempDir);
        tomlService = new TomlService(sandbox);
    }

    @Test
    void readWrite() throws Exception {
        String filename = "test.toml";
        
        ObjectMapper mapper = new TomlMapper();
        JsonNode expected = mapper.createObjectNode().put("foo", "bar");

        tomlService.write(filename, expected);
        JsonNode actual = tomlService.read(filename);
        
        assertEquals(expected, actual);
    }
}
