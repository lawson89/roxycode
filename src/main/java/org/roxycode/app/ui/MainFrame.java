package org.roxycode.app.ui;

import java.awt.*;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.service.ProjectAnalysisService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.service.SystemToolService;
import org.springframework.ai.chat.memory.ChatMemory;

/** Main application window for RoxyCode. */
public final class MainFrame extends JFrame {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MainFrame.class);
  private static final long serialVersionUID = 1L;

  private final transient SettingsService settingsService;
  private final transient AiService aiService;
  private final transient SystemToolService toolService;
  private final transient ProjectService projectService;
  private final transient ProjectAnalysisService projectAnalysisService;

  private final transient GitService gitService;
  private final transient EnvironmentService envService;
  private final transient JexlServiceRegistry jexlServiceRegistry;
  private final transient WorkflowService workflowService;
  private final transient PlanService planManagerService;
  private final transient org.roxycode.app.events.TurnEventBridge turnEventBridge;
  private final transient org.roxycode.app.ai.services.cache.RepoMapPackerService packerService;
  private final transient org.roxycode.app.ai.services.cache.ProjectCacheMetaService cacheMetaService;
  private final transient org.roxycode.app.ai.services.cache.GeminiCacheService geminiCacheService;
  private final transient ChatMemory chatMemory;
  private final transient org.roxycode.app.service.PromptService promptService;
  private final transient GenericBuildToolService buildToolService;
  private final CardLayout cardLayout = new CardLayout();
  private final JPanel contentArea = new JPanel(cardLayout);

  private GitChangesPanel gitChangesPanel;
  private ChatPanel qaChatPanel;
  private HeaderPanel header;

  public MainFrame(
      SettingsService settingsService,
      AiService aiService,
      SystemToolService toolService,
      ProjectService projectService,
      GitService gitService,
      EnvironmentService envService,
      JexlServiceRegistry jexlServiceRegistry,
      WorkflowService workflowService,
      PlanService planManagerService,
      org.roxycode.app.events.TurnEventBridge turnEventBridge,
      org.roxycode.app.ai.services.cache.RepoMapPackerService packerService,
      org.roxycode.app.ai.services.cache.ProjectCacheMetaService cacheMetaService,
      org.roxycode.app.ai.services.cache.GeminiCacheService geminiCacheService,
      org.roxycode.app.service.PromptService promptService,
      GenericBuildToolService buildToolService,
      ChatMemory chatMemory,
      ProjectAnalysisService projectAnalysisService) {
    this.settingsService = settingsService;
    this.aiService = aiService;
    this.toolService = toolService;
    this.projectService = projectService;
    this.gitService = gitService;
    this.envService = envService;
    this.jexlServiceRegistry = jexlServiceRegistry;
    this.workflowService = workflowService;
    this.planManagerService = planManagerService;
    this.turnEventBridge = turnEventBridge;
    this.packerService = packerService;
    this.cacheMetaService = cacheMetaService;
    this.geminiCacheService = geminiCacheService;
    this.promptService = promptService;
    this.projectAnalysisService = projectAnalysisService;

    this.buildToolService = buildToolService;
    this.chatMemory = chatMemory;
    setupLaf();
    setupWindow();
    initComponents();
    projectService.addProjectListener(
        path -> {
          updateTitle();
          chatMemory.clear("qa:default");
          chatMemory.clear("task:default");
        });
  }

  private void setupLaf() {
    SettingsPanel.applyTheme(settingsService.getSettings().getTheme());
  }

  private void setupWindow() {
    updateTitle();
    setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    setSize(1200, 900);
    setLocationRelativeTo(null);

    addWindowListener(
        new java.awt.event.WindowAdapter() {
          @Override
          public void windowOpened(java.awt.event.WindowEvent e) {
            if (qaChatPanel != null) {
              qaChatPanel.getInputArea().requestFocusInWindow();
            }
          }
        });

    try {
      java.net.URL iconUrl = getClass().getResource("/static/images/roxy.png");
      if (iconUrl != null) {
        setIconImage(new ImageIcon(iconUrl).getImage());
      }
    } catch (Exception e) {
      if (log.isTraceEnabled()) log.trace("Failed to load icon: {}", e.getMessage());
    }
  }

  private void initComponents() {
    setLayout(new MigLayout("fill, insets 0", "[200!]0[fill, grow]", "[fill, grow]"));

    // Sidebar
    SidebarPanel sidebar = new SidebarPanel(workflowService, this::showCard);

    // Main Workspace
    JPanel workspace =
        new JPanel(new MigLayout("fill, insets 0", "[fill, grow]", "[]0[fill, grow]0[]"));

    header =
        new HeaderPanel(
            projectService,
            gitService,
            settingsService,
            workflowService,
            planManagerService,
            this::showCard);

        StatusPanel statusBar = new StatusPanel(envService, chatMemory);

    // QA Workspace (Explore)
    JTabbedPane qaTabs = new JTabbedPane();
    this.qaChatPanel =
        new ChatPanel(
            aiService,
            workflowService,
            turnEventBridge,
            chatMemory,
            "qa",
            WorkflowPhase.EXPLORE,
            false);
    qaTabs.addTab("Chat", qaChatPanel);
    qaTabs.addTab(
        "History", new HistoryPanel(turnEventBridge, chatMemory, geminiCacheService, "qa"));

    // Task Workspace (Plan/Code/Review)
    JTabbedPane taskTabs = new JTabbedPane();
    taskTabs.addTab(
        "Chat",
        new ChatPanel(aiService, workflowService, turnEventBridge, chatMemory, "task", null, true));
    taskTabs.addTab("Plan", new PlanPanel(planManagerService, workflowService));
    gitChangesPanel = new GitChangesPanel(gitService);
    taskTabs.addTab("Git", gitChangesPanel);
    taskTabs.addTab(
        "History", new HistoryPanel(turnEventBridge, chatMemory, geminiCacheService, "task"));

    // Content Area Panels
    contentArea.add(qaTabs, "QA_VIEW");
    contentArea.add(taskTabs, "TASK_VIEW");
    contentArea.add(
        new CodebaseCachePanel(
            packerService,
            cacheMetaService,
            geminiCacheService,
            promptService,
            workflowService,
            jexlServiceRegistry,
            gitService,
            projectAnalysisService),
        "CACHE");
    contentArea.add(new JexlApiPanel(jexlServiceRegistry), "API");
    contentArea.add(new SystemToolsPanel(toolService), "TOOLS");
    contentArea.add(new SettingsPanel(settingsService), "SETTINGS");
    contentArea.add(
        new BuildConfigPanel(buildToolService, settingsService, projectService), "BUILD_CONFIG");
    contentArea.add(new ContextManagerPanel(promptService), "CONTEXT_MGR");

    workspace.add(header, "h 80!, wrap");
    workspace.add(contentArea, "grow, wrap");
    workspace.add(statusBar, "h 30!");

        add(sidebar, "growy");
    add(workspace, "grow");

    // Default to QA View
    showCard("QA_VIEW");
  }

  private void showCard(String cardName) {
    cardLayout.show(contentArea, cardName);
    if (header != null) {
      header.setMentorMode("QA_VIEW".equals(cardName));
    }
    if ("TASK_VIEW".equals(cardName) && gitChangesPanel != null) {
      gitChangesPanel.refresh();
    }
  }

  private void updateTitle() {
    setTitle("RoxyCode - [" + projectService.getProjectName() + "]");
  }
}
