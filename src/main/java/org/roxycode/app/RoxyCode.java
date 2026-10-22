package org.roxycode.app;

import java.awt.GraphicsEnvironment;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
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

@SpringBootApplication
public class RoxyCode {

    private static final Logger log = LoggerFactory.getLogger(RoxyCode.class);
    static final String SETTINGS_PROPERTY_SOURCE = "roxycodeSettingsProperties";
    static final String PROP_GEMINI_API_KEY = "spring.ai.google.genai.api-key";
    static final String PROP_GEMINI_MODEL = "spring.ai.google.genai.chat.options.model";
    static final String DEFAULT_GEMINI_MODEL = AppSettings.DEFAULT_GEMINI_MODEL;

    public static void main(String[] args) {
        String theme = AppSettings.DEFAULT_THEME;
        AppSettings settings = null;

        try {
            settings = SettingsService.loadFromDisk();
            if (settings.getTheme() != null && !settings.getTheme().isBlank()) {
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
                SwingUtilities.invokeAndWait(
                        () -> {
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
            context =
                    new SpringApplicationBuilder(RoxyCode.class)
                            .headless(false)
                            .initializers(createSettingsInitializer(initialSettings))
                            .run(args);
            Runtime.getRuntime().addShutdownHook(new Thread(context::close));
        } catch (RuntimeException e) {
            disposeSplash(splashRef.get());
            throw e;
        }

        SwingUtilities.invokeLater(
                () -> {
                    SplashScreenWindow splash = splashRef.get();
                    try {
                        if (splash != null) {
                            splash.setStatus("Building workspace UI...");
                        }

                        MainFrame frame = context.getBean(MainFrame.class);
                        frame.setVisible(true);
                    } finally {
                        disposeSplash(splash);
                    }
                });
    }

    public static ApplicationContextInitializer<ConfigurableApplicationContext>
            createSettingsInitializer(AppSettings settings) {
        return applicationContext -> {
            ConfigurableEnvironment env = applicationContext.getEnvironment();
            Map<String, Object> props = new HashMap<>();
            if (settings != null
                    && settings.getGeminiApiKey() != null
                    && !settings.getGeminiApiKey().isEmpty()) {
                props.put(PROP_GEMINI_API_KEY, settings.getGeminiApiKey());
            }
            String model =
                    (settings != null
                                    && settings.getGeminiModel() != null
                                    && !settings.getGeminiModel().isEmpty())
                            ? settings.getGeminiModel()
                            : DEFAULT_GEMINI_MODEL;
            props.put(PROP_GEMINI_MODEL, model);
            env.getPropertySources()
                    .addFirst(new MapPropertySource(SETTINGS_PROPERTY_SOURCE, props));
        };
    }

    private static void disposeSplash(SplashScreenWindow splash) {
        if (splash == null) {
            return;
        }
        Edt.run(splash::dispose);
    }
}
