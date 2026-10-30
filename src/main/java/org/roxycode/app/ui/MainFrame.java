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
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.events.SystemToolsUpdatedEvent;
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
public final class MainFrame extends JPanel {
    private static final Logger log = LoggerFactory.getLogger(MainFrame.class);
    private static final long serialVersionUID = 1L;

    static final String QA_VIEW = "QA_VIEW";
    static final String TASK_VIEW = "TASK_VIEW";
    private static final String CACHE_VIEW = "CACHE";
    private static final String API_VIEW = "API";
    static final String TOOLS_VIEW = "TOOLS";
    static final String SETTINGS_VIEW = "SETTINGS";
    private static final String BUILD_CONFIG_VIEW = "BUILD_CONFIG";
    private static final String CONTEXT_MGR_VIEW = "CONTEXT_MGR";
    static final String MISSING_REQUIRED_TOOLS_TITLE = "Required System Tools Missing";
    static final String MISSING_REQUIRED_TOOLS_MESSAGE = "RoxyCode requires Ripgrep, Git, and Universal Ctags to function properly. Please review the System Tools page to install or configure any missing required tools.";
    static final String MISSING_API_KEY_TITLE = "Gemini API Key Required";
    static final String MISSING_API_KEY_MESSAGE = "Welcome to RoxyCode! Please enter and save your Gemini API Key in Global Settings to get started.";

    private final transient SettingsService settingsService;
    private final transient ProjectService projectService;
    private final transient ChatMemory chatMemory;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentArea = new JPanel(cardLayout);

    private final JFrame hostFrame;
    private final HeaderPanel header;
    private final QaWorkspacePanel qaWorkspacePanel;
    private final TaskWorkspacePanel taskWorkspacePanel;
    private SidebarPanel sidebar;
    private String title = "";
    private String currentCard = QA_VIEW;
    private String lastMissingToolsMessage;
    private boolean pendingMissingToolsAlert;
    private transient Consumer<String> missingToolsDialogPresenter;
    private String lastMissingApiKeyMessage;
    private boolean pendingMissingApiKeyAlert;
    private transient Consumer<String> missingApiKeyDialogPresenter;

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
        if (GraphicsEnvironment.isHeadless()) {
            this.hostFrame = null;
        } else {
            this.hostFrame = new JFrame();
        }

        setupLaf();
        setupWindow();
        initComponents(statusBar, codebaseCachePanel, jexlApiPanel, systemToolsPanel, settingsPanel, buildConfigPanel,
                contextManagerPanel);
        checkSettingsOnStartup();
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

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        Edt.run(() -> {
            if (sidebar != null && settingsService != null) {
                sidebar.setSettingsAttentionNeeded(!settingsService.hasConfiguredApiKey());
            }
        });
    }

    @EventListener
    public void onSystemToolsUpdated(SystemToolsUpdatedEvent event) {
        if (event == null || event.tools() == null) {
            return;
        }
        Edt.run(() -> {
            if (sidebar != null) {
                sidebar.setToolsAttentionNeeded(!SystemToolService.areAllRequiredToolsDetected(event.tools()));
            }
        });
    }

    private void setupLaf() {
        SettingsPanel.applyTheme(settingsService.getSettings().getTheme());
    }

    private void setupWindow() {
        updateTitle();
        setSize(1200, 900);
        if (hostFrame == null) {
            return;
        }
        hostFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        hostFrame.setSize(1200, 900);
        hostFrame.setLocationRelativeTo(null);
        hostFrame.setContentPane(this);

        hostFrame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                onWindowOpened();
            }
        });

        try {
            java.net.URL iconUrl = getClass().getResource("/static/images/roxy.png");
            if (iconUrl != null) {
                hostFrame.setIconImage(new ImageIcon(iconUrl).getImage());
            }
        } catch (Exception e) {
            log.trace("Failed to load icon: {}", e.getMessage());
        }
    }

    void onWindowOpened() {
        if (QA_VIEW.equals(currentCard) && qaWorkspacePanel != null) {
            qaWorkspacePanel.focusChatInput();
        }
        if (pendingMissingApiKeyAlert) {
            pendingMissingApiKeyAlert = false;
            Edt.run(() -> showMissingApiKeyOptionPane(MISSING_API_KEY_MESSAGE));
        }
        if (pendingMissingToolsAlert) {
            pendingMissingToolsAlert = false;
            Edt.run(() -> showMissingToolsOptionPane(MISSING_REQUIRED_TOOLS_MESSAGE));
        }
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (hostFrame != null) {
            hostFrame.setVisible(visible);
        }
    }

    public void dispose() {
        if (hostFrame != null) {
            hostFrame.dispose();
        }
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
        if (hostFrame != null) {
            hostFrame.setTitle(title);
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

    private void checkSettingsOnStartup() {
        handleStartupSettingsCheck();
    }

    void handleStartupSettingsCheck() {
        if (settingsService == null) {
            return;
        }
        boolean missingKey = !settingsService.hasConfiguredApiKey();
        if (sidebar != null) {
            sidebar.setSettingsAttentionNeeded(missingKey);
        }
        if (!missingKey) {
            return;
        }
        lastMissingApiKeyMessage = MISSING_API_KEY_MESSAGE;
        if (missingApiKeyDialogPresenter != null) {
            missingApiKeyDialogPresenter.accept(MISSING_API_KEY_MESSAGE);
        } else if (!GraphicsEnvironment.isHeadless()) {
            if (isShowing()) {
                showMissingApiKeyOptionPane(MISSING_API_KEY_MESSAGE);
            } else {
                pendingMissingApiKeyAlert = true;
            }
        }
    }

    private void showMissingApiKeyOptionPane(String message) {
        if (missingApiKeyDialogPresenter != null) {
            missingApiKeyDialogPresenter.accept(message);
        } else if (!GraphicsEnvironment.isHeadless()) {
            java.awt.Component parent = this;
            if (hostFrame != null) {
                parent = hostFrame;
            }
            JOptionPane.showMessageDialog(parent, message, MISSING_API_KEY_TITLE, JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void checkRequiredSystemToolsOnStartup(SystemToolsPanel systemToolsPanel) {
        if (systemToolsPanel == null) {
            return;
        }
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
        if (tools == null || tools.isEmpty()) {
            return;
        }
        boolean missingTools = !SystemToolService.areAllRequiredToolsDetected(tools);
        if (sidebar != null) {
            sidebar.setToolsAttentionNeeded(missingTools);
        }
        if (!missingTools) {
            return;
        }
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
        if (missingToolsDialogPresenter != null) {
            missingToolsDialogPresenter.accept(message);
        } else if (!GraphicsEnvironment.isHeadless()) {
            java.awt.Component parent = this;
            if (hostFrame != null) {
                parent = hostFrame;
            }
            JOptionPane.showMessageDialog(parent, message, MISSING_REQUIRED_TOOLS_TITLE, JOptionPane.WARNING_MESSAGE);
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

    void setMissingApiKeyDialogPresenter(Consumer<String> presenter) {
        this.missingApiKeyDialogPresenter = presenter;
    }

    String getLastMissingApiKeyMessage() {
        return lastMissingApiKeyMessage;
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
