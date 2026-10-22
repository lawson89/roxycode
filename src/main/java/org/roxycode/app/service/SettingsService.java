package org.roxycode.app.service;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.GeminiModelConfig;
import org.roxycode.app.model.GeminiModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
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

    public SettingsService() {
        this(AiConfig.SHARED_TOML_MAPPER, event -> {});
    }

    public SettingsService(TomlMapper mapper) {
        this(mapper, event -> {});
    }

    public SettingsService(ApplicationEventPublisher eventPublisher) {
        this(AiConfig.SHARED_TOML_MAPPER, eventPublisher);
    }

    @Autowired
    public SettingsService(TomlMapper mapper, ApplicationEventPublisher eventPublisher) {
        this.mapper = mapper != null ? mapper : AiConfig.SHARED_TOML_MAPPER;
        this.eventPublisher = eventPublisher != null ? eventPublisher : event -> {};
    }

    private AppSettings settings;
    private Path settingsPathOverride;

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
    public Path getRoxyHomeDir() {
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
                log.error("Failed to load settings from {}: {}", path, e.getMessage(), e);
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
        try (InputStream is = SettingsService.class.getResourceAsStream("/models.toml")) {
            if (is == null) {
                log.error("Could not find /models.toml on classpath");
                return List.of();
            }
            GeminiModels geminiModels = mapper.readValue(is, GeminiModels.class);
            return geminiModels != null && geminiModels.models() != null
                    ? geminiModels.models()
                    : List.of();
        } catch (IOException e) {
            log.error("Failed to load Gemini models: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Gets the current application settings.
     *
     * @return AppSettings object.
     */
    public AppSettings getSettings() {
        if (settings == null) {
            loadSettings();
        }
        return settings;
    }

    /** Saves the current settings to the TOML file. */
    public void saveSettings() {
        try {
            Path path = getSettingsPath();
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            AppSettings currentSettings = getSettings();
            mapper.writeValue(path.toFile(), currentSettings);
            eventPublisher.publishEvent(new SettingsChangedEvent(currentSettings));
        } catch (IOException e) {
            log.error("Failed to save settings: {}", e.getMessage(), e);
        }
    }

    /**
     * Overrides the settings path, useful for testing.
     *
     * @param path The path to use for settings.
     */
    public void setSettingsPathOverride(Path path) {
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
