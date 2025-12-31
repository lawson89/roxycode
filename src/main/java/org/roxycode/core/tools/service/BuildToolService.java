package org.roxycode.core.tools.service;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.roxycode.core.Sandbox;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Singleton
public class BuildToolService {

    private final Sandbox sandbox;
    private final FileSystemService fileSystemService;


    public enum BuildTool {
        MAVEN, GRADLE, ANT, UNKNOWN
    }

    @Inject
    public BuildToolService(Sandbox sandbox, FileSystemService fileSystemService) {
        this.sandbox = sandbox;
        this.fileSystemService = fileSystemService;
    }

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

    public String compile() {
        BuildTool tool = detect();
        if (tool == BuildTool.UNKNOWN) {
            return "❌ Could not detect build tool (no pom.xml, build.gradle, or build.xml found).";
        }
        return executeCommand(getCompileCommand(tool), "Compilation");
    }

    public String runTests() {
        BuildTool tool = detect();
        if (tool == BuildTool.UNKNOWN) {
            return "❌ Could not detect build tool (no pom.xml, build.gradle, or build.xml found).";
        }
        return executeCommand(getTestCommand(tool), "Tests");
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

    String resolveExecutable(BuildTool tool) {
        return resolveExecutable(tool, System.getProperty("os.name").toLowerCase().contains("win"));
    }

    String getOperatingSystem() {
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
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return "✅ " + context + " SUCCESSFUL\n" + output;
            } else {
                return "❌ " + context + " FAILED (Exit Code: " + exitCode + ")\n" + output;
            }
        } catch (IOException | InterruptedException e) {
            return "❌ ERROR executing " + context + ": " + String.join(" ", command) + "\n" + e.getMessage();
        }
    }

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
    public String getProjectSummary() {
        String report = "### Project Info\n";
        report += "- Build Tool: " + detect() + "\n";
        report += "- OS: " + getOperatingSystem() + "\n";
        report += "\n";
        report += "### Build file\n";
        report += getBuildFileContents();
        report += "\n";
        report += "### Project Files\n";
        report += "```" + fileSystemService.tree(sandbox.getRoot().toAbsolutePath().toString()) + "```\n";
        report += "### Project Structure\n";
        report += getProjectStructure();
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


}
