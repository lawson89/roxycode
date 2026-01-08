package org.roxycode.core.tools.service;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.roxycode.core.Sandbox;
import org.roxycode.core.tools.LLMDoc;
import org.roxycode.core.tools.ScriptService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@ScriptService("fileEditorService")
@Singleton
public class FileEditorService {

    private final Sandbox sandbox;
    private final Map<String, List<String>> backups = new HashMap<>();

    @Inject
    public FileEditorService(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    private void saveBackup(String path, List<String> currentLines) {
        backups.put(path, new ArrayList<>(currentLines));
    }

    @LLMDoc("Returns lines from startLine to endLine (1-based index, inclusive)")
    public String getLines(String path, int startLine, int endLine) throws IOException {
        Path p = sandbox.resolve(path);
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        
        int start = Math.max(1, startLine) - 1;
        int end = Math.min(lines.size(), endLine);
        
        if (start >= end || start >= lines.size()) {
            return "";
        }
        
        return lines.subList(start, end).stream().collect(Collectors.joining("\n"));
    }

    @LLMDoc("Returns lines with 1-based line numbers from startLine to endLine (inclusive)")
    public String getLinesWithNumbers(String path, int startLine, int endLine) throws IOException {
        Path p = sandbox.resolve(path);
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        
        int start = Math.max(1, startLine) - 1;
        int end = Math.min(lines.size(), endLine);
        
        if (start >= end || start >= lines.size()) {
            return "";
        }
        
        return IntStream.range(start, end)
                .mapToObj(i -> (i + 1) + ": " + lines.get(i))
                .collect(Collectors.joining("\n"));
    }

    @LLMDoc("Finds the first line matching the regex after startLine (1-based index). Returns -1 if not found.")
    public int findLine(String path, String regex, int startLine) throws IOException {
        Path p = sandbox.resolve(path);
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        Pattern pattern = Pattern.compile(regex);
        
        int start = Math.max(1, startLine) - 1;
        for (int i = start; i < lines.size(); i++) {
            if (pattern.matcher(lines.get(i)).find()) {
                return i + 1;
            }
        }
        return -1;
    }

    @LLMDoc("Moves lines from startLine to endLine to targetLine (1-based index)")
    public void moveLines(String path, int startLine, int endLine, int targetLine) throws IOException {
        Path p = sandbox.resolve(path);
        List<String> lines = new ArrayList<>(Files.readAllLines(p, StandardCharsets.UTF_8));
        saveBackup(path, lines);
        
        int start = Math.max(1, startLine) - 1;
        int end = Math.min(lines.size(), endLine);
        
        if (start >= end || start >= lines.size()) {
            return;
        }
        
        List<String> toMove = new ArrayList<>(lines.subList(start, end));
        lines.subList(start, end).clear();
        
        int target = Math.max(1, targetLine) - 1;
        if (target > lines.size()) {
            target = lines.size();
        }
        
        lines.addAll(target, toMove);
        Files.write(p, lines, StandardCharsets.UTF_8);
    }

    @LLMDoc("Deletes lines from startLine to endLine (1-based index, inclusive)")
    public void deleteLines(String path, int startLine, int endLine) throws IOException {
        Path p = sandbox.resolve(path);
        List<String> lines = new ArrayList<>(Files.readAllLines(p, StandardCharsets.UTF_8));
        saveBackup(path, lines);
        
        int start = Math.max(1, startLine) - 1;
        int end = Math.min(lines.size(), endLine);
        
        if (start >= end || start >= lines.size()) {
            return;
        }
        
        lines.subList(start, end).clear();
        Files.write(p, lines, StandardCharsets.UTF_8);
    }

    @LLMDoc("Replaces lines from startLine to endLine with newLines (1-based index, inclusive)")
    public void replaceLines(String path, int startLine, int endLine, List<String> newLines) throws IOException {
        Path p = sandbox.resolve(path);
        List<String> lines = new ArrayList<>(Files.readAllLines(p, StandardCharsets.UTF_8));
        saveBackup(path, lines);
        
        int start = Math.max(1, startLine) - 1;
        int end = Math.min(lines.size(), endLine);
        
        if (start > lines.size()) {
            start = lines.size();
        }
        if (end < start) {
            end = start;
        }
        if (end > lines.size()) {
            end = lines.size();
        }

        lines.subList(start, end).clear();
        lines.addAll(start, newLines);
        Files.write(p, lines, StandardCharsets.UTF_8);
    }

    @LLMDoc("Inserts lines at targetLine (1-based index)")
    public void insertLines(String path, int targetLine, List<String> lines) throws IOException {
        replaceLines(path, targetLine, targetLine - 1, lines);
    }

    @LLMDoc("Adjusts indentation for a range of lines. Positive for adding spaces, negative for removing.")
    public void indentLines(String path, int startLine, int endLine, int spaces) throws IOException {
        Path p = sandbox.resolve(path);
        List<String> lines = new ArrayList<>(Files.readAllLines(p, StandardCharsets.UTF_8));
        saveBackup(path, lines);

        int start = Math.max(1, startLine) - 1;
        int end = Math.min(lines.size(), endLine);

        if (start >= end || start >= lines.size()) {
            return;
        }

        for (int i = start; i < end; i++) {
            String line = lines.get(i);
            if (spaces > 0) {
                lines.set(i, " ".repeat(spaces) + line);
            } else if (spaces < 0) {
                int toRemove = Math.abs(spaces);
                int count = 0;
                while (count < toRemove && count < line.length() && line.charAt(count) == ' ') {
                    count++;
                }
                lines.set(i, line.substring(count));
            }
        }
        Files.write(p, lines, StandardCharsets.UTF_8);
    }

    @LLMDoc("Replaces occurrences of a regex pattern with replacement within a line range")
    public void replaceInLines(String path, String pattern, String replacement, int startLine, int endLine) throws IOException {
        Path p = sandbox.resolve(path);
        List<String> lines = new ArrayList<>(Files.readAllLines(p, StandardCharsets.UTF_8));
        saveBackup(path, lines);

        int start = Math.max(1, startLine) - 1;
        int end = Math.min(lines.size(), endLine);

        if (start >= end || start >= lines.size()) {
            return;
        }

        for (int i = start; i < end; i++) {
            lines.set(i, lines.get(i).replaceAll(pattern, replacement));
        }
        Files.write(p, lines, StandardCharsets.UTF_8);
    }

    @LLMDoc("Reverts the last change made to the specified file")
    public void undo(String path) throws IOException {
        List<String> backup = backups.get(path);
        if (backup != null) {
            Path p = sandbox.resolve(path);
            Files.write(p, backup, StandardCharsets.UTF_8);
            backups.remove(path);
        }
    }
}
