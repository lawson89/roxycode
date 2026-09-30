package org.roxycode.app;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.roxycode.app.ui.MainFrame;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.service.AiService;
import org.roxycode.app.model.AppSettings;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import javax.swing.SwingUtilities;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

@SpringBootApplication
public class RoxyCode {

    public static void main(String[] args) {
        // Pre-load API key to set system property for Spring AI auto-configuration
        try {
            String userHome = System.getProperty("user.home");
            Path path = Paths.get(userHome, ".roxycode", "settings.toml");
            if (path.toFile().exists()) {
                TomlMapper mapper = new TomlMapper();
                AppSettings settings = mapper.readValue(path.toFile(), AppSettings.class);
                if (settings.getGeminiApiKey() != null && !settings.getGeminiApiKey().isEmpty()) {
                    System.setProperty("spring.ai.google.genai.api-key", settings.getGeminiApiKey());
                }
            }
        } catch (Exception e) {
            // Ignore, default to application.properties
        }

        ConfigurableApplicationContext context = new SpringApplicationBuilder(RoxyCode.class)
                .headless(false)
                .run(args);

        SwingUtilities.invokeLater(() -> {
            SettingsService settingsService = context.getBean(SettingsService.class);
            AiService aiService = context.getBean(AiService.class);
            MainFrame frame = new MainFrame(settingsService, aiService);
            frame.setVisible(true);
        });
    }

}