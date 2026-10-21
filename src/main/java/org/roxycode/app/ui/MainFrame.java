package org.roxycode.app.ui;

import java.awt.CardLayout;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JPanel;
import net.miginfocom.swing.MigLayout;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Main application window for RoxyCode. */
@Component
@Lazy
public final class MainFrame extends JFrame {
    private static final Logger log = LoggerFactory.getLogger(MainFrame.class);
    private static final long serialVersionUID = 1L;

    static final String QA_VIEW = "QA_VIEW";
    static final String TASK_VIEW = "TASK_VIEW";
    private static final String CACHE_VIEW = "CACHE";
    private static final String API_VIEW = "API";
    private static final String TOOLS_VIEW = "TOOLS";
    private static final String SETTINGS_VIEW = "SETTINGS";
    private static final String BUILD_CONFIG_VIEW = "BUILD_CONFIG";
    private static final String CONTEXT_MGR_VIEW = "CONTEXT_MGR";

    private final transient SettingsService settingsService;
    private final transient ProjectService projectService;
    private final transient ChatMemory chatMemory;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentArea = new JPanel(cardLayout);

    private final HeaderPanel header;
    private final QaWorkspacePanel qaWorkspacePanel;
    private final TaskWorkspacePanel taskWorkspacePanel;

    public MainFrame(
            SettingsService settingsService,
            ProjectService projectService,
            ChatMemory chatMemory,
            HeaderPanel header,
            StatusPanel statusBar,
            QaWorkspacePanel qaWorkspacePanel,
            TaskWorkspacePanel taskWorkspacePanel,
            CodebaseCachePanel codebaseCachePanel,
            JexlApiPanel jexlApiPanel,
            SystemToolsPanel systemToolsPanel,
            SettingsPanel settingsPanel,
            BuildConfigPanel buildConfigPanel,
            ContextManagerPanel contextManagerPanel) {
        this.settingsService = settingsService;
        this.projectService = projectService;
        this.chatMemory = chatMemory;
        this.header = header;
        this.qaWorkspacePanel = qaWorkspacePanel;
        this.taskWorkspacePanel = taskWorkspacePanel;

        setupLaf();
        setupWindow();
        initComponents(
                statusBar,
                codebaseCachePanel,
                jexlApiPanel,
                systemToolsPanel,
                settingsPanel,
                buildConfigPanel,
                contextManagerPanel);
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        updateTitle();
        chatMemory.clear(ChatModel.QA_CONVERSATION_ID);
        chatMemory.clear(ChatModel.TASK_CONVERSATION_ID);
        if (qaWorkspacePanel != null && qaWorkspacePanel.getQaChatPanel() != null) {
            qaWorkspacePanel.getQaChatPanel().clearOutput();
        }
        if (taskWorkspacePanel != null) {
            if (taskWorkspacePanel.getTaskChatPanel() != null) {
                taskWorkspacePanel.getTaskChatPanel().clearOutput();
            }
            taskWorkspacePanel.refreshGitChanges();
        }
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
                        if (qaWorkspacePanel != null) {
                            qaWorkspacePanel.focusChatInput();
                        }
                    }
                });

        try {
            java.net.URL iconUrl = getClass().getResource("/static/images/roxy.png");
            if (iconUrl != null) {
                setIconImage(new ImageIcon(iconUrl).getImage());
            }
        } catch (Exception e) {
            if (log.isTraceEnabled()) {
                log.trace("Failed to load icon: {}", e.getMessage());
            }
        }
    }

    private void initComponents(
            StatusPanel statusBar,
            CodebaseCachePanel codebaseCachePanel,
            JexlApiPanel jexlApiPanel,
            SystemToolsPanel systemToolsPanel,
            SettingsPanel settingsPanel,
            BuildConfigPanel buildConfigPanel,
            ContextManagerPanel contextManagerPanel) {
        setLayout(new MigLayout("fill, insets 0", "[200!]0[fill, grow]", "[fill, grow]"));

        // Sidebar
        SidebarPanel sidebar = new SidebarPanel(this::showCard);
        taskWorkspacePanel.bindSidebar(sidebar);

        // Main Workspace
        JPanel workspace =
                new JPanel(new MigLayout("fill, insets 0", "[fill, grow]", "[]0[fill, grow]0[]"));

        // Content Area Panels
        contentArea.add(qaWorkspacePanel, QA_VIEW);
        contentArea.add(taskWorkspacePanel, TASK_VIEW);
        contentArea.add(codebaseCachePanel, CACHE_VIEW);
        contentArea.add(jexlApiPanel, API_VIEW);
        contentArea.add(systemToolsPanel, TOOLS_VIEW);
        contentArea.add(settingsPanel, SETTINGS_VIEW);
        contentArea.add(buildConfigPanel, BUILD_CONFIG_VIEW);
        contentArea.add(contextManagerPanel, CONTEXT_MGR_VIEW);

        workspace.add(header, "h 36!, wrap");
        workspace.add(contentArea, "grow, wrap");
        workspace.add(statusBar, "h 30!");

        add(sidebar, "growy");
        add(workspace, "grow");

        // Default to QA View
        showCard(QA_VIEW);
    }

    void showCard(String cardName) {
        cardLayout.show(contentArea, cardName);
        if (QA_VIEW.equals(cardName) && qaWorkspacePanel != null) {
            qaWorkspacePanel.focusChatInput();
        } else if (TASK_VIEW.equals(cardName) && taskWorkspacePanel != null) {
            taskWorkspacePanel.onViewShown();
        }
    }

    HeaderPanel getHeader() {
        return header;
    }

    QaWorkspacePanel getQaWorkspacePanel() {
        return qaWorkspacePanel;
    }

    TaskWorkspacePanel getTaskWorkspacePanel() {
        return taskWorkspacePanel;
    }

    private void updateTitle() {
        setTitle("RoxyCode - [" + projectService.getProjectName() + "]");
    }
}
