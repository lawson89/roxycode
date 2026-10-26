package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.Container;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.swing.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.ai.services.cache.ProjectCacheMetaService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.events.PhaseChangedEvent;
import org.roxycode.app.events.WorkflowTransitionEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.PromptService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.service.SystemToolService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.ApplicationEventPublisher;

class MentorModeIntegrationTest {

    private SettingsService settingsService;
    private ProjectService projectService;
    private PlanService planService;
    private QaWorkspacePanel qaWorkspacePanel;
    private TaskWorkspacePanel taskWorkspacePanel;
    private MainFrame mainFrame;

    @BeforeEach
    void setUp() {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                java.awt.GraphicsEnvironment.isHeadless(),
                "Skipping JFrame test in headless environment");
        settingsService = mock(SettingsService.class);
        when(settingsService.getSettings()).thenReturn(new AppSettings());
        projectService = mock(ProjectService.class);
        when(projectService.getProjectName()).thenReturn("TestProject");

        planService = mock(PlanService.class);
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);

        AiService aiService = mock(AiService.class);
        SystemToolService toolService = mock(SystemToolService.class);
        when(toolService.detectTools()).thenReturn(CompletableFuture.completedFuture(List.of()));
        GitService gitService = mock(GitService.class);
        EnvironmentService envService = mock(EnvironmentService.class);
        JexlServiceRegistry jexlServiceRegistry = mock(JexlServiceRegistry.class);
        when(jexlServiceRegistry.getDocumentation(any())).thenReturn("");
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        RepoMapPackerService packerService = mock(RepoMapPackerService.class);
        ProjectCacheMetaService cacheMetaService = mock(ProjectCacheMetaService.class);
        PromptService promptService = mock(PromptService.class);
        GenericBuildToolService buildToolService = mock(GenericBuildToolService.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        ExploreChatPanel exploreChatPanel =
                new ExploreChatPanel(
                        aiService, planService, eventPublisher, chatMemory, settingsService, null);
        ExploreHistoryPanel exploreHistoryPanel = new ExploreHistoryPanel(chatMemory);
        FeatureChatPanel featureChatPanel =
                new FeatureChatPanel(
                        aiService, planService, eventPublisher, chatMemory, settingsService, null);
        FeatureHistoryPanel featureHistoryPanel = new FeatureHistoryPanel(chatMemory);

        HeaderPanel headerPanel = new HeaderPanel(projectService, gitService, settingsService);
        StatusPanel statusPanel = new StatusPanel(envService);
        qaWorkspacePanel = new QaWorkspacePanel(exploreChatPanel, exploreHistoryPanel);
        PlanPanel planPanel = new PlanPanel(planService, gitService);
        taskWorkspacePanel =
                new TaskWorkspacePanel(
                        featureChatPanel, featureHistoryPanel, planService, planPanel);
        CodebaseCachePanel codebaseCachePanel =
                new CodebaseCachePanel(packerService, cacheMetaService, promptService);
        JexlApiPanel jexlApiPanel = new JexlApiPanel(jexlServiceRegistry);
        SystemToolsPanel systemToolsPanel = new SystemToolsPanel(toolService);
        SettingsPanel settingsPanel = new SettingsPanel(settingsService);
        BuildConfigPanel buildConfigPanel =
                new BuildConfigPanel(buildToolService, settingsService, toolService);
        ContextManagerPanel contextManagerPanel =
                new ContextManagerPanel(promptService, settingsService);

        mainFrame =
                new MainFrame(
                        settingsService,
                        projectService,
                        chatMemory,
                        headerPanel,
                        statusPanel,
                        qaWorkspacePanel,
                        taskWorkspacePanel,
                        codebaseCachePanel,
                        jexlApiPanel,
                        systemToolsPanel,
                        settingsPanel,
                        buildConfigPanel,
                        contextManagerPanel);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mainFrame != null) {
            SwingUtilities.invokeAndWait(mainFrame::dispose);
        }
    }

    @Test
    void testWorkspaceBarsLocatedInRespectiveViews() throws Exception {
        HeaderPanel header = mainFrame.getHeader();
        JPanel workspace = (JPanel) header.getParent();
        net.miginfocom.swing.MigLayout workspaceLayout =
                (net.miginfocom.swing.MigLayout) workspace.getLayout();
        assertEquals(
                "h 36!, wrap",
                workspaceLayout.getComponentConstraints(header),
                "Main header bar should have a compact height constraint of h 36!");

        JTabbedPane qaTabs = qaWorkspacePanel.getQaTabs();
        assertEquals(
                com.formdev.flatlaf.FlatClientProperties.TABBED_PANE_TAB_TYPE_UNDERLINED,
                qaTabs.getClientProperty(
                        com.formdev.flatlaf.FlatClientProperties.TABBED_PANE_TAB_TYPE));
        JPanel qaHeaderBar =
                (JPanel)
                        qaTabs.getClientProperty(
                                com.formdev.flatlaf.FlatClientProperties
                                        .TABBED_PANE_TRAILING_COMPONENT);
        assertNotNull(qaHeaderBar);
        assertSame(qaWorkspacePanel.getQaHeaderBar(), qaHeaderBar);
        JLabel mentorModeLabel = (JLabel) qaHeaderBar.getComponent(0);
        assertEquals("READ-ONLY MENTOR MODE", mentorModeLabel.getText());
        assertFalse(
                mentorModeLabel.isOpaque(),
                "mentorModeLabel should not be opaque so pill badge renders cleanly");
        String mentorStyle =
                (String)
                        mentorModeLabel.getClientProperty(
                                com.formdev.flatlaf.FlatClientProperties.STYLE);
        assertNotNull(mentorStyle);
        assertTrue(mentorStyle.contains("arc: 12"));
        assertTrue(mentorStyle.contains("border: 3,10,3,10"));

        JPanel progressTracker = taskWorkspacePanel.getProgressTracker();
        JLabel planLabel = taskWorkspacePanel.getPlanLabel();
        JTabbedPane taskTabs = taskWorkspacePanel.getTaskTabs();
        assertEquals(
                com.formdev.flatlaf.FlatClientProperties.TABBED_PANE_TAB_TYPE_UNDERLINED,
                taskTabs.getClientProperty(
                        com.formdev.flatlaf.FlatClientProperties.TABBED_PANE_TAB_TYPE));
        Container taskHeaderBar = progressTracker.getParent();
        assertSame(
                taskHeaderBar,
                taskTabs.getClientProperty(
                        com.formdev.flatlaf.FlatClientProperties.TABBED_PANE_TRAILING_COMPONENT));
        assertSame(taskWorkspacePanel.getTopBar(), taskHeaderBar);
        assertSame(
                taskHeaderBar,
                planLabel.getParent(),
                "planLabel and progressTracker should share taskHeaderBar");
        assertEquals(
                0,
                taskHeaderBar.getComponentZOrder(progressTracker),
                "progressTracker should precede planLabel");
        assertEquals(
                1,
                taskHeaderBar.getComponentZOrder(planLabel),
                "planLabel should sit to the right of progressTracker");

        // 3 phase indicators + 2 wizard connector lines = 5 components
        assertEquals(
                5,
                progressTracker.getComponentCount(),
                "Progress tracker should have 3 indicators and 2 wizard connector lines");
        assertInstanceOf(WorkflowPhaseIndicator.class, progressTracker.getComponent(0));
        assertFalse(progressTracker.getComponent(1) instanceof WorkflowPhaseIndicator);
        assertInstanceOf(WorkflowPhaseIndicator.class, progressTracker.getComponent(2));
        assertFalse(progressTracker.getComponent(3) instanceof WorkflowPhaseIndicator);
        assertInstanceOf(WorkflowPhaseIndicator.class, progressTracker.getComponent(4));

        // Verify planLabel updates when a plan is active
        ImplementationPlan mockPlan =
                new ImplementationPlan(
                        "My Feature Plan",
                        "Goal",
                        List.of(),
                        List.of(),
                        false,
                        null,
                        null,
                        null,
                        List.of());
        when(planService.getCurrentPlan()).thenReturn(mockPlan);

        taskWorkspacePanel.updateActivePhase(WorkflowPhase.CODE);

        assertTrue(planLabel.isVisible());
        assertEquals("MY FEATURE PLAN", planLabel.getText());
        assertEquals("My Feature Plan", planLabel.getToolTipText());

        // Verify long plan titles (> 40 chars) are truncated with ellipsis and preserve full
        // tooltip
        String longTitle =
                "Consolidate EDT dispatch with Edt.run, clean up ProcessRunner buffers, and standardize PromptService I/O exceptions";
        ImplementationPlan longPlan =
                new ImplementationPlan(
                        "  " + longTitle + "  ",
                        "Goal",
                        List.of(),
                        List.of(),
                        false,
                        null,
                        null,
                        null,
                        List.of());
        when(planService.getCurrentPlan()).thenReturn(longPlan);

        taskWorkspacePanel.updateActivePhase(WorkflowPhase.CODE);

        assertTrue(planLabel.isVisible());
        assertEquals(
                longTitle.toUpperCase(java.util.Locale.ROOT).substring(0, 37) + "...",
                planLabel.getText());
        assertEquals(40, planLabel.getText().length());
        assertEquals(longTitle, planLabel.getToolTipText());

        // Verify clearing plan resets text, tooltip, and visibility
        when(planService.getCurrentPlan()).thenReturn(null);
        taskWorkspacePanel.updateActivePhase(WorkflowPhase.PLAN);

        assertFalse(planLabel.isVisible());
        assertEquals("", planLabel.getText());
        assertNull(planLabel.getToolTipText());
    }

    @Test
    void testShowCardFocusesInputAndSetsCaretToEnd() throws Exception {
        ChatPanel qaChatPanel = qaWorkspacePanel.getQaChatPanel();
        ChatPanel taskChatPanel = taskWorkspacePanel.getTaskChatPanel();
        JTabbedPane taskTabs = taskWorkspacePanel.getTaskTabs();

        qaChatPanel.getInputArea().setText("QA draft message");
        qaChatPanel.getInputArea().setCaretPosition(0);

        taskChatPanel.getInputArea().setText("Task draft message");
        taskChatPanel.getInputArea().setCaretPosition(0);

        // Select another tab in taskTabs (e.g., Plan tab at index 1)
        taskTabs.setSelectedIndex(1);

        // Switch to TASK_VIEW
        mainFrame.showCard("TASK_VIEW");
        SwingUtilities.invokeAndWait(() -> {});

        assertEquals(
                taskChatPanel,
                taskTabs.getSelectedComponent(),
                "Chat tab should be selected in TASK_VIEW");
        assertEquals(
                "Task draft message".length(),
                taskChatPanel.getInputArea().getCaretPosition(),
                "Task input caret should be at the end of the text");

        // Switch back to QA_VIEW
        mainFrame.showCard("QA_VIEW");
        SwingUtilities.invokeAndWait(() -> {});

        assertEquals(
                "QA draft message".length(),
                qaChatPanel.getInputArea().getCaretPosition(),
                "QA input caret should be at the end of the text");
    }

    @Test
    void testSidebarWaitingApprovalSyncedWithPlanService() throws Exception {
        SidebarPanel sidebar = null;
        for (java.awt.Component comp : mainFrame.getContentPane().getComponents()) {
            if (comp instanceof SidebarPanel sp) {
                sidebar = sp;
                break;
            }
        }
        assertNotNull(sidebar, "MainFrame should contain a SidebarPanel");
        assertFalse(sidebar.isTaskWaitingApproval());

        taskWorkspacePanel.onWorkflowTransition(
                new WorkflowTransitionEvent(
                        WorkflowPhase.PLAN, WorkflowPhase.CODE, "Ready to code", false));
        SwingUtilities.invokeAndWait(() -> {});
        assertTrue(
                sidebar.isTaskWaitingApproval(),
                "Sidebar should show waiting approval when transition is requested");

        when(planService.getPendingPhase()).thenReturn(null);
        taskWorkspacePanel.onPhaseChanged(new PhaseChangedEvent(WorkflowPhase.CODE));
        SwingUtilities.invokeAndWait(() -> {});
        assertFalse(
                sidebar.isTaskWaitingApproval(),
                "Sidebar waiting approval should clear when pending phase is null on phase change");

        // Verify both QaWorkspacePanel and TaskWorkspacePanel loading states update SidebarPanel
        assertFalse(sidebar.isQaBusy());
        assertFalse(sidebar.isTaskBusy());

        qaWorkspacePanel.getQaChatPanel().setLoading(true);
        assertTrue(
                sidebar.isQaBusy(),
                "Sidebar Codebase Q&A button should show busy indicator when Q&A chat is loading");
        assertFalse(sidebar.isTaskBusy());

        qaWorkspacePanel.getQaChatPanel().setLoading(false);
        assertFalse(
                sidebar.isQaBusy(),
                "Sidebar Codebase Q&A button should clear busy indicator when Q&A chat finishes loading");

        taskWorkspacePanel.getTaskChatPanel().setLoading(true);
        assertTrue(
                sidebar.isTaskBusy(),
                "Sidebar Feature Request button should show busy indicator when task chat is loading");
        taskWorkspacePanel.getTaskChatPanel().setLoading(false);
        assertFalse(sidebar.isTaskBusy());
    }

    @Test
    void testOnPhaseChangedAndOnPlanUpdatedOffEdtDispatchToEdt() throws Exception {
        ImplementationPlan plan =
                new ImplementationPlan(
                        "Off EDT Plan",
                        "Goal",
                        List.of(),
                        List.of(),
                        false,
                        null,
                        null,
                        null,
                        List.of());
        when(planService.getCurrentPlan()).thenReturn(plan);
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.REVIEW);
        when(planService.getPendingPhase()).thenReturn(WorkflowPhase.PLAN);

        SidebarPanel sidebar = null;
        for (java.awt.Component comp : mainFrame.getContentPane().getComponents()) {
            if (comp instanceof SidebarPanel sp) {
                sidebar = sp;
                break;
            }
        }
        assertNotNull(sidebar);

        Thread bgThread =
                new Thread(
                        () -> {
                            taskWorkspacePanel.onPlanUpdated(
                                    new org.roxycode.app.events.PlanUpdatedEvent(plan));
                            taskWorkspacePanel.onPhaseChanged(
                                    new PhaseChangedEvent(WorkflowPhase.REVIEW));
                        });
        bgThread.start();
        bgThread.join();

        SwingUtilities.invokeAndWait(() -> {});

        assertTrue(taskWorkspacePanel.getPlanLabel().isVisible());
        assertEquals("OFF EDT PLAN", taskWorkspacePanel.getPlanLabel().getText());
        assertTrue(sidebar.isTaskWaitingApproval());
    }
}
