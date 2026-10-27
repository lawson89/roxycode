package org.roxycode.app.service;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.GeminiModelConfig;
import org.roxycode.app.model.GeminiModels;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** Service for managing application settings. */
@Service
@SuppressWarnings("PMD.NullAssignment")
public class SettingsService {
    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);
    private static final String SETTINGS_DIR = ".roxycode";
    private static final String SETTINGS_FILE = "settings.toml";

    private final TomlMapper mapper;
    private final ApplicationEventPublisher eventPublisher;

    public SettingsService(TomlMapper mapper, ApplicationEventPublisher eventPublisher) {
        this.mapper = mapper != null ? mapper : AiConfig.SHARED_TOML_MAPPER;
        this.eventPublisher = eventPublisher != null ? eventPublisher : event -> {};
    }

    private AppSettings settings;
    private Path settingsPathOverride;
    private volatile List<GeminiModelConfig> cachedAvailableModels;
    private volatile java.util.concurrent.CompletableFuture<Void> lastSaveFuture =
            java.util.concurrent.CompletableFuture.completedFuture(null);

    /** Returns the default RoxyCode home directory (~/.roxycode). */
    public static Path getDefaultRoxyHomeDir() {
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, SETTINGS_DIR);
    }

    /** Returns the default user settings file path (~/.roxycode/settings.toml). */
    public static Path getDefaultSettingsPath() {
        return getDefaultRoxyHomeDir().resolve(SETTINGS_FILE);
    }

    /**
     * Returns the active RoxyCode home directory (~/.roxycode by default, or the parent directory
     * of {@code settingsPathOverride} when overridden in tests).
     */
    public synchronized Path getRoxyHomeDir() {
        if (settingsPathOverride != null && settingsPathOverride.getParent() != null) {
            return settingsPathOverride.getParent();
        }
        return getDefaultRoxyHomeDir();
    }

    /**
     * Loads {@link AppSettings} from the default settings path using {@link
     * AiConfig#SHARED_TOML_MAPPER}, falling back to default settings if missing or unreadable.
     */
    public static AppSettings loadFromDisk() {
        return loadFromDisk(getDefaultSettingsPath(), AiConfig.SHARED_TOML_MAPPER);
    }

    /**
     * Loads {@link AppSettings} from the given path using the provided {@link TomlMapper}, falling
     * back to default settings if missing or unreadable.
     */
    public static AppSettings loadFromDisk(Path path, TomlMapper mapper) {
        TomlMapper effectiveMapper = mapper != null ? mapper : AiConfig.SHARED_TOML_MAPPER;
        if (path != null && Files.exists(path)) {
            try {
                return effectiveMapper.readValue(path.toFile(), AppSettings.class);
            } catch (IOException e) {
                log.warn("Failed to load settings from {}: {}", path, e.getMessage());
            }
        }
        return new AppSettings();
    }

    /**
     * Loads the available Gemini model configurations from {@code /models.toml}.
     *
     * @return list of available model configurations
     */
    public List<GeminiModelConfig> getAvailableModels() {
        List<GeminiModelConfig> cached = this.cachedAvailableModels;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (this.cachedAvailableModels != null) {
                return this.cachedAvailableModels;
            }
            ClassPathResource resource = new ClassPathResource("models.toml");
            try {
                String content = resource.getContentAsString(StandardCharsets.UTF_8);
                GeminiModels geminiModels = mapper.readValue(content, GeminiModels.class);
                List<GeminiModelConfig> loaded =
                        geminiModels != null && geminiModels.models() != null
                                ? List.copyOf(geminiModels.models())
                                : List.of();
                this.cachedAvailableModels = loaded;
                return loaded;
            } catch (IOException e) {
                log.error("Failed to load Gemini models: {}", e.getMessage(), e);
                return List.of();
            }
        }
    }

    /**
     * Gets the current application settings.
     *
     * @return AppSettings object.
     */
    public synchronized AppSettings getSettings() {
        if (settings == null) {
            loadSettings();
        }
        return settings;
    }

    /** Saves the current settings to the TOML file. */
    public void saveSettings() {
        AppSettings currentSettings;
        Path path;
        synchronized (this) {
            currentSettings = getSettings();
            path = getSettingsPath();
        }
        eventPublisher.publishEvent(new SettingsChangedEvent(currentSettings));
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            lastSaveFuture =
                    java.util.concurrent.CompletableFuture.runAsync(
                            () -> writeSettingsToDisk(path, currentSettings));
        } else {
            writeSettingsToDisk(path, currentSettings);
            lastSaveFuture = java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    }

    private synchronized void writeSettingsToDisk(Path path, AppSettings currentSettings) {
        try {
            PathUtils.createParentDirectories(path);
            mapper.writeValue(path.toFile(), currentSettings);
        } catch (IOException e) {
            log.error("Failed to save settings: {}", e.getMessage(), e);
        }
    }

    java.util.concurrent.CompletableFuture<Void> getLastSaveFuture() {
        return lastSaveFuture;
    }

    /**
     * Overrides the settings path, useful for testing.
     *
     * @param path The path to use for settings.
     */
    public synchronized void setSettingsPathOverride(Path path) {
        this.settingsPathOverride = path;
        this.settings = null; // Force reload
    }

    private void loadSettings() {
        settings = loadFromDisk(getSettingsPath(), mapper);
    }

    private Path getSettingsPath() {
        if (settingsPathOverride != null) {
            return settingsPathOverride;
        }
        return getDefaultSettingsPath();
    }
}
