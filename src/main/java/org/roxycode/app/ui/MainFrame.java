package org.roxycode.app.ui;

import java.awt.CardLayout;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Component
@Lazy
public final class MainFrame extends JPanel {
    private static final Logger log = LoggerFactory.getLogger(MainFrame.class);
    private static final long serialVersionUID = 1L;

    static final String QA_VIEW = "QA_VIEW";
    static final String TASK_VIEW = "TASK_VIEW";
    static final String CACHE_VIEW = "CACHE";
    static final String API_VIEW = "API";
    static final String TOOLS_VIEW = "TOOLS";
    static final String SETTINGS_VIEW = "SETTINGS";
    static final String BUILD_CONFIG_VIEW = "BUILD_CONFIG";
    static final String CONTEXT_MGR_VIEW = "CONTEXT_MGR";
    static final String MEMORY_VIEW = "MEMORY";

    private final transient SettingsService settingsService;
    private final transient ProjectService projectService;
    private final transient ChatMemory chatMemory;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentArea = new JPanel(cardLayout);

    private final JFrame hostFrame;
    private final HeaderPanel header;
    private final QaWorkspacePanel qaWorkspacePanel;
    private final TaskWorkspacePanel taskWorkspacePanel;
    private final CodebaseCachePanel codebaseCachePanel;
    private final AgentMemoryPanel agentMemoryPanel;
    SidebarPanel sidebar;
    String currentCard = QA_VIEW;
    private boolean initialToolsCheckComplete;
    private List<SystemTool> lastStartupTools = List.of();

    @Autowired
    public MainFrame(SettingsService settingsService, ProjectService projectService, ChatMemory chatMemory,
            HeaderPanel header, StatusPanel statusBar, QaWorkspacePanel qaWorkspacePanel,
            TaskWorkspacePanel taskWorkspacePanel, CodebaseCachePanel codebaseCachePanel, JexlApiPanel jexlApiPanel,
            SystemToolsPanel systemToolsPanel, SettingsPanel settingsPanel, BuildConfigPanel buildConfigPanel,
            ContextManagerPanel contextManagerPanel, @Nullable AgentMemoryPanel agentMemoryPanel) {
        this.settingsService = settingsService;
        this.projectService = projectService;
        this.chatMemory = chatMemory;
        this.header = header;
        this.qaWorkspacePanel = qaWorkspacePanel;
        this.taskWorkspacePanel = taskWorkspacePanel;
        this.codebaseCachePanel = codebaseCachePanel;
        this.agentMemoryPanel = agentMemoryPanel;
        if (GraphicsEnvironment.isHeadless()) {
            this.hostFrame = null;
        } else {
            this.hostFrame = new JFrame();
        }

        setupLaf();
        setupWindow();
        initComponents(statusBar, codebaseCachePanel, jexlApiPanel, systemToolsPanel, settingsPanel, buildConfigPanel,
                contextManagerPanel, agentMemoryPanel);
        if (settingsPanel != null) {
            settingsPanel.setOnInitialApiKeyConfiguredCallback(() -> {
                updateOnboardingAndNavigationState();
                boolean hasProject = projectService != null && projectService.hasActiveProject();
                if (hasProject) {
                    if (projectService.isBuildConfirmed()) {
                        showCard(QA_VIEW);
                    } else {
                        showCard(BUILD_CONFIG_VIEW);
                    }
                }
            });
        }
        if (buildConfigPanel != null) {
            buildConfigPanel.setOnConfirmSavedCallback(() -> {
                updateOnboardingAndNavigationState();
                showCard(QA_VIEW);
            });
        }
        handleStartupSettingsCheck();
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
            updateOnboardingAndNavigationState();
        });
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        Edt.run(this::updateOnboardingAndNavigationState);
    }

    void updateOnboardingAndNavigationState() {
        boolean hasApiKey = settingsService != null && settingsService.hasConfiguredApiKey();
        boolean hasProject = projectService != null && projectService.hasActiveProject();
        boolean buildConfirmed = hasProject && projectService.isBuildConfirmed();
        if (sidebar != null) {
            sidebar.updateNavigationAccess(hasApiKey, hasProject, buildConfirmed);
        }
        if (!hasApiKey) {
            showCard(SETTINGS_VIEW);
        } else if (hasProject && !buildConfirmed) {
            showCard(BUILD_CONFIG_VIEW);
        } else if (!hasProject && isProjectDependentCard(currentCard)) {
            showCard(SETTINGS_VIEW);
        }
    }

    static boolean isProjectDependentCard(String cardName) {
        return QA_VIEW.equals(cardName) || TASK_VIEW.equals(cardName) || CACHE_VIEW.equals(cardName)
                || BUILD_CONFIG_VIEW.equals(cardName);
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

    public void setTitle(String title) {
        if (hostFrame != null) {
            hostFrame.setTitle(title);
        }
    }

    private void initComponents(StatusPanel statusBar, CodebaseCachePanel codebaseCachePanel, JexlApiPanel jexlApiPanel,
            SystemToolsPanel systemToolsPanel, SettingsPanel settingsPanel, BuildConfigPanel buildConfigPanel,
            ContextManagerPanel contextManagerPanel, @Nullable AgentMemoryPanel memoryPanel) {
        setLayout(new MigLayout("fill, insets 0", "[210!, fill]0[fill, grow]", "[fill, grow]"));

        sidebar = new SidebarPanel(this::showCard);
        qaWorkspacePanel.bindSidebar(sidebar);
        qaWorkspacePanel.setOnMoveToFeatureListener(content -> {
            showCard(TASK_VIEW);
            if (taskWorkspacePanel != null && taskWorkspacePanel.taskChatPanel != null) {
                taskWorkspacePanel.taskChatPanel.setInputText(content);
            }
        });
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
        if (memoryPanel != null) {
            contentArea.add(memoryPanel, MEMORY_VIEW);
        }

        workspace.add(header, "h 36!, wrap");
        workspace.add(contentArea, "grow, wrap");
        workspace.add(statusBar, "h 30!");

        add(sidebar, "grow");
        add(workspace, "grow");
        showCard(QA_VIEW);
    }

    void handleStartupSettingsCheck() {
        if (settingsService == null) {
            return;
        }
        updateOnboardingAndNavigationState();
        if (initialToolsCheckComplete) {
            evaluateStartupSetupAlert();
        }
    }

    private void checkRequiredSystemToolsOnStartup(SystemToolsPanel systemToolsPanel) {
        if (systemToolsPanel == null) {
            initialToolsCheckComplete = true;
            evaluateStartupSetupAlert();
            return;
        }
        CompletableFuture<List<SystemTool>> future = systemToolsPanel.initialToolsFuture;
        if (future == null) {
            initialToolsCheckComplete = true;
            evaluateStartupSetupAlert();
            return;
        }
        future.whenComplete((tools, ex) -> {
            Edt.run(() -> {
                initialToolsCheckComplete = true;
                if (ex == null && tools != null) {
                    handleStartupToolsCheck(tools);
                } else {
                    evaluateStartupSetupAlert();
                }
            });
        });
    }

    void handleStartupToolsCheck(List<SystemTool> tools) {
        initialToolsCheckComplete = true;
        if (tools == null || tools.isEmpty()) {
            lastStartupTools = List.of();
            evaluateStartupSetupAlert();
            return;
        }
        lastStartupTools = List.copyOf(tools);
        boolean missingTools = !SystemToolService.areAllRequiredToolsDetected(tools);
        if (sidebar != null) {
            sidebar.setToolsAttentionNeeded(missingTools);
        }
        evaluateStartupSetupAlert();
    }

    private void evaluateStartupSetupAlert() {
        boolean hasApiKey = settingsService != null && settingsService.hasConfiguredApiKey();
        if (!hasApiKey) {
            return;
        }
        List<String> missingToolNames = List.of();
        if (lastStartupTools != null && !lastStartupTools.isEmpty()) {
            missingToolNames = SystemToolService.getMissingRequiredToolNames(lastStartupTools);
        }
        if (!missingToolNames.isEmpty()) {
            showCard(TOOLS_VIEW);
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
        } else if (CACHE_VIEW.equals(cardName) && codebaseCachePanel != null) {
            codebaseCachePanel.refreshStatus();
        } else if (MEMORY_VIEW.equals(cardName) && agentMemoryPanel != null) {
            agentMemoryPanel.refresh();
        }
    }

    private void updateTitle() {
        setTitle("RoxyCode - [" + projectService.getProjectName() + "]");
    }
}
