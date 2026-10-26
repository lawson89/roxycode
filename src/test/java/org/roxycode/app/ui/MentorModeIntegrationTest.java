package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Container;
import java.util.List;
import java.util.Locale;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.PhaseChangedEvent;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.events.WorkflowTransitionEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.SettingsService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.ApplicationEventPublisher;

class MentorModeIntegrationTest {

    private PlanService planService;
    private QaWorkspacePanel qaWorkspacePanel;
    private TaskWorkspacePanel taskWorkspacePanel;
    private SidebarPanel sidebar;

    @BeforeEach
    void setUp() {
        SettingsService settingsService = mock(SettingsService.class);
        when(settingsService.getSettings()).thenReturn(new AppSettings());

        planService = mock(PlanService.class);
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);

        AiService aiService = mock(AiService.class);
        GitService gitService = mock(GitService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        ExploreChatPanel exploreChatPanel =
                new ExploreChatPanel(
                        aiService, planService, eventPublisher, chatMemory, settingsService, null);
        ExploreHistoryPanel exploreHistoryPanel = new ExploreHistoryPanel(chatMemory);
        FeatureChatPanel featureChatPanel =
                new FeatureChatPanel(
                        aiService, planService, eventPublisher, chatMemory, settingsService, null);
        FeatureHistoryPanel featureHistoryPanel = new FeatureHistoryPanel(chatMemory);

        qaWorkspacePanel = new QaWorkspacePanel(exploreChatPanel, exploreHistoryPanel);
        PlanPanel planPanel = new PlanPanel(planService, gitService);
        taskWorkspacePanel =
                new TaskWorkspacePanel(
                        featureChatPanel, featureHistoryPanel, planService, planPanel);

        sidebar = new SidebarPanel(card -> {});
        qaWorkspacePanel.bindSidebar(sidebar);
        taskWorkspacePanel.bindSidebar(sidebar);
    }

    @Test
    void testWorkspaceBarsLocatedInRespectiveViews() {
        JTabbedPane qaTabs = qaWorkspacePanel.getQaTabs();
        assertEquals(
                FlatClientProperties.TABBED_PANE_TAB_TYPE_UNDERLINED,
                qaTabs.getClientProperty(FlatClientProperties.TABBED_PANE_TAB_TYPE));
        JPanel qaHeaderBar =
                (JPanel)
                        qaTabs.getClientProperty(
                                FlatClientProperties.TABBED_PANE_TRAILING_COMPONENT);
        assertNotNull(qaHeaderBar);
        assertSame(qaWorkspacePanel.getQaHeaderBar(), qaHeaderBar);
        JLabel mentorModeLabel = (JLabel) qaHeaderBar.getComponent(0);
        assertEquals("READ-ONLY MENTOR MODE", mentorModeLabel.getText());
        assertFalse(
                mentorModeLabel.isOpaque(),
                "mentorModeLabel should not be opaque so pill badge renders cleanly");
        String mentorStyle = (String) mentorModeLabel.getClientProperty(FlatClientProperties.STYLE);
        assertNotNull(mentorStyle);
        assertTrue(mentorStyle.contains("arc: 12"));
        assertTrue(mentorStyle.contains("border: 3,10,3,10"));

        JPanel progressTracker = taskWorkspacePanel.getProgressTracker();
        JLabel planLabel = taskWorkspacePanel.getPlanLabel();
        JTabbedPane taskTabs = taskWorkspacePanel.getTaskTabs();
        assertEquals(
                FlatClientProperties.TABBED_PANE_TAB_TYPE_UNDERLINED,
                taskTabs.getClientProperty(FlatClientProperties.TABBED_PANE_TAB_TYPE));
        Container taskHeaderBar = progressTracker.getParent();
        assertSame(
                taskHeaderBar,
                taskTabs.getClientProperty(FlatClientProperties.TABBED_PANE_TRAILING_COMPONENT));
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

        assertEquals(
                5,
                progressTracker.getComponentCount(),
                "Progress tracker should have 3 indicators and 2 wizard connector lines");
        assertInstanceOf(WorkflowPhaseIndicator.class, progressTracker.getComponent(0));
        assertFalse(progressTracker.getComponent(1) instanceof WorkflowPhaseIndicator);
        assertInstanceOf(WorkflowPhaseIndicator.class, progressTracker.getComponent(2));
        assertFalse(progressTracker.getComponent(3) instanceof WorkflowPhaseIndicator);
        assertInstanceOf(WorkflowPhaseIndicator.class, progressTracker.getComponent(4));

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
                longTitle.toUpperCase(Locale.ROOT).substring(0, 37) + "...", planLabel.getText());
        assertEquals(40, planLabel.getText().length());
        assertEquals(longTitle, planLabel.getToolTipText());

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

        taskTabs.setSelectedIndex(1);

        taskWorkspacePanel.onViewShown();
        SwingUtilities.invokeAndWait(() -> {});

        assertEquals(
                taskChatPanel,
                taskTabs.getSelectedComponent(),
                "Chat tab should be selected in TASK_VIEW");
        assertEquals(
                "Task draft message".length(),
                taskChatPanel.getInputArea().getCaretPosition(),
                "Task input caret should be at the end of the text");

        qaWorkspacePanel.focusChatInput();
        SwingUtilities.invokeAndWait(() -> {});

        assertEquals(
                "QA draft message".length(),
                qaChatPanel.getInputArea().getCaretPosition(),
                "QA input caret should be at the end of the text");
    }

    @Test
    void testSidebarWaitingApprovalSyncedWithPlanService() throws Exception {
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

        Thread bgThread =
                new Thread(
                        () -> {
                            taskWorkspacePanel.onPlanUpdated(new PlanUpdatedEvent(plan));
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
