package org.roxycode.app.service;

import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.model.AppSettings;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Service for managing the active project root directory.
 */
@Service
@AgentDoc("Manages the current active project root directory and handles its persistence.")
public class ProjectService {
    private final SettingsService settingsService;
    private final List<Consumer<Path>> projectListeners = new ArrayList<>();
    private Path currentProjectRoot;

    public ProjectService(SettingsService settingsService) {
        this.settingsService = settingsService;
        String savedPath = settingsService.getSettings().getCurrentProjectPath();
        if (savedPath != null && !savedPath.isEmpty()) {
            this.currentProjectRoot = Paths.get(savedPath);
        } else {
            this.currentProjectRoot = Paths.get(System.getProperty("user.dir"));
        }
    }

    @AgentDoc("Returns the current active project root path.")
    public Path getCurrentProjectRoot() {
        return currentProjectRoot;
    }

    @AgentDoc("Returns true if there is an active project root set.")
    public boolean hasActiveProject() {
        return currentProjectRoot != null;
    }

    @AgentDoc("Sets the active project root path and persists it in settings.")
    public void setCurrentProjectRoot(Path newRoot) {
        if (newRoot != null) {
            this.currentProjectRoot = newRoot;
            AppSettings settings = settingsService.getSettings();
            settings.setCurrentProjectPath(newRoot.toAbsolutePath().toString());
            settingsService.saveSettings();
            notifyListeners(newRoot);
        }
    }

    @AgentDoc("Returns the name of the current project (the last element of the path).")
    public String getProjectName() {
        if (currentProjectRoot == null) {
            return "No Project";
        }
        return currentProjectRoot.getFileName().toString();
    }

    public void addProjectListener(Consumer<Path> listener) {
        projectListeners.add(listener);
    }

    private void notifyListeners(Path newRoot) {
        for (Consumer<Path> listener : projectListeners) {
            listener.accept(newRoot);
        }
    }
}