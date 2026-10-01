package org.roxycode.app;

import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.explore.ExploreManager;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.roxycode.app.ui.MainFrame;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.model.AppSettings;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import javax.swing.SwingUtilities;
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
                if (settings.getGeminiModel() != null && !settings.getGeminiModel().isEmpty()) {
                    System.setProperty("spring.ai.google.genai.chat.options.model", settings.getGeminiModel());
                }
            }
        } catch (Exception e) {
            // Ignore, default to application.properties
        }

        // Fallback for model if not set via settings
        if (System.getProperty("spring.ai.google.genai.chat.options.model") == null) {
            System.setProperty("spring.ai.google.genai.chat.options.model", "gemini-1.5-flash");
        }

        ConfigurableApplicationContext context = new SpringApplicationBuilder(RoxyCode.class)
                .headless(false)
                .run(args);

        SwingUtilities.invokeLater(() -> {
            SettingsService settingsService = context.getBean(SettingsService.class);
            AiService aiService = context.getBean(AiService.class);
            org.roxycode.app.service.SystemToolService toolService = context.getBean(org.roxycode.app.service.SystemToolService.class);
            ProjectService projectService = context.getBean(ProjectService.class);
            GitService gitService = context.getBean(GitService.class);
            EnvironmentService envService = context.getBean(EnvironmentService.class);
            JexlServiceRegistry jexlServiceRegistry = context.getBean(JexlServiceRegistry.class);
            ExploreManager exploreManager = context.getBean(ExploreManager.class);
            
            MainFrame frame = new MainFrame(settingsService, aiService, toolService, projectService, gitService, envService, jexlServiceRegistry, exploreManager);
            frame.setVisible(true);
        });
    }

}