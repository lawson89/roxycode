package org.roxycode.app.ui;

import net.miginfocom.swing.MigLayout;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.ai.services.ExploreManager;
import org.roxycode.app.ai.services.PlanManagerService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.SystemToolService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.ai.workflow.WorkflowService;
import org.springframework.ai.chat.memory.ChatMemory;
import com.formdev.flatlaf.FlatClientProperties;
import javax.swing.*;
import java.awt.*;

/**
 * Main application window for RoxyCode.
 */
public class MainFrame extends JFrame {

    private final SettingsService settingsService;
    private final AiService aiService;
    private final SystemToolService toolService;
    private final ProjectService projectService;
    private final GitService gitService;
    private final EnvironmentService envService;
    private final JexlServiceRegistry jexlServiceRegistry;
    private final ExploreManager exploreManager;
    private final WorkflowService workflowService;
    private final PlanManagerService planManagerService;
    private final org.roxycode.app.ai.JexlTool jexlTool;
    private final org.roxycode.app.events.TurnEventBridge turnEventBridge;
    private final org.roxycode.app.ai.services.cache.RepoMapPackerService packerService;
    private final org.roxycode.app.ai.services.cache.ProjectCacheMetaService cacheMetaService;
    private final org.roxycode.app.ai.services.cache.GeminiCacheService geminiCacheService;
    private final ChatMemory chatMemory;
    private final org.roxycode.app.service.PromptService promptService;
    private final GenericBuildToolService buildToolService;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentArea = new JPanel(cardLayout);

    public MainFrame(SettingsService settingsService, AiService aiService, SystemToolService toolService, 
                    ProjectService projectService, GitService gitService, EnvironmentService envService,
                    JexlServiceRegistry jexlServiceRegistry, ExploreManager exploreManager, WorkflowService workflowService, 
                    PlanManagerService planManagerService, org.roxycode.app.ai.JexlTool jexlTool,
                    org.roxycode.app.events.TurnEventBridge turnEventBridge,
                    org.roxycode.app.ai.services.cache.RepoMapPackerService packerService,
                    org.roxycode.app.ai.services.cache.ProjectCacheMetaService cacheMetaService,
                    org.roxycode.app.ai.services.cache.GeminiCacheService geminiCacheService,
                    org.roxycode.app.service.PromptService promptService,
                    GenericBuildToolService buildToolService, ChatMemory chatMemory) {
        this.settingsService = settingsService;
        this.aiService = aiService;
        this.toolService = toolService;
        this.projectService = projectService;
        this.gitService = gitService;
        this.envService = envService;
        this.jexlServiceRegistry = jexlServiceRegistry;
        this.exploreManager = exploreManager;
        this.workflowService = workflowService;
        this.planManagerService = planManagerService;
        this.jexlTool = jexlTool;
        this.turnEventBridge = turnEventBridge;
        this.packerService = packerService;
        this.cacheMetaService = cacheMetaService;
        this.geminiCacheService = geminiCacheService;
        this.promptService = promptService;
        this.buildToolService = buildToolService;
        this.chatMemory = chatMemory;
        setupLaf();
        setupWindow();
        initComponents();
        projectService.addProjectListener(path -> updateTitle());
    }

    private void setupLaf() {
        SettingsPanel.applyTheme(settingsService.getSettings().getTheme());
    }

    private void setupWindow() {
        updateTitle();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1100, 800);
        setLocationRelativeTo(null);

        try {
            java.net.URL iconUrl = getClass().getResource("/static/images/roxy.png");
            if (iconUrl != null) {
                setIconImage(new ImageIcon(iconUrl).getImage());
            }
        } catch (Exception e) {
            // Ignore icon loading errors
        }
    }

    private void initComponents() {
        setLayout(new MigLayout("fill, insets 0", "[200!]0[fill, grow]", "[fill, grow]"));

        // Sidebar
        SidebarPanel sidebar = new SidebarPanel(settingsService, cardName -> cardLayout.show(contentArea, cardName));

        // Main Workspace
        JPanel workspace = new JPanel(new MigLayout("fill, insets 0", "[fill, grow]", "[]0[]0[fill, grow]0[]"));
        
        HeaderPanel header = new HeaderPanel(projectService, gitService, settingsService, 
                cardName -> cardLayout.show(contentArea, cardName));
        JPanel phaseRow = new JPanel(new MigLayout("insets 5 20 5 20, fillx", "[center]", "center"));
        phaseRow.putClientProperty(FlatClientProperties.STYLE, "background: darken($Panel.background, 2%)");
        phaseRow.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")));
        phaseRow.add(new PhaseStepPanel(workflowService));

        StatusPanel statusBar = new StatusPanel(envService);

        // Content Area Panels
        contentArea.add(new ChatPanel(aiService, exploreManager, jexlTool, workflowService, turnEventBridge), "CHAT");
        contentArea.add(new HistoryPanel(turnEventBridge, jexlTool, chatMemory, geminiCacheService), "HISTORY");
        contentArea.add(new PlanPanel(planManagerService), "PLAN");
        contentArea.add(new CodebaseCachePanel(projectService, settingsService, packerService, cacheMetaService, geminiCacheService, promptService), "CACHE");
        contentArea.add(new JexlApiPanel(jexlServiceRegistry), "API");
        contentArea.add(new SystemToolsPanel(toolService), "TOOLS");
        contentArea.add(new SettingsPanel(settingsService), "SETTINGS");
        contentArea.add(new PromptPanel(promptService, settingsService), "PROMPT");
        contentArea.add(new BuildConfigPanel(buildToolService, settingsService, projectService), "BUILD_CONFIG");

        workspace.add(header, "h 60!, wrap");
        workspace.add(phaseRow, "h 40!, wrap");
        workspace.add(contentArea, "grow, wrap");
        workspace.add(statusBar, "h 30!");

        add(sidebar, "growy");
        add(workspace, "grow");
    }
    
    private JPanel createPlaceholderPanel(String text) {
        JPanel p = new JPanel(new MigLayout("fill", "[center]", "[center]"));
        p.add(new JLabel(text));
        return p;
    }

    private void updateTitle() {
        setTitle("RoxyCode - [" + projectService.getProjectName() + "]");
    }
}