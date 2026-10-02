package org.roxycode.app.ui;

import net.miginfocom.swing.MigLayout;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.explore.ExploreManager;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.SystemToolService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.ai.workflow.WorkflowService;
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
    private final org.roxycode.app.ai.JexlTool jexlTool;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentArea = new JPanel(cardLayout);

    public MainFrame(SettingsService settingsService, AiService aiService, SystemToolService toolService, 
                    ProjectService projectService, GitService gitService, EnvironmentService envService,
                    JexlServiceRegistry jexlServiceRegistry, ExploreManager exploreManager, WorkflowService workflowService, org.roxycode.app.ai.JexlTool jexlTool) {
        this.settingsService = settingsService;
        this.aiService = aiService;
        this.toolService = toolService;
        this.projectService = projectService;
        this.gitService = gitService;
        this.envService = envService;
        this.jexlServiceRegistry = jexlServiceRegistry;
        this.exploreManager = exploreManager;
        this.workflowService = workflowService;
        this.jexlTool = jexlTool;
        setupLaf();
        setupWindow();
        initComponents();
    }

    private void setupLaf() {
        SettingsPanel.applyTheme(settingsService.getSettings().getTheme());
    }

    private void setupWindow() {
        setTitle("RoxyCode");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1100, 800);
        setLocationRelativeTo(null);
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
        contentArea.add(new ChatPanel(aiService, exploreManager, jexlTool, workflowService), "CHAT");
        contentArea.add(createPlaceholderPanel("Plan Management"), "PLAN");
        contentArea.add(new ContextViewerPanel(jexlServiceRegistry), "CONTEXT");
        contentArea.add(new SystemToolsPanel(toolService), "TOOLS");
        contentArea.add(new SettingsPanel(settingsService), "SETTINGS");

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
}
