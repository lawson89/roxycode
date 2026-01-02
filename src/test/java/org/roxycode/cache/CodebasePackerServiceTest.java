package org.roxycode.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlFactory;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class CodebasePackerServiceTest {

    @Inject
    CodebasePackerService service;

    @TempDir
    Path tempDir;

    @Test
    void testPackCodebaseToString_HappyPath() throws IOException {
        // --- 1. SETUP ---

        // A. Create Source File (Java)
        Path srcDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(srcDir);
        Path javaFile = srcDir.resolve("App.java");
        Files.writeString(javaFile, "package com.example;\npublic class App {}");

        // B. Create Config File (JSON - "application/json" whitelist test)
        Path resourcesDir = tempDir.resolve("src/main/resources");
        Files.createDirectories(resourcesDir);
        Path jsonFile = resourcesDir.resolve("config.json");
        Files.writeString(jsonFile, "{ \"version\": \"1.0.0\" }");

        // C. Create Excluded File (.git)
        Path gitDir = tempDir.resolve(".git");
        Files.createDirectories(gitDir);
        Files.writeString(gitDir.resolve("config"), "[core] repositoryformatversion = 0");

        // D. Create Binary File (Simulated Image)
        Path imgDir = tempDir.resolve("assets");
        Files.createDirectories(imgDir);
        Path imgFile = imgDir.resolve("logo.png");
        byte[] binaryData = new byte[20];
        new Random().nextBytes(binaryData);
        Files.write(imgFile, binaryData);


        // --- 2. EXECUTE ---
        List<String> exclusions = List.of("**/.git/**", "**/target/**");
        String tomlOutput = service.packCodebaseToString(tempDir, exclusions, "junit-user");


        // --- 3. VERIFY ---
        assertNotNull(tomlOutput);
        System.out.println("DEBUG TOML Output:\n" + tomlOutput);

        // Deserialize to validate structure
        ObjectMapper tomlMapper = new ObjectMapper(new TomlFactory());
        CodebaseCache cache = tomlMapper.readValue(tomlOutput, CodebaseCache.class);

        // Assert Metadata
        assertEquals("junit-user", cache.user());
        assertNotNull(cache.generatedAt());
        assertTrue(cache.projectRoot().contains(tempDir.toString()));

        // Assert Files (Should have 2: App.java and config.json)
        List<CachedFile> files = cache.files();
        assertEquals(2, files.size(), "Should only contain App.java and config.json");

        // Verify App.java
        Optional<CachedFile> javaCached = files.stream()
                .filter(f -> f.path().endsWith("App.java"))
                .findFirst();
        assertTrue(javaCached.isPresent());
        assertTrue(javaCached.get().mimeType().contains("java"), "Mime type should be java source");
        assertTrue(javaCached.get().content().contains("public class App"));

        // Verify config.json
        Optional<CachedFile> jsonCached = files.stream()
                .filter(f -> f.path().endsWith("config.json"))
                .findFirst();
        assertTrue(jsonCached.isPresent());

        // Verify Exclusions
        boolean hasGit = files.stream().anyMatch(f -> f.path().contains(".git"));
        boolean hasPng = files.stream().anyMatch(f -> f.path().endsWith(".png"));
        assertFalse(hasGit, ".git folder should be excluded");
        assertFalse(hasPng, "Binary .png file should be excluded");
    }

    @Test
    void testPackCodebaseToFile() throws IOException {
        // --- 1. SETUP ---
        Path txtFile = tempDir.resolve("readme.txt");
        Files.writeString(txtFile, "Just a readme.");

        Path outputDir = tempDir.resolve("output");
        Files.createDirectories(outputDir);
        Path targetFile = outputDir.resolve("cache.toml");

        // --- 2. EXECUTE ---
        service.packCodebaseToFile(
                tempDir,
                List.of("**/output/**"), // Exclude the output folder itself!
                "file-writer-user",
                targetFile
        );

        // --- 3. VERIFY ---
        assertTrue(Files.exists(targetFile), "Target file should exist");
        assertTrue(Files.size(targetFile) > 0, "Target file should not be empty");

        // Read it back to ensure valid TOML
        ObjectMapper tomlMapper = new ObjectMapper(new TomlFactory());
        CodebaseCache cache = tomlMapper.readValue(targetFile.toFile(), CodebaseCache.class);

        assertEquals("file-writer-user", cache.user());
        assertTrue(cache.files().stream().anyMatch(f -> f.path().equals("readme.txt")));
    }

}
