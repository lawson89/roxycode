package org.roxycode.core.tools.service;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.roxycode.core.Sandbox;
import org.roxycode.core.tools.LLMDoc;
import org.roxycode.core.tools.ScriptService;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service for high-level file system operations within the sandbox.
 * Includes basic I/O, directory listing, and structural views.
 */
@ScriptService("fileSystemService")
@Singleton
@LLMDoc("Service for high-level file system operations within the sandbox. Includes basic I/O, directory listing, and structural views.")
public class FileSystemService {

    private final Sandbox sandbox;

    @Inject
    public FileSystemService(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    /**
     * Reads the entire content of a file as a UTF-8 string.
     *
     * @param path The path to the file.
     * @return The file content.
     * @throws IOException If an I/O error occurs.
     */
    @LLMDoc("Reads the content of a file at the given path")
    public String readFile(String path) throws IOException {
        Path p = sandbox.resolve(path);
        return FileUtils.readFileToString(p.toFile(), StandardCharsets.UTF_8);
    }

    /**
     * Writes a string to a file at the specified path, creating parent directories if necessary.
     *
     * @param path    The path to the file.
     * @param content The content to write.
     * @throws IOException If an I/O error occurs.
     */
    @LLMDoc("Writes the given content to a file at the specified path")
    public void writeFile(String path, String content) throws IOException {
        Path p = sandbox.resolve(path);
        Path parent = p.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        FileUtils.writeStringToFile(p.toFile(), content, StandardCharsets.UTF_8);
    }

    /**
     * Deletes a file or directory at the specified path. Directories are deleted recursively.
     *
     * @param path The path to the file or directory.
     * @throws IOException If the path is protected or an I/O error occurs.
     */
    @LLMDoc("Deletes the file or directory at the given path")
    public void delete(String path) throws IOException {
        Path p = sandbox.resolve(path);
        // Safety checks
        Path root = sandbox.getRoot();
        if (p.equals(root))
            throw new IOException("Cannot delete project root.");
        if (p.startsWith(root.resolve(".git")))
            throw new IOException("Cannot delete .git folder.");
        File file = p.toFile();
        if (file.isDirectory()) {
            FileUtils.deleteDirectory(file);
        } else {
            FileUtils.forceDelete(file);
        }
    }

    /**
     * Lists files in a directory that match a wildcard pattern.
     *
     * @param path      The path to the directory.
     * @param pattern   The wildcard pattern (e.g., "*.java").
     * @param recursive Whether to search subdirectories.
     * @return A newline-separated list of relative file paths.
     */
    @LLMDoc("Lists files in a directory matching a pattern, optionally recursive")
    public String listFiles(String path, String pattern, boolean recursive) {
        Path p = sandbox.resolve(path);
        File dir = p.toFile();
        if (!dir.exists() || !dir.isDirectory()) {
            return "Error: Path not found or not a directory: " + path;
        }
        Collection<File> files = FileUtils.listFiles(dir, WildcardFileFilter.builder().setWildcards(pattern).get(), recursive ? TrueFileFilter.INSTANCE : null);
        Path projectRoot = sandbox.getRoot();
        return files.stream().map(f -> projectRoot.relativize(f.toPath()).toString()).sorted().collect(Collectors.joining("\n"));
    }

    /**
     * Generates a visual tree representation of a directory structure.
     *
     * @param path The path to the root directory.
     * @return A string representing the directory tree.
     */
    @LLMDoc("Returns a visual tree representation of the directory structure")
    public String tree(String path) {
        Path rootDir = sandbox.resolve(path);
        if (!Files.exists(rootDir)) {
            return "❌ Path not found: " + path;
        }
        if (!Files.isDirectory(rootDir)) {
            return "❌ Not a directory: " + path;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(rootDir.getFileName()).append("/\n");
        traverse(rootDir, "", 0, sb);
        return sb.toString();
    }

    private void traverse(Path currentDir, String prefix, int depth, StringBuilder sb) {
        if (depth > 5) {
            sb.append(prefix).append("└── ... (max depth reached)\n");
            return;
        }
        try (Stream<Path> stream = Files.list(currentDir)) {
            List<Path> contents = stream.sorted((a, b) -> {
                boolean aDir = Files.isDirectory(a);
                boolean bDir = Files.isDirectory(b);
                if (aDir && !bDir)
                    return -1;
                if (!aDir && bDir)
                    return 1;
                return a.compareTo(b);
            }).toList();
            for (int i = 0; i < contents.size(); i++) {
                Path p = contents.get(i);
                boolean isLast = (i == contents.size() - 1);
                sb.append(prefix);
                sb.append(isLast ? "└── " : "├── ");
                sb.append(p.getFileName());
                if (Files.isDirectory(p))
                    sb.append("/");
                sb.append("\n");
                if (Files.isDirectory(p)) {
                    String name = p.getFileName().toString();
                    // @todo externalize this
                    if (!name.startsWith(".") && !name.equals("target") && !name.equals("build")) {
                        traverse(p, prefix + (isLast ? "    " : "│   "), depth + 1, sb);
                    }
                }
            }
        } catch (IOException e) {
            sb.append(prefix).append("❌ Access Denied: ").append(e.getMessage()).append("\n");
        }
    }

    /**
     * Performs a regex search and replace on the content of a file.
     *
     * @param path    The path to the file.
     * @param search  The regex pattern to search for.
     * @param replace The replacement string.
     * @return A status message indicating whether any changes were made.
     * @throws IOException If an I/O error occurs.
     */
    @LLMDoc("Replaces occurrences of a string in a file with another string using regex")
    public String replaceInFile(String path, String search, String replace) throws IOException {
        Path p = sandbox.resolve(path);
        String content = FileUtils.readFileToString(p.toFile(), StandardCharsets.UTF_8);
        String updated = content.replaceAll(search, replace);
        if (content.equals(updated)) {
            return "No changes made to " + path;
        }
        FileUtils.writeStringToFile(p.toFile(), updated, StandardCharsets.UTF_8);
        return "Successfully updated " + path;
    }

    /**
     * Reads the content of multiple files and concatenates them into a single string with headers.
     *
     * @param paths The list of file paths to read.
     * @return A string containing the content of all successfully read files.
     */
    @LLMDoc("Reads the contents of multiple files and returns them as a single string")
    public String readFiles(List<String> paths) {
        StringBuilder output = new StringBuilder();
        for (String pathStr : paths) {
            output.append("--- File: ").append(pathStr).append(" ---\n");
            try {
                output.append(readFile(pathStr)).append("\n");
            } catch (Exception e) {
                output.append("(Error reading file: ").append(e.getMessage()).append(")\n");
            }
            output.append("\n");
        }
        return output.toString();
    }
    /**
     * Checks if a file or directory exists at the specified path.
     *
     * @param path The path to check.
     * @return true if the path exists, false otherwise.
     */
    @LLMDoc("Checks if a file or directory exists at the specified path")
    public boolean exists(String path) {
        return Files.exists(sandbox.resolve(path));
    }

    /**
     * Checks if the specified path is a directory.
     *
     * @param path The path to check.
     * @return true if it is a directory, false otherwise.
     */
    @LLMDoc("Checks if the specified path is a directory")
    public boolean isDirectory(String path) {
        return Files.isDirectory(sandbox.resolve(path));
    }

    /**
     * Checks if the specified path is a regular file.
     *
     * @param path The path to check.
     * @return true if it is a file, false otherwise.
     */
    @LLMDoc("Checks if the specified path is a regular file")
    public boolean isFile(String path) {
        return Files.isRegularFile(sandbox.resolve(path));
    }

    /**
     * Moves or renames a file or directory from source to target.
     *
     * @param source The source path.
     * @param target The target path.
     * @throws IOException If an I/O error occurs.
     */
    @LLMDoc("Moves or renames a file or directory")
    public void move(String source, String target) throws IOException {
        Path src = sandbox.resolve(source);
        Path dest = sandbox.resolve(target);
        Files.move(src, dest);
    }

    /**
     * Copies a file or directory from source to target.
     *
     * @param source The source path.
     * @param target The target path.
     * @throws IOException If an I/O error occurs.
     */
    @LLMDoc("Copies a file or directory")
    public void copy(String source, String target) throws IOException {
        Path src = sandbox.resolve(source);
        Path dest = sandbox.resolve(target);
        if (Files.isDirectory(src)) {
            FileUtils.copyDirectory(src.toFile(), dest.toFile());
        } else {
            FileUtils.copyFile(src.toFile(), dest.toFile());
        }
    }

    /**
     * Returns the size of a file in bytes.
     *
     * @param path The path to the file.
     * @return The file size in bytes.
     * @throws IOException If the file does not exist or an I/O error occurs.
     */
    @LLMDoc("Returns the size of a file in bytes")
    public long getSize(String path) throws IOException {
        return Files.size(sandbox.resolve(path));
    }
}
