package org.roxycode.app;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.swing.SwingUtilities;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.ui.MainFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class RoxyCode {

  private static final Logger log = LoggerFactory.getLogger(RoxyCode.class);

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
          System.setProperty(
              "spring.ai.google.genai.chat.options.model", settings.getGeminiModel());
        }
      }
    } catch (Exception e) {
      log.warn("Failed to pre-load settings for Spring AI configuration: {}", e.getMessage());
      // Ignore, default to application.properties
    }

    // Fallback for model if not set via settings
    if (System.getProperty("spring.ai.google.genai.chat.options.model") == null) {
      System.setProperty("spring.ai.google.genai.chat.options.model", "gemini-1.5-flash");
    }

    @SuppressWarnings("PMD.CloseResource")
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(RoxyCode.class).headless(false).run(args);
    Runtime.getRuntime().addShutdownHook(new Thread(context::close));

    SwingUtilities.invokeLater(
        () -> {
          SettingsService settingsService = context.getBean(SettingsService.class);
          AiService aiService = context.getBean(AiService.class);
          org.roxycode.app.service.SystemToolService toolService =
              context.getBean(org.roxycode.app.service.SystemToolService.class);
          ProjectService projectService = context.getBean(ProjectService.class);
          GitService gitService = context.getBean(GitService.class);
          EnvironmentService envService = context.getBean(EnvironmentService.class);
          JexlServiceRegistry jexlServiceRegistry = context.getBean(JexlServiceRegistry.class);
          WorkflowService workflowService = context.getBean(WorkflowService.class);
          PlanService planManagerService = context.getBean(PlanService.class);
          org.roxycode.app.events.TurnEventBridge turnEventBridge =
              context.getBean(org.roxycode.app.events.TurnEventBridge.class);
          org.roxycode.app.ai.services.cache.RepoMapPackerService packerService =
              context.getBean(org.roxycode.app.ai.services.cache.RepoMapPackerService.class);
          org.roxycode.app.ai.services.cache.ProjectCacheMetaService cacheMetaService =
              context.getBean(org.roxycode.app.ai.services.cache.ProjectCacheMetaService.class);
          org.roxycode.app.ai.services.cache.GeminiCacheService geminiCacheService =
              context.getBean(org.roxycode.app.ai.services.cache.GeminiCacheService.class);
          org.roxycode.app.service.PromptService promptService =
              context.getBean(org.roxycode.app.service.PromptService.class);
          GenericBuildToolService buildToolService = context.getBean(GenericBuildToolService.class);
          org.springframework.ai.chat.memory.ChatMemory chatMemory =
              context.getBean(org.springframework.ai.chat.memory.ChatMemory.class);
          org.roxycode.app.service.ProjectAnalysisService projectAnalysisService =
              context.getBean(org.roxycode.app.service.ProjectAnalysisService.class);

          MainFrame frame =
              new MainFrame(
                  settingsService,
                  aiService,
                  toolService,
                  projectService,
                  gitService,
                  envService,
                  jexlServiceRegistry,
                  workflowService,
                  planManagerService,
                  turnEventBridge,
                  packerService,
                  cacheMetaService,
                  geminiCacheService,
                  promptService,
                  buildToolService,
                  chatMemory,
                  projectAnalysisService);
          frame.setVisible(true);
        });
  }
}
