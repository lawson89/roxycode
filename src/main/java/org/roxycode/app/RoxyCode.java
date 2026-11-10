package org.roxycode.app;

import java.awt.GraphicsEnvironment;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.ui.MainFrame;
import org.roxycode.app.ui.SettingsPanel;
import org.roxycode.app.ui.SplashScreenWindow;
import org.roxycode.app.util.Edt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * The main entry point for the RoxyCode application. This class initializes the Spring Boot application context,
 * configures the system theme, and launches the main UI frame.
 */
@SpringBootApplication
public class RoxyCode {

    private static final Logger log = LoggerFactory.getLogger(RoxyCode.class);

    private static final String SETTINGS_PROPERTY_SOURCE = "roxycodeSettingsProperties";
    private static final String PROP_GEMINI_API_KEY = "spring.ai.google.genai.api-key";
    private static final String PROP_GEMINI_MODEL = "spring.ai.google.genai.chat.options.model";
    private static final String DEFAULT_GEMINI_MODEL = AppSettings.DEFAULT_GEMINI_MODEL;

    /**
     * Main entry point for the application.
     * 
     * @param args
     *            command line arguments
     */
    public static void main(String[] args) {
        String theme = AppSettings.DEFAULT_THEME;
        AppSettings settings = null;
        try {
            settings = SettingsService.loadFromDisk();
            if (StringUtils.isNotBlank(settings.getTheme())) {
                theme = settings.getTheme();
            }
        } catch (Exception e) {
            log.warn("Failed to pre-load settings for Spring AI configuration: {}", e.getMessage());
        }

        final String initialTheme = theme;
        final AppSettings initialSettings = settings;
        AtomicReference<SplashScreenWindow> splashRef = new AtomicReference<>();

        if (!GraphicsEnvironment.isHeadless()) {
            try {
                Edt.runAndWait(() -> {
                    SettingsPanel.applyTheme(initialTheme);
                    SplashScreenWindow splash = new SplashScreenWindow();
                    splash.setVisible(true);
                    splashRef.set(splash);
                });
            } catch (Exception e) {
                log.warn("Failed to initialize splash screen: {}", e.getMessage());
            }
        }

        @SuppressWarnings("PMD.CloseResource")
        ConfigurableApplicationContext context;
        try {
            context = new SpringApplicationBuilder(RoxyCode.class).headless(false)
                    .initializers(createSettingsInitializer(initialSettings)).run(args);
        } catch (RuntimeException e) {
            disposeSplash(splashRef.get());
            throw e;
        }

        SplashScreenWindow splash = splashRef.get();
        if (splash != null) {
            try {
                Edt.runAndWait(() -> splash.setStatus("Building workspace UI..."));
            } catch (Exception e) {
                log.debug("Failed to update splash status: {}", e.getMessage());
            }
        }

        Edt.run(() -> {
            try {
                MainFrame mainFrame = context.getBean(MainFrame.class);
                mainFrame.setVisible(true);
                SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> disposeSplash(splash)));
            } catch (RuntimeException e) {
                disposeSplash(splash);
                throw e;
            }
        });
    }

    private static ApplicationContextInitializer<ConfigurableApplicationContext> createSettingsInitializer(
            AppSettings settings) {
        return applicationContext -> {
            ConfigurableEnvironment env = applicationContext.getEnvironment();
            Map<String, Object> props = new HashMap<>();
            if (settings != null) {
                if (StringUtils.isNotBlank(settings.getGeminiApiKey())) {
                    props.put(PROP_GEMINI_API_KEY, settings.getGeminiApiKey());
                }
                String model = DEFAULT_GEMINI_MODEL;
                if (StringUtils.isNotBlank(settings.getGeminiModel())) {
                    model = settings.getGeminiModel();
                }
                props.put(PROP_GEMINI_MODEL, model);
            }
            env.getPropertySources().addFirst(new MapPropertySource(SETTINGS_PROPERTY_SOURCE, props));
        };
    }

    private static void disposeSplash(SplashScreenWindow splash) {
        if (splash != null) {
            Edt.run(splash::dispose);
        }
    }
}
