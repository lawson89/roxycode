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

/**
 * Service for editing files within the sandbox.
 * Provides fine-grained control over line manipulation and content replacement.
 * All line numbers are 1-based and inclusive.
 */
@ScriptService("fileEditorService")
@Singleton
@LLMDoc("Service for editing files within the sandbox. Provides fine-grained control over line manipulation and content replacement.")
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

    private int[] resolveRange(List<String> lines, int startLine, int endLine) {
        int start = Math.max(1, startLine) - 1;
        int end = Math.min(lines.size(), endLine);
        
        if (start > lines.size()) {
            start = lines.size();
        }
        if (end < start) {
            end = start;
        }
        
        return new int[]{start, end};
    }

    /**
     * Retrieves a range of lines from a file.
     *
     * @param path      The path to the file relative to the sandbox root.
     * @param startLine The starting line number (1-based, inclusive).
     * @param endLine   The ending line number (1-based, inclusive).
     * @return The requested lines joined by newlines. Returns an empty string if the file is empty or the range is invalid.
     * @throws IOException If an I/O error occurs or the file does not exist.
     */
    @LLMDoc("Returns lines from startLine to endLine (1-based index, inclusive).")
    public String getLines(String path, int startLine, int endLine) throws IOException {
        Path p = sandbox.resolve(path);
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        
        int[] range = resolveRange(lines, startLine, endLine);
        int start = range[0];
        int end = range[1];
        
        if (start >= end) {
            return "";
        }
        
        return lines.subList(start, end).stream().collect(Collectors.joining("\n"));
    }

    /**
     * Retrieves a range of lines from a file, prefixed with their 1-based line numbers.
     *
     * @param path      The path to the file relative to the sandbox root.
     * @param startLine The starting line number (1-based, inclusive).
     * @param endLine   The ending line number (1-based, inclusive).
     * @return The requested lines with line numbers joined by newlines. Returns an empty string if the file is empty or the range is invalid.
     * @throws IOException If an I/O error occurs or the file does not exist.
     */
    @LLMDoc("Returns lines with 1-based line numbers from startLine to endLine (1-based index, inclusive).")
    public String getLinesWithNumbers(String path, int startLine, int endLine) throws IOException {
        Path p = sandbox.resolve(path);
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        
        int[] range = resolveRange(lines, startLine, endLine);
        int start = range[0];
        int end = range[1];
        
        if (start >= end) {
            return "";
        }
        
        return IntStream.range(start, end)
                .mapToObj(i -> (i + 1) + ": " + lines.get(i))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Finds the first line in a file that matches a regular expression, starting from a given line.
     *
     * @param path      The path to the file relative to the sandbox root.
     * @param regex     The regular expression to search for.
     * @param startLine The line number to start the search from (1-based).
     * @return The 1-based line number of the first match, or -1 if no match is found or the starting line is out of bounds.
     * @throws IOException If an I/O error occurs or the file does not exist.
     */

    /**
     * Reads the entire content of a file as a string.
     *
     * @param path The path to the file relative to the sandbox root.
     * @return The file content.
     * @throws IOException If an I/O error occurs or the file does not exist.
     */
    @LLMDoc("Returns the entire content of the file.")
    public String readFile(String path) throws IOException {
        return getLines(path, 1, Integer.MAX_VALUE);
    }

    /**
     * Reads the entire content of a file as a string, prefixed with 1-based line numbers.
     *
     * @param path The path to the file relative to the sandbox root.
     * @return The file content with line numbers.
     * @throws IOException If an I/O error occurs or the file does not exist.
     */
    @LLMDoc("Returns the entire content of the file with 1-based line numbers.")
    public String readFileWithNumbers(String path) throws IOException {
        return getLinesWithNumbers(path, 1, Integer.MAX_VALUE);
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

    /**
     * Moves a range of lines to a target position in the file.
     *
     * @param path       The path to the file relative to the sandbox root.
     * @param startLine  The starting line number of the range to move (1-based, inclusive).
     * @param endLine    The ending line number of the range to move (1-based, inclusive).
     * @param targetLine The line number where the moved lines should be inserted (1-based).
     * @throws IOException If an I/O error occurs or the file does not exist.
     */
    @LLMDoc("Moves lines from startLine to endLine to targetLine (1-based index).")
    public void moveLines(String path, int startLine, int endLine, int targetLine) throws IOException {
        Path p = sandbox.resolve(path);
        List<String> lines = new ArrayList<>(Files.readAllLines(p, StandardCharsets.UTF_8));
        saveBackup(path, lines);
        
        int[] range = resolveRange(lines, startLine, endLine);
        int start = range[0];
        int end = range[1];
        
        if (start >= end) {
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

    /**
     * Deletes a range of lines from a file.
     *
     * @param path      The path to the file relative to the sandbox root.
     * @param startLine The starting line number (1-based, inclusive).
     * @param endLine   The ending line number (1-based, inclusive).
     * @throws IOException If an I/O error occurs or the file does not exist.
     */
    @LLMDoc("Deletes lines from startLine to endLine (1-based index, inclusive).")
    public void deleteLines(String path, int startLine, int endLine) throws IOException {
        Path p = sandbox.resolve(path);
        List<String> lines = new ArrayList<>(Files.readAllLines(p, StandardCharsets.UTF_8));
        saveBackup(path, lines);
        
        int[] range = resolveRange(lines, startLine, endLine);
        int start = range[0];
        int end = range[1];
        
        if (start >= end) {
            return;
        }
        
        lines.subList(start, end).clear();
        Files.write(p, lines, StandardCharsets.UTF_8);
    }

    /**
     * Replaces a range of lines in a file with new content.
     *
     * @param path      The path to the file relative to the sandbox root.
     * @param startLine The starting line number (1-based, inclusive).
     * @param endLine   The ending line number (1-based, inclusive).
     * @param newLines  The list of new lines to insert.
     * @throws IOException If an I/O error occurs or the file does not exist.
     */
    @LLMDoc("Replaces lines from startLine to endLine with newLines (1-based index, inclusive). To insert without deleting, set endLine to startLine - 1.")
    public void replaceLines(String path, int startLine, int endLine, List<String> newLines) throws IOException {
        Path p = sandbox.resolve(path);
        List<String> lines = new ArrayList<>(Files.readAllLines(p, StandardCharsets.UTF_8));
        saveBackup(path, lines);
        
        int[] range = resolveRange(lines, startLine, endLine);
        int start = range[0];
        int end = range[1];

        lines.subList(start, end).clear();
        lines.addAll(start, newLines);
        Files.write(p, lines, StandardCharsets.UTF_8);
    }

    /**
     * Inserts a list of lines at a specified target line number.
     *
     * @param path       The path to the file relative to the sandbox root.
     * @param targetLine The line number where the lines should be inserted (1-based).
     * @param lines      The list of lines to insert.
     * @throws IOException If an I/O error occurs or the file does not exist.
     */
    @LLMDoc("Inserts lines at targetLine (1-based index).")
    public void insertLines(String path, int targetLine, List<String> lines) throws IOException {
        replaceLines(path, targetLine, targetLine - 1, lines);
    }

    /**
     * Adjusts the indentation of a range of lines.
     *
     * @param path      The path to the file relative to the sandbox root.
     * @param startLine The starting line number (1-based, inclusive).
     * @param endLine   The ending line number (1-based, inclusive).
     * @param spaces    The number of spaces to add (positive) or remove (negative).
     * @throws IOException If an I/O error occurs or the file does not exist.
     */
    @LLMDoc("Adjusts indentation for a range of lines (1-based index, inclusive). Positive for adding spaces, negative for removing.")
    public void indentLines(String path, int startLine, int endLine, int spaces) throws IOException {
        Path p = sandbox.resolve(path);
        List<String> lines = new ArrayList<>(Files.readAllLines(p, StandardCharsets.UTF_8));
        saveBackup(path, lines);

        int[] range = resolveRange(lines, startLine, endLine);
        int start = range[0];
        int end = range[1];

        if (start >= end) {
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

    /**
     * Replaces occurrences of a regex pattern with a replacement string within a range of lines.
     *
     * @param path        The path to the file relative to the sandbox root.
     * @param pattern     The regex pattern to search for.
     * @param replacement The replacement string.
     * @param startLine   The starting line number (1-based, inclusive).
     * @param endLine     The ending line number (1-based, inclusive).
     * @throws IOException If an I/O error occurs or the file does not exist.
     */
    @LLMDoc("Replaces occurrences of a regex pattern with replacement within a line range (1-based index, inclusive).")
    public void replaceInLines(String path, String pattern, String replacement, int startLine, int endLine) throws IOException {
        Path p = sandbox.resolve(path);
        List<String> lines = new ArrayList<>(Files.readAllLines(p, StandardCharsets.UTF_8));
        saveBackup(path, lines);

        int[] range = resolveRange(lines, startLine, endLine);
        int start = range[0];
        int end = range[1];

        if (start >= end) {
            return;
        }

        for (int i = start; i < end; i++) {
            lines.set(i, lines.get(i).replaceAll(pattern, replacement));
        }
        Files.write(p, lines, StandardCharsets.UTF_8);
    }

    /**
     * Reverts the last change made to a specified file, if a backup exists.
     *
     * @param path The path to the file relative to the sandbox root.
     * @throws IOException If an I/O error occurs.
     */
    @LLMDoc("Reverts the last change made to the specified file.")
    public void undo(String path) throws IOException {
        List<String> backup = backups.get(path);
        if (backup != null) {
            Path p = sandbox.resolve(path);
            Files.write(p, backup, StandardCharsets.UTF_8);
            backups.remove(path);
        }
    }
}
