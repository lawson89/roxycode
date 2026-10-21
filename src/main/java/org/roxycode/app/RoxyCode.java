package org.roxycode.app;

import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.ui.MainFrame;
import org.roxycode.app.ui.SettingsPanel;
import org.roxycode.app.ui.SplashScreenWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class RoxyCode {

    private static final Logger log = LoggerFactory.getLogger(RoxyCode.class);

    public static void main(String[] args) {
        String theme = "FlatLaf Dark";

        // Pre-load settings to set system properties for Spring AI auto-configuration and UI theme
        try {
            AppSettings settings = SettingsService.loadFromDisk();
            if (settings.getGeminiApiKey() != null && !settings.getGeminiApiKey().isEmpty()) {
                System.setProperty("spring.ai.google.genai.api-key", settings.getGeminiApiKey());
            }
            if (settings.getGeminiModel() != null && !settings.getGeminiModel().isEmpty()) {
                System.setProperty(
                        "spring.ai.google.genai.chat.options.model", settings.getGeminiModel());
            }
            if (settings.getTheme() != null && !settings.getTheme().isBlank()) {
                theme = settings.getTheme();
            }
        } catch (Exception e) {
            log.warn("Failed to pre-load settings for Spring AI configuration: {}", e.getMessage());
            // Ignore, default to application.properties
        }

        // Fallback for model if not set via settings
        if (System.getProperty("spring.ai.google.genai.chat.options.model") == null) {
            System.setProperty("spring.ai.google.genai.chat.options.model", "gemini-1.5-flash");
        }

        final String initialTheme = theme;
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
            context = new SpringApplicationBuilder(RoxyCode.class).headless(false).run(args);
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

    private static void disposeSplash(SplashScreenWindow splash) {
        if (splash == null) {
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            splash.dispose();
        } else {
            SwingUtilities.invokeLater(splash::dispose);
        }
    }
}
