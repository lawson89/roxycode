package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.service.SystemToolService;
import org.springframework.ai.chat.memory.ChatMemory;

/** Main application window for RoxyCode. */
public final class MainFrame extends JFrame {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MainFrame.class);
    private static final long serialVersionUID = 1L;

    private static final String QA_VIEW = "QA_VIEW";
    private static final String TASK_VIEW = "TASK_VIEW";
    private static final String CACHE_VIEW = "CACHE";
    private static final String API_VIEW = "API";
    private static final String TOOLS_VIEW = "TOOLS";
    private static final String SETTINGS_VIEW = "SETTINGS";
    private static final String BUILD_CONFIG_VIEW = "BUILD_CONFIG";
    private static final String CONTEXT_MGR_VIEW = "CONTEXT_MGR";
    private static final String LAYOUT_CENTER = "center";

    private final transient SettingsService settingsService;
    private final transient AiService aiService;
    private final transient SystemToolService toolService;
    private final transient ProjectService projectService;

    private final transient GitService gitService;
    private final transient EnvironmentService envService;
    private final transient JexlServiceRegistry jexlServiceRegistry;
    private final transient WorkflowService workflowService;
    private final transient PlanService planManagerService;
    private final transient org.roxycode.app.events.TurnEventBridge turnEventBridge;
    private final transient org.roxycode.app.ai.services.cache.RepoMapPackerService packerService;
    private final transient org.roxycode.app.ai.services.cache.ProjectCacheMetaService
            cacheMetaService;
    private final transient ChatMemory chatMemory;
    private final transient org.roxycode.app.service.PromptService promptService;
    private final transient GenericBuildToolService buildToolService;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentArea = new JPanel(cardLayout);

    private GitChangesPanel gitChangesPanel;
    private ChatPanel qaChatPanel;
    private ChatPanel taskChatPanel;
    private JTabbedPane qaTabs;
    private JTabbedPane taskTabs;
    private HeaderPanel header;
    private JLabel planLabel;
    private JPanel progressTracker;
    private final List<WorkflowPhaseIndicator> indicators = new ArrayList<>();

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
            org.roxycode.app.service.PromptService promptService,
            GenericBuildToolService buildToolService,
            ChatMemory chatMemory) {
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
        this.promptService = promptService;

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
                    if (gitChangesPanel != null) {
                        gitChangesPanel.refresh();
                    }
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
                            qaChatPanel.focusInput();
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
        SidebarPanel sidebar = new SidebarPanel(this::showCard);

        // Main Workspace
        JPanel workspace =
                new JPanel(new MigLayout("fill, insets 0", "[fill, grow]", "[]0[fill, grow]0[]"));

        header = new HeaderPanel(projectService, gitService, settingsService);

        StatusPanel statusBar = new StatusPanel(envService);

        // QA Workspace (Explore)
        JPanel qaViewPanel = new JPanel(new BorderLayout());
        JPanel qaHeaderBar =
                new JPanel(
                        new MigLayout("insets 8 20 8 20, fillx", "[center, grow]", LAYOUT_CENTER));
        qaHeaderBar.setBorder(
                BorderFactory.createMatteBorder(
                        0, 0, 1, 0, UIManager.getColor("Component.borderColor")));

        JLabel mentorModeLabel = new JLabel("READ-ONLY MENTOR MODE");
        mentorModeLabel.putClientProperty(
                FlatClientProperties.STYLE, "font: bold -1; foreground: $Label.disabledForeground");
        mentorModeLabel.setIcon(
                FontIcon.of(Codicons.SEARCH, 16, UIManager.getColor("Label.disabledForeground")));
        qaHeaderBar.add(mentorModeLabel, LAYOUT_CENTER);

        this.qaTabs = new JTabbedPane();
        this.qaChatPanel =
                new ChatPanel(
                        aiService,
                        workflowService,
                        turnEventBridge,
                        chatMemory,
                        "qa",
                        WorkflowPhase.EXPLORE,
                        false,
                        settingsService,
                        planManagerService);
        qaTabs.addTab("Chat", qaChatPanel);
        qaTabs.addTab("History", new HistoryPanel(turnEventBridge, chatMemory, "qa"));
        qaTabs.addChangeListener(
                e -> {
                    if (qaChatPanel.equals(qaTabs.getSelectedComponent())) {
                        SwingUtilities.invokeLater(qaChatPanel::focusInput);
                    }
                });
        qaViewPanel.add(qaHeaderBar, BorderLayout.NORTH);
        qaViewPanel.add(qaTabs, BorderLayout.CENTER);

        // Task Workspace (Plan/Code/Review)
        JPanel taskViewPanel = new JPanel(new BorderLayout());
        JPanel taskHeaderBar =
                new JPanel(new MigLayout("insets 6 20 6 20, fillx", "[grow]", LAYOUT_CENTER));
        taskHeaderBar.setBorder(
                BorderFactory.createMatteBorder(
                        0, 0, 1, 0, UIManager.getColor("Component.borderColor")));

        progressTracker = new JPanel(new MigLayout("insets 0, gapx 4", "[]", LAYOUT_CENTER));
        progressTracker.setOpaque(false);
        for (WorkflowPhase phase : WorkflowPhase.values()) {
            if (phase == WorkflowPhase.EXPLORE) continue;
            indicators.add(new WorkflowPhaseIndicator(phase, "font: bold -1"));
        }
        taskHeaderBar.add(progressTracker, "split 2, center");

        planLabel = new JLabel();
        planLabel.putClientProperty(
                FlatClientProperties.STYLE, "font: bold -2; foreground: $Component.accentColor");
        taskHeaderBar.add(planLabel, "gapleft 16, center, hidemode 3");

        workflowService.addPhaseListener(this::updateActivePhase);
        planManagerService.addListener(
                plan -> updateActivePhase(workflowService.getCurrentPhase()));
        updateActivePhase(workflowService.getCurrentPhase());

        this.taskTabs = new JTabbedPane();
        this.taskChatPanel =
                new ChatPanel(
                        aiService,
                        workflowService,
                        turnEventBridge,
                        chatMemory,
                        "task",
                        null,
                        true,
                        settingsService,
                        planManagerService);
        this.taskChatPanel.addLoadingListener(sidebar::setTaskBusy);
        workflowService.addTransitionRequestListener(
                (current, requested, reason) ->
                        SwingUtilities.invokeLater(
                                () -> sidebar.setTaskWaitingApproval(requested != null)));
        workflowService.addPhaseListener(
                phase ->
                        SwingUtilities.invokeLater(
                                () ->
                                        sidebar.setTaskWaitingApproval(
                                                workflowService.getPendingPhase() != null)));
        taskTabs.addTab("Chat", taskChatPanel);
        taskTabs.addTab("Plan", new PlanPanel(planManagerService, workflowService));
        gitChangesPanel = new GitChangesPanel(gitService);
        if (turnEventBridge != null) {
            turnEventBridge.addFileChangedListener(
                    event -> SwingUtilities.invokeLater(gitChangesPanel::refresh));
        }
        taskTabs.addTab("Git", gitChangesPanel);
        taskTabs.addTab("History", new HistoryPanel(turnEventBridge, chatMemory, "task"));
        taskTabs.addChangeListener(
                e -> {
                    if (taskChatPanel.equals(taskTabs.getSelectedComponent())) {
                        SwingUtilities.invokeLater(taskChatPanel::focusInput);
                    } else if (gitChangesPanel.equals(taskTabs.getSelectedComponent())) {
                        gitChangesPanel.refresh();
                    }
                });
        taskViewPanel.add(taskHeaderBar, BorderLayout.NORTH);
        taskViewPanel.add(taskTabs, BorderLayout.CENTER);

        // Content Area Panels
        contentArea.add(qaViewPanel, QA_VIEW);
        contentArea.add(taskViewPanel, TASK_VIEW);
        contentArea.add(
                new CodebaseCachePanel(packerService, cacheMetaService, promptService), CACHE_VIEW);
        contentArea.add(new JexlApiPanel(jexlServiceRegistry), API_VIEW);
        contentArea.add(new SystemToolsPanel(toolService), TOOLS_VIEW);
        contentArea.add(new SettingsPanel(settingsService), SETTINGS_VIEW);
        contentArea.add(
                new BuildConfigPanel(buildToolService, settingsService, projectService),
                BUILD_CONFIG_VIEW);
        contentArea.add(new ContextManagerPanel(promptService), CONTEXT_MGR_VIEW);

        workspace.add(header, "h 36!, wrap");
        workspace.add(contentArea, "grow, wrap");
        workspace.add(statusBar, "h 30!");

        add(sidebar, "growy");
        add(workspace, "grow");

        // Default to QA View
        showCard(QA_VIEW);
    }

    private void updateActivePhase(WorkflowPhase currentPhase) {
        ImplementationPlan plan = planManagerService.getCurrentPlan();
        if (plan != null && plan.title() != null && !plan.title().isBlank()) {
            planLabel.setText(plan.title().toUpperCase(Locale.ROOT));
            planLabel.setVisible(true);
        } else {
            planLabel.setVisible(false);
        }

        progressTracker.removeAll();
        var visitedPhases = workflowService.getVisitedPhases();
        for (int i = 0; i < indicators.size(); i++) {
            WorkflowPhaseIndicator indicator = indicators.get(i);
            boolean isActive = indicator.getPhase() == currentPhase;
            boolean isCompleted =
                    visitedPhases != null && visitedPhases.contains(indicator.getPhase());
            indicator.setActive(isActive);
            indicator.setCompleted(isCompleted);
            if (i > 0) {
                progressTracker.add(
                        createConnectorLine(isActive || isCompleted), "w 24!, h 2!, center");
            }
            progressTracker.add(indicator);
        }
        progressTracker.revalidate();
        progressTracker.repaint();
    }

    private JPanel createConnectorLine(boolean reached) {
        JPanel line = new JPanel();
        line.setOpaque(true);
        Color accentColor = UIManager.getColor("Component.accentColor");
        Color borderColor = UIManager.getColor("Component.borderColor");
        if (reached) {
            line.setBackground(accentColor != null ? accentColor : Color.BLUE);
        } else {
            line.setBackground(borderColor != null ? borderColor : Color.GRAY);
        }
        return line;
    }

    private void showCard(String cardName) {
        cardLayout.show(contentArea, cardName);
        if (QA_VIEW.equals(cardName) && qaChatPanel != null) {
            if (qaTabs != null) {
                qaTabs.setSelectedComponent(qaChatPanel);
            }
            SwingUtilities.invokeLater(qaChatPanel::focusInput);
        } else if (TASK_VIEW.equals(cardName)) {
            if (gitChangesPanel != null) {
                gitChangesPanel.refresh();
            }
            if (taskChatPanel != null) {
                if (taskTabs != null) {
                    taskTabs.setSelectedComponent(taskChatPanel);
                }
                SwingUtilities.invokeLater(taskChatPanel::focusInput);
            }
        }
    }

    private void updateTitle() {
        setTitle("RoxyCode - [" + projectService.getProjectName() + "]");
    }
}
