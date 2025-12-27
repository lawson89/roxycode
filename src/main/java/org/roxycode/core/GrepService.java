package org.roxycode.core;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Singleton
public class GrepService {

    private final Sandbox sandbox;

    @Inject
    public GrepService(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    public String grep(String patternStr, String pathStr, String filePattern) {
        if (pathStr == null || pathStr.isEmpty()) {
            pathStr = ".";
        }
        if (filePattern == null || filePattern.isEmpty()) {
            filePattern = "*";
        }

        Path root;
        try {
            root = sandbox.resolve(pathStr);
        } catch (SecurityException e) {
            return "❌ Security Error: " + e.getMessage();
        }

        if (!Files.exists(root)) {
            return "❌ Path not found: " + pathStr;
        }

        Pattern regex;
        try {
            regex = Pattern.compile(patternStr);
        } catch (Exception e) {
            return "❌ Invalid regex: " + e.getMessage();
        }

        PathMatcher fileMatcher = FileSystems.getDefault().getPathMatcher("glob:" + filePattern);
        Path projectRoot = sandbox.getRoot();
        StringBuilder sb = new StringBuilder();
        List<String> results = new ArrayList<>();
        final int MAX_MATCHES = 500;

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                private int matchCount = 0;

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    if (name.startsWith(".") || name.equals("target") || name.equals("build") || name.equals("node_modules")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matchCount >= MAX_MATCHES) {
                        return FileVisitResult.TERMINATE;
                    }

                    if (!fileMatcher.matches(file.getFileName())) {
                        return FileVisitResult.CONTINUE;
                    }

                    try (BufferedReader reader = Files.newBufferedReader(file)) {
                        String line;
                        int lineNum = 0;
                        while ((line = reader.readLine()) != null) {
                            lineNum++;
                            Matcher matcher = regex.matcher(line);
                            if (matcher.find()) {
                                Path relativePath = projectRoot.relativize(file);
                                results.add(relativePath.toString() + ":" + lineNum + ": " + line.trim());
                                matchCount++;
                                if (matchCount >= MAX_MATCHES) {
                                    return FileVisitResult.TERMINATE;
                                }
                            }
                        }
                    } catch (IOException e) {
                        // Ignore unreadable files
                    } catch (Exception e) {
                        // Ignore other errors
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return "❌ Error walking file tree: " + e.getMessage();
        }

        if (results.isEmpty()) {
            return "No matches found.";
        }

        String output = results.stream().collect(Collectors.joining("\n"));
        if (results.size() >= MAX_MATCHES) {
            output += "\n... (truncated after " + MAX_MATCHES + " matches)";
        }

        return output;
    }
}
