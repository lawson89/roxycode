package org.roxycode.core.tools.service;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.inject.Provider;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.core.Sandbox;
import org.roxycode.core.tools.LLMDoc;
import org.roxycode.core.tools.ScriptService;
import org.roxycode.core.tools.ScriptServiceRegistry;

import java.nio.charset.StandardCharsets;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Service for interacting with build tools (Maven, Gradle, Ant).
 * Provides methods for compilation, testing, and project information.
 */
@ScriptService("buildToolService")
@Singleton
@LLMDoc("Service for interacting with build tools (Maven, Gradle, Ant). Provides methods for compilation, testing, and project information.")
public class BuildToolService {

    private final Sandbox sandbox;
    private final FileSystemService fileSystemService;
    private final Provider<ScriptServiceRegistry> scriptServiceRegistry;

    /**
     * Supported build tools.
     */
    public enum BuildTool {
        MAVEN, GRADLE, ANT, UNKNOWN
    }

    @Inject
    public BuildToolService(Sandbox sandbox, FileSystemService fileSystemService, Provider<ScriptServiceRegistry> scriptServiceRegistry) {
        this.sandbox = sandbox;
        this.fileSystemService = fileSystemService;
        this.scriptServiceRegistry = scriptServiceRegistry;
    }

    /**
     * Detects the current build tool by checking for common build files in the project root.
     *
     * @return The detected build tool.
     */
    @LLMDoc("Detects the current build tool")
    public BuildTool detect() {
        Path projectRoot = sandbox.getRoot();
        if (Files.exists(projectRoot.resolve("pom.xml"))) {
            return BuildTool.MAVEN;
        } else // Gradle can use Groovy (.gradle) or Kotlin (.gradle.kts) DSL
            if (Files.exists(projectRoot.resolve("build.gradle")) || Files.exists(projectRoot.resolve("build.gradle.kts"))) {
                return BuildTool.GRADLE;
            } else if (Files.exists(projectRoot.resolve("build.xml"))) {
                return BuildTool.ANT;
            }
        return BuildTool.UNKNOWN;
    }

    /**
     * Compiles the current project using the detected build tool.
     *
     * @return The output of the compilation process.
     */
    @LLMDoc("Compiles the current project")
    public String compile() {
        BuildTool tool = detect();
        if (tool == BuildTool.UNKNOWN) {
            return "❌ Could not detect build tool (no pom.xml, build.gradle, or build.xml found).";
        }
        return executeCommand(getCompileCommand(tool), "Compilation");
    }

    /**
     * Runs all tests for the current project using the detected build tool.
     *
     * @return The output of the test execution process.
     */
    @LLMDoc("Runs the tests for the current project")
    public String runTests() {
        BuildTool tool = detect();
        if (tool == BuildTool.UNKNOWN) {
            return "❌ Could not detect build tool (no pom.xml, build.gradle, or build.xml found).";
        }
        return executeCommand(getTestCommand(tool), "Tests");
    }

    /**
     * Runs a single test for the current project.
     *
     * @param testName The name of the test to run (e.g., 'ClassName' or 'ClassName#methodName').
     * @return The output of the test execution process.
     */
    @LLMDoc("Runs a single test for the current project. Example: runTest('ClassName') or runTest('ClassName#methodName')")
    public String runTest(String testName) {
        BuildTool tool = detect();
        if (tool == BuildTool.UNKNOWN) {
            return "❌ Could not detect build tool (no pom.xml, build.gradle, or build.xml found).";
        }
        List<String> command = getSingleTestCommand(tool, testName);
        if (command.isEmpty()) {
            return "❌ Single test execution is not supported for " + tool;
        }
        return executeCommand(command, "Test " + testName);
    }

    /**
     * Runs the verify goal (Maven) or check task (Gradle) for the project.
     * This typically includes unit tests, integration tests, and static analysis like SpotBugs.
     *
     * @return The output of the verification process.
     */
    @LLMDoc("Runs the full build lifecycle (verify/check) including static analysis")
    public String verify() {
        BuildTool tool = detect();
        if (tool == BuildTool.UNKNOWN) {
            return "❌ Could not detect build tool (no pom.xml, build.gradle, or build.xml found).";
        }
        return executeCommand(getVerifyCommand(tool), "Verification");
    }

    List<String> getSingleTestCommand(BuildTool tool, String testName) {
        List<String> command = new ArrayList<>();
        String executable = resolveExecutable(tool);
        if (executable.isEmpty()) return command;

        command.add(executable);
        switch (tool) {
            case MAVEN:
                command.addAll(Arrays.asList("test", "-Dtest=" + testName));
                break;
            case GRADLE:
                command.addAll(Arrays.asList("test", "--tests", testName));
                break;
            default:
                return new ArrayList<>();
        }
        return command;
    }

    List<String> getCompileCommand(BuildTool tool) {
        List<String> command = new ArrayList<>();
        command.add(resolveExecutable(tool));
        switch (tool) {
            case MAVEN:
                command.addAll(Arrays.asList("clean", "compile"));
                break;
            case GRADLE:
                command.add("classes");
                break;
            case ANT:
                command.add("compile");
                break;
        }
        return command;
    }

    List<String> getTestCommand(BuildTool tool) {
        List<String> command = new ArrayList<>();
        command.add(resolveExecutable(tool));
        command.add("test");
        return command;
    }

    List<String> getVerifyCommand(BuildTool tool) {
        List<String> command = new ArrayList<>();
        command.add(resolveExecutable(tool));
        switch (tool) {
            case MAVEN:
                command.add("verify");
                break;
            case GRADLE:
                command.add("check");
                break;
            case ANT:
                command.add("verify");
                break;
        }
        return command;
    }

    String resolveExecutable(BuildTool tool) {
        return resolveExecutable(tool, System.getProperty("os.name").toLowerCase().contains("win"));
    }

    /**
     * Returns the name of the operating system.
     *
     * @return The operating system name in lowercase.
     */
    @LLMDoc("Returns the operating system name")
    public String getOperatingSystem() {
        return System.getProperty("os.name").toLowerCase();
    }

    String resolveExecutable(BuildTool tool, boolean isWindows) {
        Path projectRoot = sandbox.getRoot();
        switch (tool) {
            case MAVEN:
                if (isWindows) {
                    Path wrapper = projectRoot.resolve("mvnw.cmd");
                    return Files.exists(wrapper) ? wrapper.toAbsolutePath().toString() : "mvn";
                } else {
                    Path wrapper = projectRoot.resolve("mvnw");
                    return Files.exists(wrapper) ? "./mvnw" : "mvn";
                }
            case GRADLE:
                if (isWindows) {
                    Path wrapper = projectRoot.resolve("gradlew.bat");
                    return Files.exists(wrapper) ? wrapper.toAbsolutePath().toString() : "gradle";
                } else {
                    Path wrapper = projectRoot.resolve("gradlew");
                    return Files.exists(wrapper) ? "./gradlew" : "gradle";
                }
            case ANT:
                return isWindows ? "ant.bat" : "ant";
            default:
                return "";
        }
    }

    private String executeCommand(List<String> command, String context) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(sandbox.getRoot().toFile());
        processBuilder.redirectErrorStream(true);
        StringBuilder output = new StringBuilder();
        try {
            Process process = processBuilder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            int exitCode = process.waitFor();
            String outputStr = StringUtils.abbreviate(output.toString(), 50000); // Limit output to 50,000 characters
            if (exitCode == 0) {
                return "✅ " + context + " SUCCESSFUL\n" + outputStr;
            } else {
                return "❌ " + context + " FAILED (Exit Code: " + exitCode + ")\n" + outputStr;
            }
        } catch (IOException | InterruptedException e) {
            return "❌ ERROR executing " + context + ": " + String.join(" ", command) + "\n" + e.getMessage();
        }
    }

    /**
     * Returns the dependency tree for the current project.
     *
     * @return The dependency tree output.
     */
    @LLMDoc("Returns the dependency tree for the current project")
    public String getDependencyTree() {
        BuildTool tool = detect();
        if (tool == BuildTool.UNKNOWN) {
            return "❌ Could not detect build tool.";
        }
        List<String> command = getDependencyTreeCommand(tool);
        if (command.isEmpty()) {
            return "❌ Dependency Tree is not available for " + tool;
        }
        return "```" + executeCommand(command, "Dependency Tree") + "```";
    }

    List<String> getDependencyTreeCommand(BuildTool tool) {
        List<String> command = new ArrayList<>();
        String executable = resolveExecutable(tool);
        if (executable.isEmpty()) return command;

        command.add(executable);
        switch (tool) {
            case MAVEN:
                command.add("dependency:tree");
                break;
            case GRADLE:
                command.add("dependencies");
                break;
            default:
                return new ArrayList<>();
        }
        return command;
    }

    /**
     * Returns the project structure (modules/subprojects) for the current project.
     *
     * @return The project structure output.
     */
    @LLMDoc("Returns the project structure (modules/subprojects)")
    public String getProjectStructure() {
        BuildTool tool = detect();
        if (tool == BuildTool.UNKNOWN) {
            return "❌ Could not detect build tool.";
        }
        List<String> command = getProjectStructureCommand(tool);
        if (command.isEmpty()) {
            return "❌ Project Structure view is not available for " + tool;
        }
        return executeCommand(command, "Project Structure");
    }

    //@todo add cache as this can be really slow
    /**
     * Returns a comprehensive summary of the project, including build info, files, and readme.
     *
     * @return The project summary.
     */
    @LLMDoc("Returns a comprehensive summary of the project, including build info, files, and readme")
    public String getProjectSummary() {
        String report = "### Project Summary\n";
        report += "### Agent Instructions\n";
        report += getAgentsContents();
        report += "### Build Info\n";
        report += "- Build Tool: " + detect() + "\n";
        report += "- OS: " + getOperatingSystem() + "\n";
        report += "\n";
        report += "### Build File\n";
        report += getBuildFileContents();
        report += "\n";
        report += "### Project Files\n";
        report += fileSystemService.tree(sandbox.getRoot().toAbsolutePath().toString());
        report += "### Project Structure\n";
        report += getProjectStructure();
        report += "\n";
        report += "### Project Readme\n";
        report += getReadmeContents();
        report += "\n";
//        report += "### Dependency Tree\n";
//        report += getDependencyTree();
        report += "\n";
//        report += "### Effective Dependencies\n";
//        report += getEffectiveConfig();
        return report;
    }

    List<String> getProjectStructureCommand(BuildTool tool) {
        List<String> command = new ArrayList<>();
        String executable = resolveExecutable(tool);
        if (executable.isEmpty()) return command;

        command.add(executable);
        switch (tool) {
            case GRADLE:
                command.add("projects");
                break;
            default:
                return new ArrayList<>();
        }
        return command;
    }

    /**
     * Returns the effective build configuration (e.g., effective POM for Maven).
     *
     * @return The effective configuration output.
     */
    @LLMDoc("Returns the effective build configuration (e.g., effective POM for Maven)")
    public String getEffectiveConfig() {
        BuildTool tool = detect();
        if (tool == BuildTool.UNKNOWN) {
            return "❌ Could not detect build tool.";
        }
        List<String> command = getEffectiveConfigCommand(tool);
        if (command.isEmpty()) {
            return "❌ Effective Config is not available for " + tool;
        }
        return executeCommand(command, "Effective Config");
    }

    List<String> getEffectiveConfigCommand(BuildTool tool) {
        List<String> command = new ArrayList<>();
        String executable = resolveExecutable(tool);
        if (executable.isEmpty()) return command;

        command.add(executable);
        switch (tool) {
            case MAVEN:
                command.add("help:effective-pom");
                break;
            case GRADLE:
                command.add("properties");
                break;
            default:
                return new ArrayList<>();
        }
        return command;
    }

    /**
     * Checks the health of the project dependencies (e.g., unused or undeclared dependencies).
     *
     * @return The dependency health report.
     */
    @LLMDoc("Checks the health of the project dependencies")
    public String getDependencyHealth() {
        BuildTool tool = detect();
        if (tool == BuildTool.UNKNOWN) {
            return "❌ Could not detect build tool.";
        }
        List<String> command = getDependencyHealthCommand(tool);
        if (command.isEmpty()) {
            return "❌ Dependency Health check is not available for " + tool;
        }
        return executeCommand(command, "Dependency Health");
    }

    List<String> getDependencyHealthCommand(BuildTool tool) {
        List<String> command = new ArrayList<>();
        String executable = resolveExecutable(tool);
        if (executable.isEmpty()) return command;

        command.add(executable);
        switch (tool) {
            case MAVEN:
                command.add("dependency:analyze");
                break;
            default:
                return new ArrayList<>();
        }
        return command;
    }

    /**
     * Returns the contents of the project build file (pom.xml, build.gradle, etc.).
     *
     * @return The build file contents.
     */
    @LLMDoc("Returns the contents of the project build file (pom.xml, build.gradle, etc.)")
    public String getBuildFileContents() {
        Path projectRoot = sandbox.getRoot();
        Path buildFile = null;

        if (Files.exists(projectRoot.resolve("pom.xml"))) {
            buildFile = projectRoot.resolve("pom.xml");
        } else if (Files.exists(projectRoot.resolve("build.gradle"))) {
            buildFile = projectRoot.resolve("build.gradle");
        } else if (Files.exists(projectRoot.resolve("build.gradle.kts"))) {
            buildFile = projectRoot.resolve("build.gradle.kts");
        } else if (Files.exists(projectRoot.resolve("build.xml"))) {
            buildFile = projectRoot.resolve("build.xml");
        }

        if (buildFile == null) {
            return "❌ Could not detect build file.";
        }

        try {
            return Files.readString(buildFile);
        } catch (IOException e) {
            return "❌ Error reading build file: " + e.getMessage();
        }
    }

    /**
     * Returns the contents of the project README file.
     *
     * @return The README file contents.
     */
    @LLMDoc("Returns the contents of the project README file")
    public String getReadmeContents() {
        Path projectRoot = sandbox.getRoot();
        List<String> potentialFilenames = Arrays.asList(
                "README.md", "README.txt", "README", "readme.md", "readme.txt", "readme"
        );

        for (String filename : potentialFilenames) {
            Path readmePath = projectRoot.resolve(filename);
            if (Files.exists(readmePath)) {
                try {
                    return Files.readString(readmePath);
                } catch (IOException e) {
                    return "❌ Error reading README file: " + e.getMessage();
                }
            }
        }

        return "❌ No README file found.";
    }

    /**
     * Returns the contents of AGENTS.md and the tool API documentation.
     *
     * @return The agent instructions and API documentation.
     */
    @LLMDoc("Returns the contents of AGENTS.md and the tool API documentation")
    public String getAgentsContents() {
        String content = "";
        Path projectRoot = sandbox.getRoot();

        Path agentsPath = projectRoot.resolve("AGENTS.md");
        if (Files.exists(agentsPath)) {
            try {
                content += Files.readString(agentsPath);
            } catch (IOException e) {
                content+= "Unable to find AGENTS.md";
            }
        }

        content += scriptServiceRegistry.get().getApiDocs();
        return content;
    }
}
