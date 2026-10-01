package org.roxycode.app.service;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import org.roxycode.app.model.AppSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Service for managing application settings.
 */
@Service
public class SettingsService {
    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);
    private static final String SETTINGS_DIR = ".roxycode";
    private static final String SETTINGS_FILE = "settings.toml";
    
    private final TomlMapper mapper = new TomlMapper();
    private AppSettings settings;
    private Path settingsPathOverride;
    private final List<Consumer<AppSettings>> listeners = new ArrayList<>();

    /**
     * Gets the current application settings.
     * @return AppSettings object.
     */
    public AppSettings getSettings() {
        if (settings == null) {
            loadSettings();
        }
        return settings;
    }

    /**
     * Saves the current settings to the TOML file.
     */
    public void addSettingsListener(Consumer<AppSettings> listener) {
        listeners.add(listener);
    }

    public void saveSettings() {
        try {
            Path path = getSettingsPath();
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            mapper.writeValue(path.toFile(), getSettings());
            notifyListeners();
        } catch (IOException e) {
            log.error("Failed to save settings: {}", e.getMessage(), e);
        }
    }

    /**
     * Overrides the settings path, useful for testing.
     * @param path The path to use for settings.
     */
    public void setSettingsPathOverride(Path path) {
        this.settingsPathOverride = path;
        this.settings = null; // Force reload
    }

    private void loadSettings() {
        Path path = getSettingsPath();
        if (Files.exists(path)) {
            try {
                settings = mapper.readValue(path.toFile(), AppSettings.class);
            } catch (IOException e) {
                log.error("Failed to load settings from {}: {}", path, e.getMessage(), e);
                settings = new AppSettings();
            }
        } else {
            settings = new AppSettings();
        }
    }

    private void notifyListeners() {
        AppSettings currentSettings = getSettings();
        for (Consumer<AppSettings> listener : listeners) {
            listener.accept(currentSettings);
        }
    }

    private Path getSettingsPath() {
        if (settingsPathOverride != null) {
            return settingsPathOverride;
        }
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, SETTINGS_DIR, SETTINGS_FILE);
    }
}