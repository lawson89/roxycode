package org.roxycode.app.service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.model.AppSettings;
import org.springframework.stereotype.Service;

/** Service for managing the active project root directory. */
@Service
@AgentDoc("Manages the current active project root directory and handles its persistence.")
@SuppressWarnings("PMD.NullAssignment")
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
      // Fix: Do not default to user.dir to avoid accidental indexing of home directory
      this.currentProjectRoot = null;
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

  /**
   * Resolves a path relative to the active project root and validates that it stays within the
   * project sandbox.
   *
   * @param relativePath the path relative to the project root (null defaults to ".")
   * @return the normalized absolute path within the project root
   * @throws IllegalStateException if no active project root is set
   * @throws SecurityException if the resolved path escapes the project root
   */
  public Path resolvePath(String relativePath) {
    Path projectRoot = getCurrentProjectRoot();
    if (projectRoot == null) {
      throw new IllegalStateException("Project root is not set.");
    }
    Path root = projectRoot.toAbsolutePath().normalize();
    Path resolved =
        root.resolve(relativePath != null ? relativePath : ".").toAbsolutePath().normalize();
    if (!resolved.startsWith(root)) {
      throw new SecurityException("Path traversal attempt detected: " + relativePath);
    }
    return resolved;
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
