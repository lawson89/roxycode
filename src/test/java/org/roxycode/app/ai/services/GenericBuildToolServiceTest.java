package org.roxycode.app.ai.services;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.service.ProjectService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenericBuildToolServiceTest {

    @TempDir
    Path tempDir;

    private ProjectService projectService;
    private TomlMapper tomlMapper;
    private GenericBuildToolService buildToolService;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        tomlMapper = new TomlMapper();

        // In the original test, buildToolService was initialized BEFORE mocks were configured,
        // so ensureConfig() wasn't called in constructor.
        buildToolService = new GenericBuildToolService(projectService, tomlMapper);
        
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getCurrentProjectRoot()).thenReturn(tempDir);
    }

    @Test
    void testDetectNoConfig() {
        assertFalse(buildToolService.detect());
    }

    @Test
    void testDetectWithConfig() throws IOException {
        Files.createDirectories(tempDir.resolve(".roxycode"));
        Files.writeString(tempDir.resolve(".roxycode/build.toml"), "[build]\ncompile = \"echo 'hi'\"");
        assertTrue(buildToolService.detect());
    }

    @Test
    void testEnsureConfigAutoDetectionMaven() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>");
        
        // This will call ensureConfig() internally
        buildToolService.compile();
        
        Path configPath = tempDir.resolve(".roxycode/build.toml");
        assertTrue(Files.exists(configPath));
        String content = Files.readString(configPath);
        assertTrue(content.contains("compile"));
    }

    @Test
    void testCommandPlaceholders() throws IOException {
        Files.createDirectories(tempDir.resolve(".roxycode"));
        Files.writeString(tempDir.resolve(".roxycode/build.toml"), "[test]\nrun_single = \"echo {testName}\"");
        
        BuildResult result = buildToolService.runSingleTest("MyTest");
        assertTrue(result.log().contains("MyTest"));
    }
    
    @Test
    void testRegexParsing() throws IOException {
        Files.createDirectories(tempDir.resolve(".roxycode"));
        Files.writeString(tempDir.resolve(".roxycode/build.toml"), 
            "[build]\ncompile = \"echo '[ERROR] Compilation failed'\"\n" +
            "[parse]\nerror_regex = '(?m)^\\[ERROR\\] (.*)$'");
            
        BuildResult result = buildToolService.compile();
        assertEquals(1, result.errors().size());
        assertEquals("Compilation failed", result.errors().get(0));
    }
    @Test
    void testEnvironmentVariables() throws IOException {
        Files.createDirectories(tempDir.resolve(".roxycode"));
        Files.writeString(tempDir.resolve(".roxycode/build.toml"), 
            "[build]\n" +
            "compile = \"echo test\"\n" +
            "[env]\n" +
            "TEST_VAR = \"hello-env\"");
        
        BuildResult result = buildToolService.compile();
        assertTrue(result.success());
    }

}
