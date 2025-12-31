package org.roxycode.core.tools.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.core.Sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildToolServiceTest {

    @TempDir
    Path tempDir;

    private BuildToolService buildToolService;
    private Sandbox sandbox;

    @BeforeEach
    void setUp() {
        sandbox = new Sandbox();
        sandbox.setRoot(tempDir.toString());
        buildToolService = new BuildToolService(sandbox);
    }

    @Test
    void testDetectMaven() throws IOException {
        Files.createFile(tempDir.resolve("pom.xml"));
        assertEquals(BuildToolService.BuildTool.MAVEN, buildToolService.detect());
    }

    @Test
    void testDetectGradle() throws IOException {
        Files.createFile(tempDir.resolve("build.gradle"));
        assertEquals(BuildToolService.BuildTool.GRADLE, buildToolService.detect());
    }

    @Test
    void testDetectGradleKotlin() throws IOException {
        Files.createFile(tempDir.resolve("build.gradle.kts"));
        assertEquals(BuildToolService.BuildTool.GRADLE, buildToolService.detect());
    }

    @Test
    void testDetectAnt() throws IOException {
        Files.createFile(tempDir.resolve("build.xml"));
        assertEquals(BuildToolService.BuildTool.ANT, buildToolService.detect());
    }

    @Test
    void testDetectUnknown() {
        assertEquals(BuildToolService.BuildTool.UNKNOWN, buildToolService.detect());
    }

    @Test
    void testResolveExecutableMavenNoWrapper() {
        assertEquals("mvn", buildToolService.resolveExecutable(BuildToolService.BuildTool.MAVEN, true));
        assertEquals("mvn", buildToolService.resolveExecutable(BuildToolService.BuildTool.MAVEN, false));
    }

    @Test
    void testResolveExecutableMavenWrapperWindows() throws IOException {
        Files.createFile(tempDir.resolve("mvnw.cmd"));
        assertEquals(tempDir.resolve("mvnw.cmd").toAbsolutePath().toString(), buildToolService.resolveExecutable(BuildToolService.BuildTool.MAVEN, true));
    }

    @Test
    void testResolveExecutableMavenWrapperUnix() throws IOException {
        Files.createFile(tempDir.resolve("mvnw"));
        assertEquals("./mvnw", buildToolService.resolveExecutable(BuildToolService.BuildTool.MAVEN, false));
    }

    @Test
    void testResolveExecutableGradleNoWrapper() {
        assertEquals("gradle", buildToolService.resolveExecutable(BuildToolService.BuildTool.GRADLE, true));
        assertEquals("gradle", buildToolService.resolveExecutable(BuildToolService.BuildTool.GRADLE, false));
    }

    @Test
    void testResolveExecutableGradleWrapperWindows() throws IOException {
        Files.createFile(tempDir.resolve("gradlew.bat"));
        assertEquals(tempDir.resolve("gradlew.bat").toAbsolutePath().toString(), buildToolService.resolveExecutable(BuildToolService.BuildTool.GRADLE, true));
    }

    @Test
    void testResolveExecutableGradleWrapperUnix() throws IOException {
        Files.createFile(tempDir.resolve("gradlew"));
        assertEquals("./gradlew", buildToolService.resolveExecutable(BuildToolService.BuildTool.GRADLE, false));
    }

    @Test
    void testResolveExecutableAnt() {
        assertEquals("ant.bat", buildToolService.resolveExecutable(BuildToolService.BuildTool.ANT, true));
        assertEquals("ant", buildToolService.resolveExecutable(BuildToolService.BuildTool.ANT, false));
    }

    @Test
    void testGetCompileCommandMaven() {
        List<String> command = buildToolService.getCompileCommand(BuildToolService.BuildTool.MAVEN);
        assertTrue(command.contains("clean"));
        assertTrue(command.contains("compile"));
    }

    @Test
    void testGetCompileCommandGradle() {
        List<String> command = buildToolService.getCompileCommand(BuildToolService.BuildTool.GRADLE);
        assertTrue(command.contains("classes"));
    }

    @Test
    void testGetCompileCommandAnt() {
        List<String> command = buildToolService.getCompileCommand(BuildToolService.BuildTool.ANT);
        assertTrue(command.contains("compile"));
    }

    @Test
    void testGetTestCommand() {
        List<String> command = buildToolService.getTestCommand(BuildToolService.BuildTool.MAVEN);
        assertTrue(command.contains("test"));
    }

    @Test
    void testCompileUnknown() {
        String result = buildToolService.compile();
        assertTrue(result.contains("Could not detect build tool"));
    }

    @Test
    void testRunTestsUnknown() {
        String result = buildToolService.runTests();
        assertTrue(result.contains("Could not detect build tool"));
    }
    @Test
    void testGetDependencyTreeCommandMaven() {
        List<String> command = buildToolService.getDependencyTreeCommand(BuildToolService.BuildTool.MAVEN);
        assertTrue(command.contains("dependency:tree"));
    }

    @Test
    void testGetDependencyTreeCommandGradle() {
        List<String> command = buildToolService.getDependencyTreeCommand(BuildToolService.BuildTool.GRADLE);
        assertTrue(command.contains("dependencies"));
    }

    @Test
    void testGetProjectStructureCommandGradle() {
        List<String> command = buildToolService.getProjectStructureCommand(BuildToolService.BuildTool.GRADLE);
        assertTrue(command.contains("projects"));
    }

    @Test
    void testGetProjectStructureCommandMaven() {
        List<String> command = buildToolService.getProjectStructureCommand(BuildToolService.BuildTool.MAVEN);
        assertTrue(command.isEmpty());
    }

    @Test
    void testGetEffectiveConfigCommandMaven() {
        List<String> command = buildToolService.getEffectiveConfigCommand(BuildToolService.BuildTool.MAVEN);
        assertTrue(command.contains("help:effective-pom"));
    }

    @Test
    void testGetEffectiveConfigCommandGradle() {
        List<String> command = buildToolService.getEffectiveConfigCommand(BuildToolService.BuildTool.GRADLE);
        assertTrue(command.contains("properties"));
    }

    @Test
    void testGetDependencyHealthCommandMaven() {
        List<String> command = buildToolService.getDependencyHealthCommand(BuildToolService.BuildTool.MAVEN);
        assertTrue(command.contains("dependency:analyze"));
    }

    @Test
    void testGetDependencyHealthCommandGradle() {
        List<String> command = buildToolService.getDependencyHealthCommand(BuildToolService.BuildTool.GRADLE);
        assertTrue(command.isEmpty());
    }
}
