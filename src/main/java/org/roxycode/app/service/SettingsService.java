package org.roxycode.app.service;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.swing.SwingUtilities;
import org.apache.commons.lang3.concurrent.ConcurrentException;
import org.apache.commons.lang3.concurrent.LazyInitializer;
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

@Service
@SuppressWarnings("PMD.NullAssignment")
public class SettingsService {
    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);
    private static final String SETTINGS_DIR = ".roxycode";
    private static final String SETTINGS_FILE = "settings.toml";

    private final TomlMapper mapper;
    private final ApplicationEventPublisher eventPublisher;
    private final Object writeLock = new Object();
    private AppSettings settings;
    private Path settingsPathOverride;
    private final LazyInitializer<List<GeminiModelConfig>> availableModelsInitializer = LazyInitializer
            .<List<GeminiModelConfig>>builder().setInitializer(this::loadAvailableModels).get();
    private volatile CompletableFuture<Void> lastSaveFuture = CompletableFuture.completedFuture(null);

    public SettingsService(TomlMapper mapper, ApplicationEventPublisher eventPublisher) {
        this.mapper = mapper != null ? mapper : AiConfig.SHARED_TOML_MAPPER;
        this.eventPublisher = eventPublisher != null ? eventPublisher : event -> {
        };
    }

    public static Path getDefaultRoxyHomeDir() {
        return Paths.get(System.getProperty("user.home"), SETTINGS_DIR);
    }

    public static Path getDefaultSettingsPath() {
        return getDefaultRoxyHomeDir().resolve(SETTINGS_FILE);
    }

    public synchronized Path getRoxyHomeDir() {
        return (settingsPathOverride != null && settingsPathOverride.getParent() != null)
                ? settingsPathOverride.getParent()
                : getDefaultRoxyHomeDir();
    }

    public static AppSettings loadFromDisk() {
        return loadFromDisk(getDefaultSettingsPath(), AiConfig.SHARED_TOML_MAPPER);
    }

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

    public List<GeminiModelConfig> getAvailableModels() {
        try {
            return availableModelsInitializer.get();
        } catch (ConcurrentException e) {
            log.error("Failed to load Gemini models: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @SuppressWarnings("PMD.UseProperClassLoader")
    private List<GeminiModelConfig> loadAvailableModels() {
        try {
            String content = new ClassPathResource("models.toml", SettingsService.class.getClassLoader())
                    .getContentAsString(StandardCharsets.UTF_8);
            GeminiModels geminiModels = mapper.readValue(content, GeminiModels.class);
            return geminiModels != null && geminiModels.models() != null
                    ? List.copyOf(geminiModels.models())
                    : List.of();
        } catch (IOException e) {
            log.error("Failed to load Gemini models: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public synchronized boolean hasSettingsFile() {
        return Files.exists(getSettingsPath());
    }

    public synchronized boolean hasConfiguredApiKey() {
        if (!hasSettingsFile()) {
            return false;
        }
        String apiKey = getSettings().getGeminiApiKey();
        return apiKey != null && !apiKey.isBlank();
    }

    public synchronized AppSettings getSettings() {
        if (settings == null) {
            loadSettings();
        }
        return settings;
    }

    public void saveSettings() {
        AppSettings snapshot;
        Path path;
        CompletableFuture<Void> saveFuture;
        synchronized (this) {
            snapshot = getSettings().copy();
            path = getSettingsPath();
            lastSaveFuture = lastSaveFuture.handle((v, ex) -> null)
                    .thenRunAsync(() -> writeSettingsToDisk(path, snapshot));
            saveFuture = lastSaveFuture;
        }
        eventPublisher.publishEvent(new SettingsChangedEvent(snapshot));
        if (!SwingUtilities.isEventDispatchThread()) {
            saveFuture.join();
        }
    }

    private void writeSettingsToDisk(Path path, AppSettings currentSettings) {
        synchronized (writeLock) {
            try {
                PathUtils.createParentDirectories(path);
                mapper.writeValue(path.toFile(), currentSettings);
            } catch (IOException e) {
                log.error("Failed to save settings: {}", e.getMessage(), e);
            }
        }
    }

    CompletableFuture<Void> getLastSaveFuture() {
        return lastSaveFuture;
    }

    public synchronized void setSettingsPathOverride(Path path) {
        this.settingsPathOverride = path;
        this.settings = null;
    }

    private void loadSettings() {
        settings = loadFromDisk(getSettingsPath(), mapper);
    }

    private Path getSettingsPath() {
        return settingsPathOverride != null ? settingsPathOverride : getDefaultSettingsPath();
    }
}
