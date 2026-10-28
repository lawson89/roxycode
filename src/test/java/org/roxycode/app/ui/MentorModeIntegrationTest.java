package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
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
        ApplicationEventPublisher pub = mock(ApplicationEventPublisher.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        qaWorkspacePanel = new QaWorkspacePanel(
                new ExploreChatPanel(aiService, planService, pub, chatMemory, settingsService, null, null, null), null);
        taskWorkspacePanel = new TaskWorkspacePanel(
                new FeatureChatPanel(aiService, planService, pub, chatMemory, settingsService, null), planService,
                new PlanPanel(planService, gitService), null);

        sidebar = new SidebarPanel(card -> {
        });
        qaWorkspacePanel.bindSidebar(sidebar);
        taskWorkspacePanel.bindSidebar(sidebar);
    }

    private static ImplementationPlan planWithTitle(String title) {
        return new ImplementationPlan(title, "Goal", List.of(), List.of(), false, null, null, null, null, List.of());
    }

    @Test
    void testDualChatPanelsAreIndependent() {
        ChatPanel qaChat = qaWorkspacePanel.getQaChatPanel();
        ChatPanel taskChat = taskWorkspacePanel.getTaskChatPanel();

        assertEquals(ChatModel.QA_CONVERSATION_ID, qaChat.getModel().getConversationId());
        assertEquals(WorkflowPhase.EXPLORE, qaChat.getModel().getLockedPhase());
        assertFalse(qaChat.getModel().isTaskMode());

        assertEquals(ChatModel.TASK_CONVERSATION_ID, taskChat.getModel().getConversationId());
        assertNull(taskChat.getModel().getLockedPhase());
        assertTrue(taskChat.getModel().isTaskMode());

        qaChat.appendMessage("user", "QA only question");
        assertEquals(1, qaChat.getConversationMessages().size());
        assertTrue(taskChat.getConversationMessages().isEmpty());
    }

    @Test
    void testActivePhaseAndPlanTitleTruncation() {
        JLabel planLabel = taskWorkspacePanel.getPlanLabel();
        when(planService.getVisitedPhases()).thenReturn(EnumSet.of(WorkflowPhase.PLAN, WorkflowPhase.CODE));
        when(planService.getCurrentPlan()).thenReturn(planWithTitle("My Feature Plan"));

        taskWorkspacePanel.updateActivePhase(WorkflowPhase.CODE);
        assertTrue(planLabel.isVisible());
        assertEquals("MY FEATURE PLAN", planLabel.getText());
        assertEquals("My Feature Plan", planLabel.getToolTipText());

        String longTitle = "Consolidate EDT dispatch with Edt.run, clean up ProcessRunner buffers, and standardize PromptService I/O exceptions";
        when(planService.getCurrentPlan()).thenReturn(planWithTitle("  " + longTitle + "  "));
        taskWorkspacePanel.updateActivePhase(WorkflowPhase.CODE);
        assertTrue(planLabel.isVisible());
        assertEquals(longTitle.toUpperCase(Locale.ROOT).substring(0, 37) + "...", planLabel.getText());
        assertEquals(40, planLabel.getText().length());
        assertEquals(longTitle, planLabel.getToolTipText());

        when(planService.getCurrentPlan()).thenReturn(null);
        taskWorkspacePanel.updateActivePhase(WorkflowPhase.PLAN);
        assertFalse(planLabel.isVisible());
        assertEquals("", planLabel.getText());
        assertNull(planLabel.getToolTipText());
    }

    @Test
    void testShowCardFocusesInputAndSetsCaretToEnd() {
        ChatPanel qaChatPanel = qaWorkspacePanel.getQaChatPanel();
        ChatPanel taskChatPanel = taskWorkspacePanel.getTaskChatPanel();
        JTabbedPane taskTabs = taskWorkspacePanel.getTaskTabs();

        qaChatPanel.getInputArea().setText("QA draft message");
        qaChatPanel.getInputArea().setCaretPosition(0);
        taskChatPanel.getInputArea().setText("Task draft message");
        taskChatPanel.getInputArea().setCaretPosition(0);
        taskTabs.setSelectedIndex(1);

        taskWorkspacePanel.onViewShown();
        SwingTestUtils.flushEdt();
        assertEquals(taskChatPanel, taskTabs.getSelectedComponent());
        assertEquals("Task draft message".length(), taskChatPanel.getInputArea().getCaretPosition());

        qaWorkspacePanel.focusChatInput();
        SwingTestUtils.flushEdt();
        assertEquals("QA draft message".length(), qaChatPanel.getInputArea().getCaretPosition());
    }

    @Test
    void testSidebarWaitingApprovalSyncedWithPlanService() {
        assertFalse(sidebar.isTaskWaitingApproval());

        taskWorkspacePanel.onWorkflowTransition(
                new WorkflowTransitionEvent(WorkflowPhase.PLAN, WorkflowPhase.CODE, "Ready to code", false));
        SwingTestUtils.flushEdt();
        assertTrue(sidebar.isTaskWaitingApproval());

        when(planService.getPendingPhase()).thenReturn(null);
        taskWorkspacePanel.onPhaseChanged(new PhaseChangedEvent(WorkflowPhase.CODE));
        SwingTestUtils.flushEdt();
        assertFalse(sidebar.isTaskWaitingApproval());
        assertFalse(sidebar.isQaBusy());
        assertFalse(sidebar.isTaskBusy());

        qaWorkspacePanel.getQaChatPanel().setLoading(true);
        assertTrue(sidebar.isQaBusy());
        assertFalse(sidebar.isTaskBusy());

        qaWorkspacePanel.getQaChatPanel().setLoading(false);
        assertFalse(sidebar.isQaBusy());

        taskWorkspacePanel.getTaskChatPanel().setLoading(true);
        assertTrue(sidebar.isTaskBusy());
        taskWorkspacePanel.getTaskChatPanel().setLoading(false);
        assertFalse(sidebar.isTaskBusy());
    }

    @Test
    void testOnPhaseChangedAndOnPlanUpdatedOffEdtDispatchToEdt() throws Exception {
        ImplementationPlan plan = planWithTitle("Off EDT Plan");
        when(planService.getCurrentPlan()).thenReturn(plan);
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.REVIEW);
        when(planService.getPendingPhase()).thenReturn(WorkflowPhase.PLAN);

        Thread bgThread = new Thread(() -> {
            taskWorkspacePanel.onPlanUpdated(new PlanUpdatedEvent(plan));
            taskWorkspacePanel.onPhaseChanged(new PhaseChangedEvent(WorkflowPhase.REVIEW));
        });
        bgThread.start();
        bgThread.join();
        SwingTestUtils.flushEdt();

        assertTrue(taskWorkspacePanel.getPlanLabel().isVisible());
        assertEquals("OFF EDT PLAN", taskWorkspacePanel.getPlanLabel().getText());
        assertTrue(sidebar.isTaskWaitingApproval());
    }
}
