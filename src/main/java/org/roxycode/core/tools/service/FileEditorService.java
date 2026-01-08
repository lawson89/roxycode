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
import java.util.List;
import java.util.stream.Collectors;

@ScriptService("fileEditorService")
@Singleton
public class FileEditorService {

    private final Sandbox sandbox;

    @Inject
    public FileEditorService(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    @LLMDoc("Returns lines from startLine to endLine (1-based index, inclusive)")
    public String getLines(String path, int startLine, int endLine) throws IOException {
        Path p = sandbox.resolve(path);
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        
        int start = Math.max(1, startLine) - 1;
        int end = Math.min(lines.size(), endLine);
        
        if (start > end || start >= lines.size()) {
            return "";
        }
        
        return lines.subList(start, end).stream().collect(Collectors.joining("\n"));
    }

    @LLMDoc("Moves lines from startLine to endLine to targetLine (1-based index)")
    public void moveLines(String path, int startLine, int endLine, int targetLine) throws IOException {
        Path p = sandbox.resolve(path);
        List<String> lines = new ArrayList<>(Files.readAllLines(p, StandardCharsets.UTF_8));
        
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
}
