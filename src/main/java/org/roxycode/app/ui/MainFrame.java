package org.roxycode.app.ui;

import java.awt.CardLayout;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import net.miginfocom.swing.MigLayout;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.model.SystemTool;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.service.SystemToolService;
import org.roxycode.app.util.Edt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Lazy
public final class MainFrame extends JFrame {
    private static final Logger log = LoggerFactory.getLogger(MainFrame.class);
    private static final long serialVersionUID = 1L;

    static final String QA_VIEW = "QA_VIEW";
    static final String TASK_VIEW = "TASK_VIEW";
    private static final String CACHE_VIEW = "CACHE";
    private static final String API_VIEW = "API";
    static final String TOOLS_VIEW = "TOOLS";
    private static final String SETTINGS_VIEW = "SETTINGS";
    private static final String BUILD_CONFIG_VIEW = "BUILD_CONFIG";
    private static final String CONTEXT_MGR_VIEW = "CONTEXT_MGR";
    static final String MISSING_REQUIRED_TOOLS_TITLE = "Required System Tools Missing";
    static final String MISSING_REQUIRED_TOOLS_MESSAGE = "RoxyCode requires Ripgrep, Git, and Universal Ctags to function properly. Please review the System Tools page to install or configure any missing required tools.";

    private final transient SettingsService settingsService;
    private final transient ProjectService projectService;
    private final transient ChatMemory chatMemory;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentArea = new JPanel(cardLayout);

    private final HeaderPanel header;
    private final QaWorkspacePanel qaWorkspacePanel;
    private final TaskWorkspacePanel taskWorkspacePanel;
    private SidebarPanel sidebar;
    private String currentCard = QA_VIEW;
    private String lastMissingToolsMessage;
    private boolean pendingMissingToolsAlert;
    private transient Consumer<String> missingToolsDialogPresenter;

    public MainFrame(SettingsService settingsService, ProjectService projectService, ChatMemory chatMemory,
            HeaderPanel header, StatusPanel statusBar, QaWorkspacePanel qaWorkspacePanel,
            TaskWorkspacePanel taskWorkspacePanel, CodebaseCachePanel codebaseCachePanel, JexlApiPanel jexlApiPanel,
            SystemToolsPanel systemToolsPanel, SettingsPanel settingsPanel, BuildConfigPanel buildConfigPanel,
            ContextManagerPanel contextManagerPanel) {
        this.settingsService = settingsService;
        this.projectService = projectService;
        this.chatMemory = chatMemory;
        this.header = header;
        this.qaWorkspacePanel = qaWorkspacePanel;
        this.taskWorkspacePanel = taskWorkspacePanel;

        setupLaf();
        setupWindow();
        initComponents(statusBar, codebaseCachePanel, jexlApiPanel, systemToolsPanel, settingsPanel, buildConfigPanel,
                contextManagerPanel);
        checkRequiredSystemToolsOnStartup(systemToolsPanel);
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        Edt.run(() -> {
            updateTitle();
            if (qaWorkspacePanel != null && qaWorkspacePanel.qaChatPanel != null) {
                qaWorkspacePanel.qaChatPanel.controller.resetForProjectChange();
            }
            if (taskWorkspacePanel != null && taskWorkspacePanel.taskChatPanel != null) {
                taskWorkspacePanel.taskChatPanel.controller.resetForProjectChange();
            }
            chatMemory.clear(ChatModel.QA_CONVERSATION_ID);
            chatMemory.clear(ChatModel.TASK_CONVERSATION_ID);
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

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                if (QA_VIEW.equals(currentCard) && qaWorkspacePanel != null) {
                    qaWorkspacePanel.focusChatInput();
                }
                if (pendingMissingToolsAlert) {
                    pendingMissingToolsAlert = false;
                    Edt.run(() -> showMissingToolsOptionPane(MISSING_REQUIRED_TOOLS_MESSAGE));
                }
            }
        });

        try {
            java.net.URL iconUrl = getClass().getResource("/static/images/roxy.png");
            if (iconUrl != null)
                setIconImage(new ImageIcon(iconUrl).getImage());
        } catch (Exception e) {
            log.trace("Failed to load icon: {}", e.getMessage());
        }
    }

    private void initComponents(StatusPanel statusBar, CodebaseCachePanel codebaseCachePanel, JexlApiPanel jexlApiPanel,
            SystemToolsPanel systemToolsPanel, SettingsPanel settingsPanel, BuildConfigPanel buildConfigPanel,
            ContextManagerPanel contextManagerPanel) {
        setLayout(new MigLayout("fill, insets 0", "[210!, fill]0[fill, grow]", "[fill, grow]"));

        sidebar = new SidebarPanel(this::showCard);
        qaWorkspacePanel.bindSidebar(sidebar);
        taskWorkspacePanel.bindSidebar(sidebar);

        JPanel workspace = new JPanel(new MigLayout("fill, insets 0", "[fill, grow]", "[]0[fill, grow]0[]"));

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

        add(sidebar, "grow");
        add(workspace, "grow");
        showCard(QA_VIEW);
    }

    private void checkRequiredSystemToolsOnStartup(SystemToolsPanel systemToolsPanel) {
        if (systemToolsPanel == null)
            return;
        CompletableFuture<List<SystemTool>> future = systemToolsPanel.getInitialToolsFuture();
        if (future == null)
            return;
        future.whenComplete((tools, ex) -> {
            if (ex == null && tools != null) {
                Edt.run(() -> handleStartupToolsCheck(tools));
            }
        });
    }

    void handleStartupToolsCheck(List<SystemTool> tools) {
        if (tools == null || tools.isEmpty() || SystemToolService.areAllRequiredToolsDetected(tools)) {
            return;
        }
        showCard(TOOLS_VIEW);
        lastMissingToolsMessage = MISSING_REQUIRED_TOOLS_MESSAGE;
        if (missingToolsDialogPresenter != null) {
            missingToolsDialogPresenter.accept(MISSING_REQUIRED_TOOLS_MESSAGE);
        } else if (!GraphicsEnvironment.isHeadless()) {
            if (isShowing()) {
                showMissingToolsOptionPane(MISSING_REQUIRED_TOOLS_MESSAGE);
            } else {
                pendingMissingToolsAlert = true;
            }
        }
    }

    private void showMissingToolsOptionPane(String message) {
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(this, message, MISSING_REQUIRED_TOOLS_TITLE, JOptionPane.WARNING_MESSAGE);
        }
    }

    void showCard(String cardName) {
        this.currentCard = cardName;
        if (sidebar != null) {
            sidebar.selectCard(cardName);
        }
        cardLayout.show(contentArea, cardName);
        if (QA_VIEW.equals(cardName) && qaWorkspacePanel != null) {
            qaWorkspacePanel.focusChatInput();
        } else if (TASK_VIEW.equals(cardName) && taskWorkspacePanel != null) {
            taskWorkspacePanel.onViewShown();
        }
    }

    void setMissingToolsDialogPresenter(Consumer<String> presenter) {
        this.missingToolsDialogPresenter = presenter;
    }

    String getLastMissingToolsMessage() {
        return lastMissingToolsMessage;
    }

    String getCurrentCard() {
        return currentCard;
    }

    SidebarPanel getSidebar() {
        return sidebar;
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
