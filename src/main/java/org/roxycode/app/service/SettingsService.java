package org.roxycode.app.service;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.swing.SwingUtilities;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.concurrent.ConcurrentException;
import org.apache.commons.lang3.concurrent.LazyInitializer;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.GeminiModelConfig;
import org.roxycode.app.model.GeminiModels;
import org.roxycode.app.model.ThinkingLevel;
import org.roxycode.app.util.Edt;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Manages persistence and retrieval of global application settings and Gemini model configurations.
 */
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
        this.mapper = Objects.requireNonNullElse(mapper, AiConfig.SHARED_TOML_MAPPER);
        this.eventPublisher = Objects.requireNonNullElse(eventPublisher, event -> {
        });
    }

    public static Path getDefaultRoxyHomeDir() {
        return Paths.get(System.getProperty("user.home"), SETTINGS_DIR);
    }

    public static Path getDefaultSettingsPath() {
        return getDefaultRoxyHomeDir().resolve(SETTINGS_FILE);
    }

    /**
     * Returns the RoxyCode home directory (usually ~/.roxycode).
     * 
     * @return the home directory path
     */
    public synchronized Path getRoxyHomeDir() {
        if (settingsPathOverride != null && settingsPathOverride.getParent() != null) {
            return settingsPathOverride.getParent();
        }
        return getDefaultRoxyHomeDir();
    }

    public static AppSettings loadFromDisk() {
        return loadFromDisk(getDefaultSettingsPath(), AiConfig.SHARED_TOML_MAPPER);
    }

    public static AppSettings loadFromDisk(Path path, TomlMapper mapper) {
        return PathUtils.readTomlOrDefault(path, AppSettings.class, new AppSettings(), mapper);
    }

    /**
     * Returns the list of available Gemini models.
     * 
     * @return the list of model configurations
     */
    public List<GeminiModelConfig> getAvailableModels() {
        try {
            return availableModelsInitializer.get();
        } catch (ConcurrentException e) {
            log.error("Failed to load Gemini models: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private List<GeminiModelConfig> loadAvailableModels() {
        try {
            String content = PathUtils.readClasspathResourceUtf8("models.toml", SettingsService.class);
            GeminiModels geminiModels = mapper.readValue(content, GeminiModels.class);
            if (geminiModels != null && geminiModels.models() != null) {
                return List.copyOf(geminiModels.models());
            }
            return List.of();
        } catch (IOException e) {
            log.error("Failed to load Gemini models: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Resolves a model configuration by its API name.
     * 
     * @param apiName
     *            the API name of the model
     * @return the model configuration, or null if not found
     */
    public GeminiModelConfig resolveModelConfig(String apiName) {
        String target = StringUtils.trimToEmpty(apiName);
        if (target.isEmpty()) {
            target = resolveGlobalGeminiModel(this);
        }
        for (GeminiModelConfig model : getAvailableModels()) {
            if (model != null && target.equalsIgnoreCase(StringUtils.trimToEmpty(model.apiName()))) {
                return model;
            }
        }
        return GeminiModelConfig.defaultForApiName(target);
    }

    public static GeminiModelConfig resolveModelConfig(SettingsService settingsService, String apiName) {
        if (settingsService != null) {
            GeminiModelConfig cfg = settingsService.resolveModelConfig(apiName);
            if (cfg != null) {
                return cfg;
            }
        }
        return GeminiModelConfig.defaultForApiName(apiName);
    }

    public static String resolveGlobalGeminiModel(SettingsService settingsService) {
        if (settingsService != null && settingsService.getSettings() != null) {
            return StringUtils
                    .defaultIfBlank(settingsService.getSettings().getGeminiModel(), AppSettings.DEFAULT_GEMINI_MODEL)
                    .trim();
        }
        return AppSettings.DEFAULT_GEMINI_MODEL;
    }

    public static ThinkingLevel resolveGlobalThinkingLevel(SettingsService settingsService) {
        if (settingsService != null && settingsService.getSettings() != null) {
            return ThinkingLevel.fromString(settingsService.getSettings().getThinkingLevel());
        }
        return ThinkingLevel.DEFAULT;
    }

    public synchronized boolean hasSettingsFile() {
        return Files.exists(getSettingsPath());
    }

    /**
     * Checks whether the given settings have valid AI authentication credentials configured (either a non-blank Gemini
     * API key when in API Key mode, or a non-blank GCP Project ID and Vertex Location when in Vertex AI mode).
     *
     * @param settings
     *            the settings to inspect
     * @return true if valid credentials are configured for the active authentication mode
     */
    public static boolean hasConfiguredCredentials(AppSettings settings) {
        if (settings == null) {
            return false;
        }
        if (settings.isVertexAiEnabled()) {
            return StringUtils.isNotBlank(settings.getVertexProjectId())
                    && StringUtils.isNotBlank(settings.getVertexLocation());
        }
        return StringUtils.isNotBlank(settings.getGeminiApiKey());
    }

    /**
     * Checks whether the current settings have valid AI authentication credentials configured.
     *
     * @return true if valid credentials are configured for the active authentication mode
     */
    public synchronized boolean hasConfiguredCredentials() {
        return hasConfiguredCredentials(getSettings());
    }

    public synchronized boolean hasConfiguredApiKey() {
        return hasConfiguredCredentials();
    }

    public synchronized AppSettings getSettings() {
        if (settings == null) {
            settings = loadFromDisk(getSettingsPath(), mapper);
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
                    .thenRunAsync(() -> writeSettingsToDisk(path, snapshot), Edt.VIRTUAL_EXECUTOR);
            saveFuture = lastSaveFuture;
        }
        eventPublisher.publishEvent(new SettingsChangedEvent(snapshot));
        if (!SwingUtilities.isEventDispatchThread()) {
            saveFuture.join();
        }
    }

    public void saveSettingsSync() {
        AppSettings snapshot;
        Path path;
        CompletableFuture<Void> pendingSaveFuture;
        synchronized (this) {
            snapshot = getSettings().copy();
            path = getSettingsPath();
            pendingSaveFuture = lastSaveFuture;
        }
        pendingSaveFuture.handle((v, ex) -> null).join();
        writeSettingsToDisk(path, snapshot);
        eventPublisher.publishEvent(new SettingsChangedEvent(snapshot));
    }

    private void writeSettingsToDisk(Path path, AppSettings currentSettings) {
        synchronized (writeLock) {
            try {
                PathUtils.writeToml(path, currentSettings, mapper);
                PathUtils.setOwnerOnlyPermissionsIfPosix(path);
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

    private Path getSettingsPath() {
        return Objects.requireNonNullElseGet(settingsPathOverride, SettingsService::getDefaultSettingsPath);
    }
}
