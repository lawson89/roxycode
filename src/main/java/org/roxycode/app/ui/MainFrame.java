package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.Taskbar;
import java.awt.desktop.QuitResponse;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.swing.Action;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.text.JTextComponent;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.SystemUtils;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.NavigateToViewEvent;
import org.roxycode.app.events.PhaseChangedEvent;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.events.SystemToolsUpdatedEvent;
import org.roxycode.app.events.WorkflowTransitionEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.SystemTool;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.service.SystemToolService;
import org.roxycode.app.util.Edt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Component
@Lazy
@SuppressWarnings("PMD.CloseResource")
public final class MainFrame extends JPanel {
    private static final Logger log = LoggerFactory.getLogger(MainFrame.class);
    private static final long serialVersionUID = 1L;

    static final String QA_VIEW = "QA_VIEW";
    static final String TASK_VIEW = "TASK_VIEW";
    static final String CACHE_VIEW = "CACHE";
    static final String API_VIEW = "API";
    static final String TOOLS_VIEW = "TOOLS";
    static final String DATABASES_VIEW = "DATABASES";
    static final String SETTINGS_VIEW = "SETTINGS";
    static final String BUILD_CONFIG_VIEW = "BUILD_CONFIG";
    static final String PROJECT_MODEL_VIEW = "PROJECT_MODEL";
    static final String PROJECT_DATABASES_VIEW = "PROJECT_DATABASES";
    static final String CONTEXT_MGR_VIEW = "CONTEXT_MGR";
    static final String MEMORY_VIEW = "MEMORY";
    static final int MIN_WINDOW_WIDTH = 800;
    static final int MIN_WINDOW_HEIGHT = 600;
    private static final int TAB_INDEX_FIRST = 0;
    private static final int TAB_INDEX_SECOND = 1;
    private static final int TAB_INDEX_THIRD = 2;

    private final transient SettingsService settingsService;
    private final transient ProjectService projectService;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentArea = new JPanel(cardLayout);

    private final JFrame hostFrame;
    private final HeaderPanel header;
    final QaWorkspacePanel qaWorkspacePanel;
    final TaskWorkspacePanel taskWorkspacePanel;
    private final CodebaseCachePanel codebaseCachePanel;
    private final BuildConfigPanel buildConfigPanel;
    private final AgentMemoryPanel agentMemoryPanel;
    private final @Nullable GlobalDatabasesPanel globalDatabasesPanel;
    private final transient PlanService planService;
    private record RouteTarget(String layoutCard, @Nullable JTabbedPane tabbedPane, int tabIndex) {
    }

    private final transient UiComponents.UiGuard hubTabGuard = new UiComponents.UiGuard();
    private final Map<String, List<JMenuItem>> cardMenuItems = new LinkedHashMap<>();
    private final Map<String, RouteTarget> routeMap = new LinkedHashMap<>();
    enum ExportKind {
        NONE, QA_CHAT, QA_PAST_TOPICS, TASK_CHAT, TASK_PLAN, TASK_PAST_PLANS
    }

    JMenuBar menuBar;
    JMenu recentProjectsMenu;
    JMenuItem printExportHtmlItem;
    JMenuItem saveAsHtmlItem;
    JTabbedPane contextHubTabs;
    JTabbedPane settingsHubTabs;
    JLabel aiConnectionReadinessBadge;
    SidebarPanel sidebar;
    String currentCard = QA_VIEW;
    private boolean initialToolsCheckComplete;
    private List<SystemTool> lastStartupTools = List.of();
    private Timer blinkTimer;
    private boolean blinkHighlight;
    AppIconRenderer.AgentStatus currentStatus = AppIconRenderer.AgentStatus.IDLE;
    private String lastAppliedIconProjectName;
    private AppIconRenderer.AgentStatus lastAppliedIconStatus;
    private boolean lastAppliedIconHighlight;
    private boolean iconImagesApplied;
    Rectangle lastNormalBounds;
    int simulatedExtendedState = Frame.NORMAL;

    public MainFrame(SettingsService settingsService, ProjectService projectService, HeaderPanel header,
            StatusPanel statusBar, QaWorkspacePanel qaWorkspacePanel, TaskWorkspacePanel taskWorkspacePanel,
            CodebaseCachePanel codebaseCachePanel, JexlApiPanel jexlApiPanel, SystemToolsPanel systemToolsPanel,
            SettingsPanel settingsPanel, BuildConfigPanel buildConfigPanel, ContextManagerPanel contextManagerPanel,
            @Nullable AgentMemoryPanel agentMemoryPanel) {
        this(settingsService, projectService, header, statusBar, qaWorkspacePanel, taskWorkspacePanel,
                codebaseCachePanel, jexlApiPanel, systemToolsPanel, settingsPanel, buildConfigPanel,
                contextManagerPanel, agentMemoryPanel, null);
    }

    @Autowired
    public MainFrame(SettingsService settingsService, ProjectService projectService, HeaderPanel header,
            StatusPanel statusBar, QaWorkspacePanel qaWorkspacePanel, TaskWorkspacePanel taskWorkspacePanel,
            CodebaseCachePanel codebaseCachePanel, JexlApiPanel jexlApiPanel, SystemToolsPanel systemToolsPanel,
            SettingsPanel settingsPanel, BuildConfigPanel buildConfigPanel, ContextManagerPanel contextManagerPanel,
            @Nullable AgentMemoryPanel agentMemoryPanel, @Nullable GlobalDatabasesPanel globalDatabasesPanel) {
        this.settingsService = settingsService;
        this.projectService = projectService;
        this.header = header;
        this.qaWorkspacePanel = qaWorkspacePanel;
        this.taskWorkspacePanel = taskWorkspacePanel;
        this.codebaseCachePanel = codebaseCachePanel;
        this.buildConfigPanel = buildConfigPanel;
        this.agentMemoryPanel = agentMemoryPanel;
        this.globalDatabasesPanel = globalDatabasesPanel;
        if (taskWorkspacePanel != null) {
            this.planService = taskWorkspacePanel.planService;
        } else {
            this.planService = null;
        }
        if (GraphicsEnvironment.isHeadless()) {
            this.hostFrame = null;
        } else {
            this.hostFrame = new JFrame();
        }

        setupLaf();
        setupWindow();
        initComponents(statusBar, codebaseCachePanel, jexlApiPanel, systemToolsPanel, settingsPanel, buildConfigPanel,
                contextManagerPanel, agentMemoryPanel, globalDatabasesPanel);
        setupMenuBar();
        registerDesktopHandlers();
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
            buildConfigPanel.setOnManageGlobalDatabasesCallback(() -> showCard(DATABASES_VIEW));
            buildConfigPanel.setOnConfigureWithRoxyCallback(prompt -> {
                showCard(QA_VIEW);
                if (qaWorkspacePanel != null) {
                    qaWorkspacePanel.focusChatInput();
                }
                ChatPanel qaChat = qaChatPanel();
                if (qaChat != null) {
                    qaChat.submitPrompt(prompt);
                }
            });
        }
        if (qaWorkspacePanel != null) {
            qaWorkspacePanel.addWorkspaceStateListener(() -> Edt.run(() -> {
                if (sidebar != null && qaChatPanel() != null) {
                    sidebar.setQaBusy(qaChatPanel().isLoading());
                }
                syncSidebarWaitingState();
                updateWorkspaceState();
                updateExportMenuItemsState();
            }));
        }
        if (taskWorkspacePanel != null) {
            taskWorkspacePanel.addWorkspaceStateListener(() -> Edt.run(() -> {
                if (sidebar != null && taskChatPanel() != null) {
                    sidebar.setTaskBusy(taskChatPanel().isLoading());
                }
                syncSidebarWaitingState();
                updateWorkspaceState();
                updateExportMenuItemsState();
            }));
        }
        updateExportMenuItemsState();
        handleStartupSettingsCheck();
        checkRequiredSystemToolsOnStartup(systemToolsPanel);
    }

    @EventListener
    public void onNavigateToView(NavigateToViewEvent event) {
        if (event == null || event.viewId() == null) {
            return;
        }
        Edt.run(() -> {
            showCard(event.viewId());
            if (event.generatedToml() != null && buildConfigPanel != null) {
                buildConfigPanel.applyGeneratedBuildToml(event.generatedToml());
            }
            if (event.initialPrompt() != null) {
                if (TASK_VIEW.equals(event.viewId())) {
                    ChatPanel taskChat = taskChatPanel();
                    if (taskChat != null) {
                        boolean autoSubmitted = taskChat.submitMovedFeatureRequest(event.initialPrompt());
                        ChatPanel qaChat = qaChatPanel();
                        if (qaChat != null) {
                            qaChat.notifyFeatureRequestSent(autoSubmitted);
                        }
                    }
                } else if (QA_VIEW.equals(event.viewId())) {
                    if (qaWorkspacePanel != null) {
                        qaWorkspacePanel.focusChatInput();
                    }
                    ChatPanel qaChat = qaChatPanel();
                    if (qaChat != null) {
                        qaChat.submitPrompt(event.initialPrompt());
                    }
                }
            }
        });
    }

    @EventListener
    public void onWorkflowTransition(WorkflowTransitionEvent event) {
        Edt.run(() -> {
            if (sidebar != null && !event.autonomous()) {
                boolean waitingInput = taskChatPanel() != null && taskChatPanel().isWaitingUserInput();
                sidebar.setTaskWaitingApproval(event.to() != null || waitingInput);
            }
            updateWorkspaceState();
        });
    }

    @EventListener
    public void onPhaseChanged(PhaseChangedEvent event) {
        Edt.run(() -> {
            syncSidebarWaitingState();
            updateWorkspaceState();
        });
    }

    @EventListener
    public void onPlanUpdated(PlanUpdatedEvent event) {
        Edt.run(() -> {
            updateWorkspaceState();
            updateExportMenuItemsState();
        });
    }

    @Nullable
    ChatPanel qaChatPanel() {
        if (qaWorkspacePanel != null) {
            return qaWorkspacePanel.qaChatPanel;
        }
        return null;
    }

    @Nullable
    ChatPanel taskChatPanel() {
        if (taskWorkspacePanel != null) {
            return taskWorkspacePanel.taskChatPanel;
        }
        return null;
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        Edt.run(() -> {
            syncSidebarWaitingState();
            updateWorkspaceState();
            updateOnboardingAndNavigationState();
        });
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        Edt.run(() -> {
            updateOnboardingAndNavigationState();
            updateSettingsHubReadiness(lastStartupTools);
        });
    }

    void updateOnboardingAndNavigationState() {
        boolean hasCredentials = settingsService != null && settingsService.hasConfiguredCredentials();
        boolean hasProject = projectService != null && projectService.hasActiveProject();
        boolean buildConfirmed = hasProject && projectService.isBuildConfirmed();
        if (sidebar != null) {
            sidebar.updateNavigationAccess(hasCredentials, hasProject, buildConfirmed);
        }
        updateSettingsHubReadiness(lastStartupTools);
        refreshRecentProjectsMenu();
        syncMenuAccessWithSidebar();
        if (!hasCredentials) {
            showCard(SETTINGS_VIEW);
        } else if (hasProject && !buildConfirmed && TASK_VIEW.equals(currentCard)) {
            showCard(BUILD_CONFIG_VIEW);
        } else if (!hasProject && isProjectDependentCard(currentCard)) {
            showCard(SETTINGS_VIEW);
        }
    }

    static boolean isProjectDependentCard(String cardName) {
        return QA_VIEW.equals(cardName) || TASK_VIEW.equals(cardName) || CACHE_VIEW.equals(cardName)
                || BUILD_CONFIG_VIEW.equals(cardName) || PROJECT_MODEL_VIEW.equals(cardName)
                || PROJECT_DATABASES_VIEW.equals(cardName);
    }

    @EventListener
    public void onSystemToolsUpdated(SystemToolsUpdatedEvent event) {
        if (event == null || event.tools() == null) {
            return;
        }
        Edt.run(() -> {
            lastStartupTools = List.copyOf(event.tools());
            if (sidebar != null) {
                sidebar.setToolsAttentionNeeded(!SystemToolService.areAllRequiredToolsDetected(event.tools()));
            }
            updateSettingsHubReadiness(lastStartupTools);
        });
    }

    private void setupLaf() {
        SettingsPanel.applyTheme(settingsService.getSettings().getTheme());
    }

    private void setupWindow() {
        updateTitle();
        AppSettings settings;
        if (settingsService != null) {
            settings = settingsService.getSettings();
        } else {
            settings = new AppSettings();
        }
        Dimension initialSize = clampWindowSize(settings.getWindowWidth(), settings.getWindowHeight(),
                resolveMaximumWindowBounds());
        setSize(initialSize.width, initialSize.height);
        if (hostFrame == null) {
            int initX = 0;
            if (settings.getWindowX() != null) {
                initX = settings.getWindowX();
            }
            int initY = 0;
            if (settings.getWindowY() != null) {
                initY = settings.getWindowY();
            }
            lastNormalBounds = new Rectangle(initX, initY, initialSize.width, initialSize.height);
            if (settings.isWindowMaximized()) {
                simulatedExtendedState = Frame.MAXIMIZED_BOTH;
            }
            return;
        }
        hostFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        hostFrame.setMinimumSize(new Dimension(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT));

        Integer savedX = settings.getWindowX();
        Integer savedY = settings.getWindowY();
        if (savedX != null && savedY != null) {
            Rectangle candidate = new Rectangle(savedX, savedY, initialSize.width, initialSize.height);
            if (isWindowBoundsVisibleOnScreens(candidate, resolveScreenBoundsList())) {
                hostFrame.setBounds(candidate);
            } else {
                hostFrame.setSize(initialSize.width, initialSize.height);
                hostFrame.setLocationRelativeTo(null);
            }
        } else {
            hostFrame.setSize(initialSize.width, initialSize.height);
            hostFrame.setLocationRelativeTo(null);
        }
        lastNormalBounds = new Rectangle(hostFrame.getBounds());
        if (settings.isWindowMaximized()) {
            hostFrame.setExtendedState(Frame.MAXIMIZED_BOTH);
        }
        hostFrame.setContentPane(this);

        hostFrame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                recordNormalWindowBounds();
            }

            @Override
            public void componentResized(ComponentEvent e) {
                recordNormalWindowBounds();
            }
        });

        hostFrame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                onWindowOpened();
            }

            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                saveWindowStateOnClose();
            }
        });

        hostFrame.addWindowFocusListener(new WindowFocusListener() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                onWindowGainedFocus();
            }

            @Override
            public void windowLostFocus(WindowEvent e) {
                if (currentStatus == AppIconRenderer.AgentStatus.WAITING_FEEDBACK) {
                    startBlinkTimer();
                }
            }
        });

        applyIconImages(currentStatus, false);
    }

    static Dimension clampWindowSize(int requestedWidth, int requestedHeight, @Nullable Rectangle maxScreenBounds) {
        int width = AppSettings.DEFAULT_WINDOW_WIDTH;
        if (requestedWidth > 0) {
            width = requestedWidth;
        }
        int height = AppSettings.DEFAULT_WINDOW_HEIGHT;
        if (requestedHeight > 0) {
            height = requestedHeight;
        }
        if (maxScreenBounds != null && maxScreenBounds.width > 0 && maxScreenBounds.height > 0) {
            width = Math.min(width, maxScreenBounds.width);
            height = Math.min(height, maxScreenBounds.height);
        }
        width = Math.max(MIN_WINDOW_WIDTH, width);
        height = Math.max(MIN_WINDOW_HEIGHT, height);
        return new Dimension(width, height);
    }

    static boolean isWindowBoundsVisibleOnScreens(Rectangle windowBounds, List<Rectangle> screenBoundsList) {
        if (windowBounds == null || screenBoundsList == null || screenBoundsList.isEmpty()) {
            return false;
        }
        for (Rectangle screenBounds : screenBoundsList) {
            if (screenBounds != null && screenBounds.intersects(windowBounds)) {
                Rectangle intersection = screenBounds.intersection(windowBounds);
                if (intersection.width >= 100 && intersection.height >= 100) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Rectangle resolveMaximumWindowBounds() {
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Rectangle> resolveScreenBoundsList() {
        if (GraphicsEnvironment.isHeadless()) {
            return List.of();
        }
        try {
            GraphicsDevice[] devices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
            if (devices == null || devices.length == 0) {
                return List.of();
            }
            List<Rectangle> boundsList = new ArrayList<>(devices.length);
            for (GraphicsDevice device : devices) {
                if (device != null) {
                    GraphicsConfiguration config = device.getDefaultConfiguration();
                    if (config != null && config.getBounds() != null) {
                        boundsList.add(config.getBounds());
                    }
                }
            }
            return boundsList;
        } catch (Exception e) {
            return List.of();
        }
    }

    int getWindowExtendedState() {
        if (simulatedExtendedState != Frame.NORMAL) {
            return simulatedExtendedState;
        }
        if (hostFrame != null) {
            return hostFrame.getExtendedState();
        }
        return Frame.NORMAL;
    }

    Rectangle getCurrentWindowBounds() {
        if (hostFrame != null) {
            return hostFrame.getBounds();
        }
        return getBounds();
    }

    void setWindowBounds(int x, int y, int width, int height) {
        setBounds(x, y, width, height);
        if (hostFrame != null) {
            hostFrame.setBounds(x, y, width, height);
        }
        recordNormalWindowBounds();
    }

    void recordNormalWindowBounds() {
        if ((getWindowExtendedState() & Frame.MAXIMIZED_BOTH) == 0) {
            Rectangle bounds = getCurrentWindowBounds();
            if (bounds != null && bounds.width > 0 && bounds.height > 0) {
                lastNormalBounds = new Rectangle(bounds);
            }
        }
    }

    void saveWindowStateOnClose() {
        if (settingsService == null) {
            return;
        }
        recordNormalWindowBounds();
        boolean maximized = (getWindowExtendedState() & Frame.MAXIMIZED_BOTH) != 0;
        Rectangle boundsToSave;
        if (lastNormalBounds != null) {
            boundsToSave = lastNormalBounds;
        } else {
            boundsToSave = getCurrentWindowBounds();
        }
        AppSettings settings = settingsService.getSettings();
        if (boundsToSave != null && boundsToSave.width > 0 && boundsToSave.height > 0) {
            settings.setWindowX(boundsToSave.x);
            settings.setWindowY(boundsToSave.y);
            settings.setWindowWidth(boundsToSave.width);
            settings.setWindowHeight(boundsToSave.height);
        }
        settings.setWindowMaximized(maximized);
        settingsService.saveSettingsSync();
    }

    void onWindowGainedFocus() {
        stopBlinkTimer();
        applyIconImages(currentStatus, false);
        if (header != null) {
            header.refreshGitBranch();
        }
    }

    void onWindowOpened() {
        if (QA_VIEW.equals(currentCard) && qaWorkspacePanel != null) {
            qaWorkspacePanel.focusChatInput();
        }
    }

    @Override
    public void setVisible(boolean visible) {
        if (visible) {
            validate();
            if (hostFrame != null) {
                hostFrame.validate();
            }
        }
        super.setVisible(visible);
        if (hostFrame != null) {
            hostFrame.setVisible(visible);
        }
    }

    public void dispose() {
        stopBlinkTimer();
        clearTaskbarAttention();
        if (hostFrame != null) {
            hostFrame.dispose();
        }
        if (settingsService != null) {
            settingsService.awaitPendingSave();
        }
    }

    private String currentTitle;

    public void setTitle(String title) {
        this.currentTitle = title;
        if (hostFrame != null) {
            hostFrame.setTitle(title);
        }
    }

    public String getTitle() {
        if (hostFrame != null) {
            return hostFrame.getTitle();
        }
        return currentTitle;
    }

    private void initComponents(StatusPanel statusBar, CodebaseCachePanel codebaseCachePanel, JexlApiPanel jexlApiPanel,
            SystemToolsPanel systemToolsPanel, SettingsPanel settingsPanel, BuildConfigPanel buildConfigPanel,
            ContextManagerPanel contextManagerPanel, @Nullable AgentMemoryPanel memoryPanel,
            @Nullable GlobalDatabasesPanel databasesPanel) {
        setLayout(new MigLayout("fill, insets 0", "[210!, fill]0[fill, grow]", "[fill, grow]"));

        sidebar = new SidebarPanel(this::showCard);
        header.setOnNavigateToCard(this::showCard);
        qaWorkspacePanel.setOnMoveToFeatureListener(content -> {
            showCard(TASK_VIEW);
            ChatPanel taskChat = taskChatPanel();
            if (taskChat != null) {
                boolean autoSubmitted = taskChat.submitMovedFeatureRequest(content);
                ChatPanel qaChat = qaChatPanel();
                if (qaChat != null) {
                    qaChat.notifyFeatureRequestSent(autoSubmitted);
                }
            }
        });
        qaWorkspacePanel.setOnApplyBuildTomlListener(toml -> {
            ChatPanel qaChat = qaChatPanel();
            if (qaChat != null) {
                qaChat.notifyBuildTomlApplied();
            }
            showCard(BUILD_CONFIG_VIEW);
            if (buildConfigPanel != null) {
                buildConfigPanel.applyGeneratedBuildToml(toml);
            }
        });
        // Workspace state listeners (busy, waiting, tab/export updates) are wired in constructor via
        // addWorkspaceStateListener

        JPanel workspace = new JPanel(new MigLayout("fill, insets 0", "[fill, grow]", "[]0[fill, grow]0[]"));

        contextHubTabs = UiComponents.createUnderlinedTabbedPane(null);
        if (contextManagerPanel != null) {
            contextHubTabs.addTab("Context Folders", FontIcon.of(Codicons.FILE, 15, SwingHtmlUtils.getAccentColor()),
                    contextManagerPanel);
        }
        if (codebaseCachePanel != null) {
            contextHubTabs.addTab("Repo Map & Prompt",
                    FontIcon.of(Codicons.DATABASE, 15, SwingHtmlUtils.getMutedForegroundColor()), codebaseCachePanel);
        }
        if (jexlApiPanel != null) {
            contextHubTabs.addTab("Agent Tools API",
                    FontIcon.of(Codicons.CODE, 15, SwingHtmlUtils.getMutedForegroundColor()), jexlApiPanel);
        }
        registerRoute(CONTEXT_MGR_VIEW, CONTEXT_MGR_VIEW, contextHubTabs, TAB_INDEX_FIRST);
        registerRoute(CACHE_VIEW, CONTEXT_MGR_VIEW, contextHubTabs, TAB_INDEX_SECOND);
        registerRoute(API_VIEW, CONTEXT_MGR_VIEW, contextHubTabs, TAB_INDEX_THIRD);
        bindHubTabRoutes(contextHubTabs);

        aiConnectionReadinessBadge = new JLabel();
        aiConnectionReadinessBadge.setIconTextGap(6);
        JPanel settingsHubTrailing = new JPanel(new MigLayout("insets 4 8 4 12", "[]", "[center]"));
        settingsHubTrailing.setOpaque(false);
        settingsHubTrailing.add(aiConnectionReadinessBadge);

        settingsHubTabs = UiComponents.createUnderlinedTabbedPane(settingsHubTrailing);
        if (settingsPanel != null) {
            settingsHubTabs.addTab("General & AI",
                    FontIcon.of(Codicons.SETTINGS_GEAR, 15, SwingHtmlUtils.getAccentColor()), settingsPanel);
        }
        if (systemToolsPanel != null) {
            settingsHubTabs.addTab("System Tools",
                    FontIcon.of(Codicons.TOOLS, 15, SwingHtmlUtils.getMutedForegroundColor()), systemToolsPanel);
        }
        if (databasesPanel != null) {
            settingsHubTabs.addTab("Databases",
                    FontIcon.of(Codicons.DATABASE, 15, SwingHtmlUtils.getMutedForegroundColor()), databasesPanel);
        }
        updateSettingsHubReadiness(lastStartupTools);
        registerRoute(SETTINGS_VIEW, SETTINGS_VIEW, settingsHubTabs, TAB_INDEX_FIRST);
        registerRoute(TOOLS_VIEW, SETTINGS_VIEW, settingsHubTabs, TAB_INDEX_SECOND);
        registerRoute(DATABASES_VIEW, SETTINGS_VIEW, settingsHubTabs, TAB_INDEX_THIRD);
        bindHubTabRoutes(settingsHubTabs);

        if (buildConfigPanel != null && buildConfigPanel.settingsTabs != null) {
            registerRoute(BUILD_CONFIG_VIEW, BUILD_CONFIG_VIEW, buildConfigPanel.settingsTabs, TAB_INDEX_FIRST);
            registerRoute(PROJECT_MODEL_VIEW, BUILD_CONFIG_VIEW, buildConfigPanel.settingsTabs,
                    BuildConfigPanel.TAB_INDEX_AI_MODEL);
            registerRoute(PROJECT_DATABASES_VIEW, BUILD_CONFIG_VIEW, buildConfigPanel.settingsTabs,
                    BuildConfigPanel.TAB_INDEX_DATABASES);
            bindHubTabRoutes(buildConfigPanel.settingsTabs);
        }

        contentArea.add(qaWorkspacePanel, QA_VIEW);
        contentArea.add(taskWorkspacePanel, TASK_VIEW);
        contentArea.add(contextHubTabs, CONTEXT_MGR_VIEW);
        contentArea.add(settingsHubTabs, SETTINGS_VIEW);
        contentArea.add(buildConfigPanel, BUILD_CONFIG_VIEW);
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

    private void setupMenuBar() {
        int shortcutMask = UiComponents.resolveMenuShortcutMask();
        menuBar = new JMenuBar();
        menuBar.putClientProperty(FlatClientProperties.MENU_BAR_EMBEDDED, Boolean.TRUE);
        applyMenuBarStyling();

        JMenu fileMenu = new JMenu("File");
        JMenuItem openProjectItem = new JMenuItem("Open Project...");
        openProjectItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, shortcutMask));
        openProjectItem.addActionListener(e -> {
            if (header != null) {
                header.chooseProject();
            }
        });
        fileMenu.add(openProjectItem);

        recentProjectsMenu = new JMenu("Recent Projects");
        refreshRecentProjectsMenu();
        fileMenu.add(recentProjectsMenu);
        fileMenu.addSeparator();

        printExportHtmlItem = new JMenuItem("Print / Export HTML...");
        printExportHtmlItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_P, shortcutMask));
        printExportHtmlItem.addActionListener(e -> exportCurrentView(false));
        fileMenu.add(printExportHtmlItem);

        saveAsHtmlItem = new JMenuItem("Save as HTML...");
        saveAsHtmlItem.addActionListener(e -> exportCurrentView(true));
        fileMenu.add(saveAsHtmlItem);
        fileMenu.addSeparator();

        fileMenu.addMenuListener(new javax.swing.event.MenuListener() {
            @Override
            public void menuSelected(javax.swing.event.MenuEvent e) {
                updateExportMenuItemsState();
            }

            @Override
            public void menuDeselected(javax.swing.event.MenuEvent e) {
            }

            @Override
            public void menuCanceled(javax.swing.event.MenuEvent e) {
            }
        });

        JMenuItem settingsItem = new JMenuItem("Settings...");
        settingsItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, shortcutMask));
        settingsItem.addActionListener(e -> navigateIfAccessible(SETTINGS_VIEW));
        registerCardMenuItem(SETTINGS_VIEW, settingsItem);
        fileMenu.add(settingsItem);

        if (!SystemUtils.IS_OS_MAC) {
            fileMenu.addSeparator();
            JMenuItem exitItem = new JMenuItem("Exit");
            exitItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, shortcutMask));
            exitItem.addActionListener(e -> exitApplication());
            fileMenu.add(exitItem);
        }

        JMenu editMenu = new JMenu("Edit");
        editMenu.add(createEditMenuItem("Undo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcutMask),
                this::performFocusedUndo));
        editMenu.add(createEditMenuItem("Redo",
                KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcutMask | InputEvent.SHIFT_DOWN_MASK),
                this::performFocusedRedo));
        editMenu.addSeparator();
        editMenu.add(
                createEditMenuItem("Cut", KeyStroke.getKeyStroke(KeyEvent.VK_X, shortcutMask), JTextComponent::cut));
        editMenu.add(
                createEditMenuItem("Copy", KeyStroke.getKeyStroke(KeyEvent.VK_C, shortcutMask), JTextComponent::copy));
        editMenu.add(createEditMenuItem("Paste", KeyStroke.getKeyStroke(KeyEvent.VK_V, shortcutMask),
                JTextComponent::paste));
        editMenu.addSeparator();
        editMenu.add(createEditMenuItem("Select All", KeyStroke.getKeyStroke(KeyEvent.VK_A, shortcutMask),
                JTextComponent::selectAll));

        JMenu viewMenu = new JMenu("View");
        addViewCardMenuItem(viewMenu, SidebarPanel.ASK_ROXY_LABEL, QA_VIEW,
                KeyStroke.getKeyStroke(KeyEvent.VK_1, shortcutMask));
        addViewCardMenuItem(viewMenu, SidebarPanel.BUILD_FEATURE_LABEL, TASK_VIEW,
                KeyStroke.getKeyStroke(KeyEvent.VK_2, shortcutMask));
        addViewCardMenuItem(viewMenu, SidebarPanel.CONTEXT_PROMPTS_LABEL, CONTEXT_MGR_VIEW,
                KeyStroke.getKeyStroke(KeyEvent.VK_3, shortcutMask));
        addViewCardMenuItem(viewMenu, SidebarPanel.AGENT_MEMORY_LABEL, MEMORY_VIEW,
                KeyStroke.getKeyStroke(KeyEvent.VK_4, shortcutMask));
        addViewCardMenuItem(viewMenu, SidebarPanel.PROJECT_SETTINGS_LABEL, BUILD_CONFIG_VIEW,
                KeyStroke.getKeyStroke(KeyEvent.VK_5, shortcutMask));
        addViewCardMenuItem(viewMenu, SidebarPanel.SETTINGS_TOOLS_LABEL, SETTINGS_VIEW,
                KeyStroke.getKeyStroke(KeyEvent.VK_6, shortcutMask));
        viewMenu.addSeparator();
        JMenuItem toggleThemeItem = new JMenuItem("Toggle Theme");
        toggleThemeItem
                .setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_T, shortcutMask | InputEvent.SHIFT_DOWN_MASK));
        toggleThemeItem.addActionListener(e -> {
            if (header != null) {
                header.toggleTheme();
            }
        });
        viewMenu.add(toggleThemeItem);

        JMenu helpMenu = new JMenu("Help");
        JMenuItem jexlApiItem = new JMenuItem("JEXL API Reference");
        jexlApiItem.addActionListener(e -> navigateIfAccessible(API_VIEW));
        registerCardMenuItem(API_VIEW, jexlApiItem);
        helpMenu.add(jexlApiItem);

        JMenuItem systemToolsItem = new JMenuItem("System Tools");
        systemToolsItem.addActionListener(e -> navigateIfAccessible(TOOLS_VIEW));
        registerCardMenuItem(TOOLS_VIEW, systemToolsItem);
        helpMenu.add(systemToolsItem);

        helpMenu.addSeparator();
        JMenuItem aboutItem = new JMenuItem("About RoxyCode");
        aboutItem.addActionListener(e -> showAboutDialog());
        helpMenu.add(aboutItem);

        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        menuBar.add(viewMenu);
        menuBar.add(helpMenu);

        if (hostFrame != null) {
            hostFrame.setJMenuBar(menuBar);
        }
        syncMenuAccessWithSidebar();
    }

    private void addViewCardMenuItem(JMenu viewMenu, String label, String cardName, KeyStroke accelerator) {
        JMenuItem item = new JMenuItem(label);
        item.setAccelerator(accelerator);
        item.addActionListener(e -> navigateIfAccessible(cardName));
        registerCardMenuItem(cardName, item);
        viewMenu.add(item);
    }

    void refreshRecentProjectsMenu() {
        if (recentProjectsMenu == null) {
            return;
        }
        recentProjectsMenu.removeAll();
        List<Path> recentProjects = List.of();
        if (projectService != null) {
            recentProjects = projectService.getRecentProjects();
        }
        if (recentProjects.isEmpty()) {
            JMenuItem emptyItem = new JMenuItem("No Recent Projects");
            emptyItem.setEnabled(false);
            recentProjectsMenu.add(emptyItem);
            return;
        }
        Path activeNormalized = null;
        if (projectService != null && projectService.getCurrentProjectRoot() != null) {
            activeNormalized = projectService.getCurrentProjectRoot().toAbsolutePath().normalize();
        }
        for (Path projectPath : recentProjects) {
            Path normalizedPath = projectPath.toAbsolutePath().normalize();
            JMenuItem item = new JMenuItem(formatRecentProjectLabel(normalizedPath));
            item.setToolTipText(normalizedPath.toString());
            if (activeNormalized != null && activeNormalized.equals(normalizedPath)) {
                item.setEnabled(false);
            } else {
                item.addActionListener(e -> openRecentProject(normalizedPath));
            }
            recentProjectsMenu.add(item);
        }
    }

    static String formatRecentProjectLabel(Path projectPath) {
        if (projectPath == null) {
            return "";
        }
        Path fileName = projectPath.getFileName();
        if (fileName == null) {
            return projectPath.toString();
        }
        return fileName + " — " + projectPath;
    }

    void openRecentProject(Path projectPath) {
        if (projectPath == null) {
            return;
        }
        if (header != null) {
            header.switchProjectAsync(projectPath);
        } else if (projectService != null) {
            Edt.runAsync(() -> projectService.setCurrentProjectRoot(projectPath), null);
        }
    }

    private void registerCardMenuItem(String cardName, JMenuItem item) {
        List<JMenuItem> items = cardMenuItems.computeIfAbsent(cardName, k -> new ArrayList<>());
        items.add(item);
    }

    private void syncMenuAccessWithSidebar() {
        if (sidebar == null) {
            return;
        }
        for (Map.Entry<String, List<JMenuItem>> entry : cardMenuItems.entrySet()) {
            NavButton btn = sidebar.getCardButton(entry.getKey());
            boolean enabled = btn == null || btn.isEnabled();
            for (JMenuItem item : entry.getValue()) {
                item.setEnabled(enabled);
            }
        }
    }

    void navigateIfAccessible(String cardName) {
        if (sidebar != null) {
            NavButton btn = sidebar.getCardButton(cardName);
            if (btn != null && !btn.isEnabled()) {
                return;
            }
        }
        showCard(cardName);
    }

    private static JMenuItem createEditMenuItem(String label, KeyStroke accelerator,
            java.util.function.Consumer<JTextComponent> action) {
        JMenuItem item = new JMenuItem(label);
        item.setAccelerator(accelerator);
        item.addActionListener(e -> {
            JTextComponent target = resolveFocusedTextComponent();
            if (target != null) {
                action.accept(target);
            }
        });
        return item;
    }

    static JTextComponent resolveFocusedTextComponent() {
        java.awt.Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (focusOwner instanceof JTextComponent textComponent) {
            return textComponent;
        }
        java.awt.Component permanentOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .getPermanentFocusOwner();
        if (permanentOwner instanceof JTextComponent textComponent) {
            return textComponent;
        }
        return null;
    }

    void performFocusedUndo(JTextComponent target) {
        invokeTextComponentAction(target, "Undo", "undo");
    }

    void performFocusedRedo(JTextComponent target) {
        invokeTextComponentAction(target, "Redo", "redo");
    }

    private static void invokeTextComponentAction(JTextComponent target, String primaryKey, String fallbackKey) {
        if (target == null || !target.isEditable()) {
            return;
        }
        Action action = target.getActionMap().get(primaryKey);
        if (action == null) {
            action = target.getActionMap().get(fallbackKey);
        }
        if (action != null && action.isEnabled()) {
            action.actionPerformed(new ActionEvent(target, ActionEvent.ACTION_PERFORMED, primaryKey));
        }
    }

    void exitApplication() {
        saveWindowStateOnClose();
        dispose();
    }

    void handleDesktopQuitRequest(@Nullable QuitResponse response) {
        saveWindowStateOnClose();
        if (response != null) {
            response.performQuit();
        }
    }

    private void registerDesktopHandlers() {
        if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()) {
            return;
        }
        try {
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.APP_PREFERENCES)) {
                desktop.setPreferencesHandler(e -> Edt.run(() -> navigateIfAccessible(SETTINGS_VIEW)));
            }
            if (desktop.isSupported(Desktop.Action.APP_ABOUT)) {
                desktop.setAboutHandler(e -> Edt.run(this::showAboutDialog));
            }
            if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                desktop.setQuitHandler((e, response) -> handleDesktopQuitRequest(response));
            }
        } catch (UnsupportedOperationException | SecurityException e) {
            log.debug("Desktop handlers not supported: {}", e.getMessage());
        } catch (Exception e) {
            log.debug("Failed to register desktop handlers: {}", e.getMessage());
        }
    }

    static String buildAboutMessage() {
        String javaVersion = System.getProperty("java.version", "Unknown");
        String osName = System.getProperty("os.name", "Unknown OS");
        String osArch = System.getProperty("os.arch", "");
        return String.join(System.lineSeparator(), "RoxyCode — High-Autonomy AI Development Workspace", "",
                "Plan • Code • Review Lifecycle", "Runtime: Java " + javaVersion + " (" + osName + " " + osArch + ")");
    }

    void showAboutDialog() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        Image iconImage = AppIconRenderer.renderAppIcon(64, getActiveProjectName(), AppIconRenderer.AgentStatus.IDLE,
                false);
        JOptionPane.showMessageDialog(hostFrame, buildAboutMessage(), "About RoxyCode", JOptionPane.INFORMATION_MESSAGE,
                new javax.swing.ImageIcon(iconImage));
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
        updateSettingsHubReadiness(lastStartupTools);
        evaluateStartupSetupAlert();
    }

    private void evaluateStartupSetupAlert() {
        boolean hasCredentials = settingsService != null && settingsService.hasConfiguredCredentials();
        if (!hasCredentials) {
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

    @Override
    public void updateUI() {
        super.updateUI();
        applyMenuBarStyling();
        updateSettingsHubReadiness(lastStartupTools);
    }

    static String formatSystemToolsTabTitle(@Nullable List<SystemTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return "System Tools";
        }
        int missingRequired = SystemToolService.getMissingRequiredToolNames(tools).size();
        if (missingRequired > 0) {
            return "System Tools (" + missingRequired + " Missing)";
        }
        int detectedCount = 0;
        for (SystemTool tool : tools) {
            if (tool != null && SystemToolService.STATUS_DETECTED.equalsIgnoreCase(tool.status())) {
                detectedCount++;
            }
        }
        return "System Tools (" + detectedCount + "/" + tools.size() + " Ready)";
    }

    void updateSettingsHubReadiness(@Nullable List<SystemTool> tools) {
        if (settingsHubTabs != null && settingsHubTabs.getTabCount() > TAB_INDEX_SECOND) {
            settingsHubTabs.setTitleAt(TAB_INDEX_SECOND, formatSystemToolsTabTitle(tools));
        }
        if (aiConnectionReadinessBadge == null) {
            return;
        }
        boolean hasCredentials = settingsService != null && settingsService.hasConfiguredCredentials();
        Color badgeColor;
        Codicons badgeIcon;
        String badgeText;
        if (hasCredentials) {
            badgeColor = SwingHtmlUtils.getUiColor(new Color(0x28, 0xa7, 0x45), "Actions.Green");
            badgeIcon = Codicons.CHECK;
            if (settingsService.getSettings() != null && settingsService.getSettings().isVertexAiEnabled()) {
                badgeText = "Vertex AI Connected";
            } else {
                badgeText = "AI Studio Connected";
            }
        } else {
            badgeColor = SwingHtmlUtils.getWarningColor();
            badgeIcon = Codicons.WARNING;
            badgeText = "Credentials Required";
        }
        String hex = SwingHtmlUtils.toHex(badgeColor);
        aiConnectionReadinessBadge.setText(badgeText);
        aiConnectionReadinessBadge.setForeground(badgeColor);
        aiConnectionReadinessBadge.setIcon(FontIcon.of(badgeIcon, 13, badgeColor));
        aiConnectionReadinessBadge.putClientProperty(FlatClientProperties.STYLE,
                "font: bold -1; arc: 999; border: 3,10,3,10," + hex + ",1,999; background: fade(" + hex + ", 14%)");
    }

    void applyMenuBarStyling() {
        if (menuBar == null) {
            return;
        }
        menuBar.putClientProperty(FlatClientProperties.STYLE, "background: darken($Panel.background, 2%)");
        menuBar.setBorder(SwingHtmlUtils.createThemeMatteBorder(0, 0, 1, 0));
    }

    private void registerRoute(String viewId, String layoutCard, JTabbedPane tabbedPane, int tabIndex) {
        routeMap.put(viewId, new RouteTarget(layoutCard, tabbedPane, tabIndex));
    }

    private void bindHubTabRoutes(JTabbedPane tabbedPane) {
        tabbedPane.addChangeListener(e -> {
            if (hubTabGuard.isActive()) {
                return;
            }
            int selectedIndex = tabbedPane.getSelectedIndex();
            for (Map.Entry<String, RouteTarget> entry : routeMap.entrySet()) {
                RouteTarget target = entry.getValue();
                if (tabbedPane.equals(target.tabbedPane()) && target.tabIndex() == selectedIndex) {
                    currentCard = entry.getKey();
                    onViewActivated(currentCard);
                    break;
                }
            }
        });
    }

    void showCard(String cardName) {
        this.currentCard = cardName;
        if (sidebar != null) {
            sidebar.selectCard(cardName);
        }
        RouteTarget route = routeMap.get(cardName);
        String layoutCard;
        if (route != null) {
            layoutCard = route.layoutCard();
            if (route.tabbedPane() != null) {
                hubTabGuard.run(() -> UiComponents.selectTabSafely(route.tabbedPane(), route.tabIndex()));
            }
        } else {
            layoutCard = cardName;
        }
        cardLayout.show(contentArea, layoutCard);
        updateTitle();
        onViewActivated(cardName);
        updateExportMenuItemsState();
    }

    private void onViewActivated(String cardName) {
        if (QA_VIEW.equals(cardName) && qaWorkspacePanel != null) {
            qaWorkspacePanel.focusChatInput();
        } else if (TASK_VIEW.equals(cardName) && taskWorkspacePanel != null) {
            taskWorkspacePanel.onViewShown();
        } else if (CACHE_VIEW.equals(cardName) && codebaseCachePanel != null) {
            codebaseCachePanel.refreshStatus();
        } else if (MEMORY_VIEW.equals(cardName) && agentMemoryPanel != null) {
            agentMemoryPanel.refresh();
        } else if (DATABASES_VIEW.equals(cardName) && globalDatabasesPanel != null) {
            globalDatabasesPanel.refresh();
        }
    }

    private void syncSidebarWaitingState() {
        if (sidebar == null) {
            return;
        }
        ChatPanel qaChat = qaChatPanel();
        ChatPanel taskChat = taskChatPanel();
        boolean pendingPhase = planService != null && planService.getPendingPhase() != null;
        boolean taskWaitingInput = taskChat != null && taskChat.isWaitingUserInput();
        sidebar.setTaskWaitingApproval(pendingPhase || taskWaitingInput);
        sidebar.setQaWaitingApproval(qaChat != null && qaChat.isWaitingUserInput());
    }

    void updateWorkspaceState() {
        AppIconRenderer.AgentStatus newStatus = resolveCurrentStatus();
        this.currentStatus = newStatus;

        if (newStatus == AppIconRenderer.AgentStatus.WAITING_FEEDBACK) {
            requestTaskbarAttention();
            if (hostFrame != null && !hostFrame.isActive()) {
                startBlinkTimer();
            } else {
                stopBlinkTimer();
            }
        } else {
            stopBlinkTimer();
            clearTaskbarAttention();
        }

        updateTitle();
        applyIconImages(newStatus, blinkHighlight);
    }

    AppIconRenderer.AgentStatus resolveCurrentStatus() {
        if (planService != null && planService.getPendingPhase() != null) {
            return AppIconRenderer.AgentStatus.WAITING_FEEDBACK;
        }
        if (sidebar != null && (sidebar.isTaskWaitingApproval() || sidebar.isQaWaitingApproval())) {
            return AppIconRenderer.AgentStatus.WAITING_FEEDBACK;
        }
        ChatPanel qaChat = qaChatPanel();
        ChatPanel taskChat = taskChatPanel();
        if (qaChat != null && qaChat.isWaitingUserInput()) {
            return AppIconRenderer.AgentStatus.WAITING_FEEDBACK;
        }
        if (taskChat != null && taskChat.isWaitingUserInput()) {
            return AppIconRenderer.AgentStatus.WAITING_FEEDBACK;
        }

        if (qaChat != null && qaChat.isLoading()) {
            return AppIconRenderer.AgentStatus.BUSY;
        }
        if (taskChat != null && taskChat.isLoading()) {
            return AppIconRenderer.AgentStatus.BUSY;
        }

        return AppIconRenderer.AgentStatus.IDLE;
    }

    private void startBlinkTimer() {
        if (blinkTimer != null && blinkTimer.isRunning()) {
            return;
        }
        blinkHighlight = true;
        blinkTimer = new Timer(750, e -> {
            blinkHighlight = !blinkHighlight;
            applyIconImages(currentStatus, blinkHighlight);
        });
        blinkTimer.setRepeats(true);
        blinkTimer.start();
    }

    private void stopBlinkTimer() {
        if (blinkTimer != null && blinkTimer.isRunning()) {
            blinkTimer.stop();
        }
        blinkHighlight = false;
    }

    private void requestTaskbarAttention() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            if (Taskbar.isTaskbarSupported()) {
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar.isSupported(Taskbar.Feature.USER_ATTENTION)) {
                    taskbar.requestUserAttention(true, false);
                }
                if (taskbar.isSupported(Taskbar.Feature.ICON_BADGE_TEXT)) {
                    taskbar.setIconBadge("!");
                }
            }
        } catch (UnsupportedOperationException | SecurityException e) {
            log.trace("Taskbar attention not supported: {}", e.getMessage());
        } catch (Exception e) {
            log.trace("Taskbar attention failed: {}", e.getMessage());
        }
    }

    private void clearTaskbarAttention() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            if (Taskbar.isTaskbarSupported()) {
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar.isSupported(Taskbar.Feature.ICON_BADGE_TEXT)) {
                    taskbar.setIconBadge(null);
                }
            }
        } catch (Exception e) {
            log.trace("Taskbar badge clear failed: {}", e.getMessage());
        }
    }

    private void applyIconImages(AppIconRenderer.AgentStatus status, boolean highlight) {
        if (hostFrame == null) {
            return;
        }
        String projName = getActiveProjectName();
        if (iconImagesApplied && Objects.equals(projName, lastAppliedIconProjectName) && status == lastAppliedIconStatus
                && highlight == lastAppliedIconHighlight) {
            return;
        }
        List<Image> icons = AppIconRenderer.renderAppIconImages(projName, status, highlight);
        hostFrame.setIconImages(icons);
        lastAppliedIconProjectName = projName;
        lastAppliedIconStatus = status;
        lastAppliedIconHighlight = highlight;
        iconImagesApplied = true;
    }

    String getActiveProjectName() {
        if (projectService != null && projectService.hasActiveProject()) {
            return projectService.getProjectName();
        }
        return null;
    }

    void updateTitle() {
        String projectName = getActiveProjectName();
        if (projectName == null || projectName.trim().isEmpty() || "No Project".equalsIgnoreCase(projectName.trim())) {
            setTitle("RoxyCode");
            return;
        }

        setTitle(projectName + " — RoxyCode");
    }

    ExportKind resolveActiveExportKind() {
        if (QA_VIEW.equals(currentCard) && qaWorkspacePanel != null && qaWorkspacePanel.qaTabs != null) {
            java.awt.Component selected = qaWorkspacePanel.qaTabs.getSelectedComponent();
            if (qaWorkspacePanel.pastTopicsPanel != null && qaWorkspacePanel.pastTopicsPanel.equals(selected)) {
                if (qaWorkspacePanel.pastTopicsPanel.canExportSelectedEntry()) {
                    return ExportKind.QA_PAST_TOPICS;
                }
                return ExportKind.NONE;
            }
            if (qaWorkspacePanel.qaChatPanel != null && qaWorkspacePanel.qaChatPanel.hasExportableContent()) {
                return ExportKind.QA_CHAT;
            }
            return ExportKind.NONE;
        }
        if (TASK_VIEW.equals(currentCard) && taskWorkspacePanel != null && taskWorkspacePanel.taskTabs != null) {
            java.awt.Component selected = taskWorkspacePanel.taskTabs.getSelectedComponent();
            if (taskWorkspacePanel.planPanel != null && taskWorkspacePanel.planPanel.equals(selected)) {
                if (taskWorkspacePanel.planPanel.canExportPlan()) {
                    return ExportKind.TASK_PLAN;
                }
                return ExportKind.NONE;
            }
            if (taskWorkspacePanel.pastPlansPanel != null && taskWorkspacePanel.pastPlansPanel.equals(selected)) {
                if (taskWorkspacePanel.pastPlansPanel.canExportSelectedEntry()) {
                    return ExportKind.TASK_PAST_PLANS;
                }
                return ExportKind.NONE;
            }
            if (taskWorkspacePanel.taskChatPanel != null && taskWorkspacePanel.taskChatPanel.hasExportableContent()) {
                return ExportKind.TASK_CHAT;
            }
            return ExportKind.NONE;
        }
        return ExportKind.NONE;
    }

    void updateExportMenuItemsState() {
        boolean canExport = resolveActiveExportKind() != ExportKind.NONE;
        if (printExportHtmlItem != null) {
            printExportHtmlItem.setEnabled(canExport);
        }
        if (saveAsHtmlItem != null) {
            saveAsHtmlItem.setEnabled(canExport);
        }
    }

    boolean exportCurrentView(boolean saveToFile) {
        ExportKind kind = resolveActiveExportKind();
        if (kind == ExportKind.NONE) {
            return false;
        }
        String projectName = getActiveProjectName();
        switch (kind) {
            case QA_CHAT -> {
                String html = HtmlExportService.buildChatExportHtml(projectName, "Ask Roxy — Chat Session",
                        qaWorkspacePanel.qaChatPanel.exportMessages());
                if (saveToFile) {
                    HtmlExportService.saveHtmlWithChooser(this, "ask-roxy-chat", html);
                } else {
                    HtmlExportService.openHtmlInBrowserAsync("roxycode-chat-", html);
                }
                return true;
            }
            case TASK_CHAT -> {
                String html = HtmlExportService.buildChatExportHtml(projectName, "Build Feature — Chat Session",
                        taskWorkspacePanel.taskChatPanel.exportMessages());
                if (saveToFile) {
                    HtmlExportService.saveHtmlWithChooser(this, "build-feature-chat", html);
                } else {
                    HtmlExportService.openHtmlInBrowserAsync("roxycode-chat-", html);
                }
                return true;
            }
            case TASK_PLAN -> {
                if (saveToFile) {
                    taskWorkspacePanel.planPanel.saveCurrentPlanAsHtml(this, projectName);
                } else {
                    taskWorkspacePanel.planPanel.exportCurrentPlanToBrowser(projectName);
                }
                return true;
            }
            case QA_PAST_TOPICS -> {
                if (saveToFile) {
                    qaWorkspacePanel.pastTopicsPanel.saveCurrentEntryAsHtml(this, projectName);
                } else {
                    qaWorkspacePanel.pastTopicsPanel.exportCurrentEntryToBrowser(projectName);
                }
                return true;
            }
            case TASK_PAST_PLANS -> {
                if (saveToFile) {
                    taskWorkspacePanel.pastPlansPanel.saveCurrentEntryAsHtml(this, projectName);
                } else {
                    taskWorkspacePanel.pastPlansPanel.exportCurrentEntryToBrowser(projectName);
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}
