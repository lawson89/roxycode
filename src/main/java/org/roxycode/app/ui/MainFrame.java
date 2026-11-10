package org.roxycode.app.ui;

import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Taskbar;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.util.ArrayList;
import java.util.List;
import org.roxycode.app.model.AppSettings;
import java.util.concurrent.CompletableFuture;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.Timer;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.PhaseChangedEvent;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.events.SystemToolsUpdatedEvent;
import org.roxycode.app.events.WorkflowTransitionEvent;
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
    static final String SETTINGS_VIEW = "SETTINGS";
    static final String BUILD_CONFIG_VIEW = "BUILD_CONFIG";
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
    private final QaWorkspacePanel qaWorkspacePanel;
    private final TaskWorkspacePanel taskWorkspacePanel;
    private final CodebaseCachePanel codebaseCachePanel;
    private final AgentMemoryPanel agentMemoryPanel;
    private final transient PlanService planService;
    private final transient UiComponents.UiGuard hubTabGuard = new UiComponents.UiGuard();
    JTabbedPane contextHubTabs;
    JTabbedPane settingsHubTabs;
    SidebarPanel sidebar;
    String currentCard = QA_VIEW;
    private boolean initialToolsCheckComplete;
    private List<SystemTool> lastStartupTools = List.of();
    private Timer blinkTimer;
    private boolean blinkHighlight;
    AppIconRenderer.AgentStatus currentStatus = AppIconRenderer.AgentStatus.IDLE;
    Rectangle lastNormalBounds;
    int simulatedExtendedState = Frame.NORMAL;

    @Autowired
    public MainFrame(SettingsService settingsService, ProjectService projectService, HeaderPanel header,
            StatusPanel statusBar, QaWorkspacePanel qaWorkspacePanel, TaskWorkspacePanel taskWorkspacePanel,
            CodebaseCachePanel codebaseCachePanel, JexlApiPanel jexlApiPanel, SystemToolsPanel systemToolsPanel,
            SettingsPanel settingsPanel, BuildConfigPanel buildConfigPanel, ContextManagerPanel contextManagerPanel,
            @Nullable AgentMemoryPanel agentMemoryPanel) {
        this.settingsService = settingsService;
        this.projectService = projectService;
        this.header = header;
        this.qaWorkspacePanel = qaWorkspacePanel;
        this.taskWorkspacePanel = taskWorkspacePanel;
        this.codebaseCachePanel = codebaseCachePanel;
        this.agentMemoryPanel = agentMemoryPanel;
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
    public void onWorkflowTransition(WorkflowTransitionEvent event) {
        Edt.run(this::updateWorkspaceState);
    }

    @EventListener
    public void onPhaseChanged(PhaseChangedEvent event) {
        Edt.run(this::updateWorkspaceState);
    }

    @EventListener
    public void onPlanUpdated(PlanUpdatedEvent event) {
        Edt.run(this::updateWorkspaceState);
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
            updateWorkspaceState();
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
        } else if (hasProject && !buildConfirmed && TASK_VIEW.equals(currentCard)) {
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
            ContextManagerPanel contextManagerPanel, @Nullable AgentMemoryPanel memoryPanel) {
        setLayout(new MigLayout("fill, insets 0", "[210!, fill]0[fill, grow]", "[fill, grow]"));

        sidebar = new SidebarPanel(this::showCard);
        header.setOnNavigateToCard(this::showCard);
        qaWorkspacePanel.setOnMoveToFeatureListener(content -> {
            showCard(TASK_VIEW);
            ChatPanel taskChat = taskChatPanel();
            if (taskChat != null) {
                taskChat.submitMovedFeatureRequest(content);
            }
        });
        taskWorkspacePanel.bindSidebar(sidebar);

        ChatPanel qaChat = qaChatPanel();
        if (qaChat != null) {
            qaChat.addLoadingListener(loading -> Edt.run(() -> {
                sidebar.setQaBusy(loading);
                updateWorkspaceState();
            }));
            qaChat.addUserInputListener(waiting -> Edt.run(() -> {
                sidebar.setQaWaitingApproval(waiting);
                updateWorkspaceState();
            }));
        }
        ChatPanel taskChat = taskChatPanel();
        if (taskChat != null) {
            taskChat.addLoadingListener(loading -> Edt.run(() -> {
                sidebar.setTaskBusy(loading);
                updateWorkspaceState();
            }));
            taskChat.addUserInputListener(waiting -> Edt.run(() -> {
                boolean pendingPhase = planService != null && planService.getPendingPhase() != null;
                sidebar.setTaskWaitingApproval(waiting || pendingPhase);
                updateWorkspaceState();
            }));
        }

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
        contextHubTabs.addChangeListener(e -> {
            if (hubTabGuard.isActive()) {
                return;
            }
            java.awt.Component selected = contextHubTabs.getSelectedComponent();
            if (codebaseCachePanel != null && codebaseCachePanel.equals(selected)) {
                currentCard = CACHE_VIEW;
                codebaseCachePanel.refreshStatus();
            } else if (jexlApiPanel != null && jexlApiPanel.equals(selected)) {
                currentCard = API_VIEW;
            } else {
                currentCard = CONTEXT_MGR_VIEW;
            }
            refreshHubTabIcons();
        });

        settingsHubTabs = UiComponents.createUnderlinedTabbedPane(null);
        if (settingsPanel != null) {
            settingsHubTabs.addTab("General & AI",
                    FontIcon.of(Codicons.SETTINGS_GEAR, 15, SwingHtmlUtils.getAccentColor()), settingsPanel);
        }
        if (systemToolsPanel != null) {
            settingsHubTabs.addTab("System Tools",
                    FontIcon.of(Codicons.TOOLS, 15, SwingHtmlUtils.getMutedForegroundColor()), systemToolsPanel);
        }
        settingsHubTabs.addChangeListener(e -> {
            if (hubTabGuard.isActive()) {
                return;
            }
            java.awt.Component selected = settingsHubTabs.getSelectedComponent();
            if (systemToolsPanel != null && systemToolsPanel.equals(selected)) {
                currentCard = TOOLS_VIEW;
            } else {
                currentCard = SETTINGS_VIEW;
            }
            refreshHubTabIcons();
        });

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

    @Override
    public void updateUI() {
        super.updateUI();
        refreshHubTabIcons();
    }

    private void refreshHubTabIcons() {
        updateSingleHubTabIcons(contextHubTabs, List.of(Codicons.FILE, Codicons.DATABASE, Codicons.CODE));
        updateSingleHubTabIcons(settingsHubTabs, List.of(Codicons.SETTINGS_GEAR, Codicons.TOOLS));
    }

    private static void updateSingleHubTabIcons(JTabbedPane tabs, List<Codicons> icons) {
        if (tabs == null || tabs.getTabCount() != icons.size()) {
            return;
        }
        java.awt.Color accent = SwingHtmlUtils.getAccentColor();
        java.awt.Color muted = SwingHtmlUtils.getMutedForegroundColor();
        int selectedIndex = tabs.getSelectedIndex();
        List<java.awt.Color> colors = new ArrayList<>(icons.size());
        for (int i = 0; i < icons.size(); i++) {
            if (i == selectedIndex) {
                colors.add(accent);
            } else {
                colors.add(muted);
            }
        }
        UiComponents.updateTabIcons(tabs, icons, colors);
    }

    void showCard(String cardName) {
        this.currentCard = cardName;
        if (sidebar != null) {
            sidebar.selectCard(cardName);
        }
        String layoutCard = resolveHubLayoutCard(cardName);
        selectHubSubTab(cardName);
        cardLayout.show(contentArea, layoutCard);
        updateTitle();
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

    private static String resolveHubLayoutCard(String cardName) {
        if (CACHE_VIEW.equals(cardName) || API_VIEW.equals(cardName) || CONTEXT_MGR_VIEW.equals(cardName)) {
            return CONTEXT_MGR_VIEW;
        }
        if (TOOLS_VIEW.equals(cardName) || SETTINGS_VIEW.equals(cardName)) {
            return SETTINGS_VIEW;
        }
        return cardName;
    }

    private void selectHubSubTab(String cardName) {
        hubTabGuard.run(() -> {
            if (contextHubTabs != null) {
                if (CONTEXT_MGR_VIEW.equals(cardName) && contextHubTabs.getTabCount() > TAB_INDEX_FIRST) {
                    contextHubTabs.setSelectedIndex(TAB_INDEX_FIRST);
                } else if (CACHE_VIEW.equals(cardName) && contextHubTabs.getTabCount() > TAB_INDEX_SECOND) {
                    contextHubTabs.setSelectedIndex(TAB_INDEX_SECOND);
                } else if (API_VIEW.equals(cardName) && contextHubTabs.getTabCount() > TAB_INDEX_THIRD) {
                    contextHubTabs.setSelectedIndex(TAB_INDEX_THIRD);
                }
            }
            if (settingsHubTabs != null) {
                if (SETTINGS_VIEW.equals(cardName) && settingsHubTabs.getTabCount() > TAB_INDEX_FIRST) {
                    settingsHubTabs.setSelectedIndex(TAB_INDEX_FIRST);
                } else if (TOOLS_VIEW.equals(cardName) && settingsHubTabs.getTabCount() > TAB_INDEX_SECOND) {
                    settingsHubTabs.setSelectedIndex(TAB_INDEX_SECOND);
                }
            }
        });
        refreshHubTabIcons();
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
        List<Image> icons = AppIconRenderer.renderAppIconImages(projName, status, highlight);
        hostFrame.setIconImages(icons);
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
}
