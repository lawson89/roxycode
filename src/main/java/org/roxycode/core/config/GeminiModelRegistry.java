package org.roxycode.core.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.roxycode.core.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Context
public class GeminiModelRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(GeminiModelRegistry.class);
    private final ObjectMapper tomlMapper;

    private final String tomlPath;
    private List<GeminiModel> models = new ArrayList<>();

    public GeminiModelRegistry(@Named("toml") ObjectMapper tomlMapper,
                               SettingsService settingsService) {
        LOG.info("Creating GeminiModelRegistry");
        this.tomlMapper = tomlMapper;
        this.tomlPath = settingsService.getRoxyHome().resolve("config").resolve("models.toml").toAbsolutePath().toString();
    }

    @PostConstruct
    void loadModels() {
        try {
            File file = new File(tomlPath);
            if (!file.exists()) {
                LOG.error("Model configuration file not found at: {}", tomlPath);
                return;
            }

            Map<String, GeminiModel> modelMap = tomlMapper.readValue(
                    file,
                    new TypeReference<>() {
                    }
            );

            this.models = new ArrayList<>(modelMap.values());
            LOG.info("Successfully loaded {} Gemini models from TOML.", models.size());
        } catch (IOException e) {
            LOG.error("Failed to parse Gemini models TOML", e);
        }
    }

    public List<GeminiModel> getAllModels() {
        return models;
    }

    public Optional<GeminiModel> getModelByName(String apiName) {
        return models.stream()
                .filter(m -> m.getApiName().equalsIgnoreCase(apiName))
                .findFirst();
    }
}
