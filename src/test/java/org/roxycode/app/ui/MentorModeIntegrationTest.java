package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JLabel;
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
    private final AtomicReference<String> selectedCard = new AtomicReference<>();

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

        sidebar = new SidebarPanel(selectedCard::set);
        qaWorkspacePanel.bindSidebar(sidebar);
        taskWorkspacePanel.bindSidebar(sidebar);
    }

    private static ImplementationPlan planWithTitle(String title) {
        return new ImplementationPlan(title, "Goal", List.of(), List.of(), false, null, null, null, null, List.of());
    }

    @Test
    void testActivePhaseAndPlanTitleTruncation() {
        JLabel planLabel = taskWorkspacePanel.planLabel;
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

        qaWorkspacePanel.qaChatPanel.setLoading(true);
        assertTrue(sidebar.isQaBusy());
        assertFalse(sidebar.isTaskBusy());

        qaWorkspacePanel.qaChatPanel.setLoading(false);
        assertFalse(sidebar.isQaBusy());

        taskWorkspacePanel.taskChatPanel.setLoading(true);
        assertTrue(sidebar.isTaskBusy());
        taskWorkspacePanel.taskChatPanel.setLoading(false);
        assertFalse(sidebar.isTaskBusy());

        JButton qaBtn = SwingTestUtils.findButton(sidebar, "Codebase Q&A");
        JButton taskBtn = SwingTestUtils.findButton(sidebar, "Feature Request");
        JButton toolsBtn = SwingTestUtils.findButton(sidebar, "System Tools");
        assertTrue(qaBtn.isSelected());
        assertFalse(taskBtn.isSelected());

        taskBtn.doClick();
        assertEquals("TASK_VIEW", selectedCard.get());

        sidebar.selectCard("TOOLS");
        assertTrue(toolsBtn.isSelected());
        assertFalse(qaBtn.isSelected());
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

        assertTrue(taskWorkspacePanel.planLabel.isVisible());
        assertEquals("OFF EDT PLAN", taskWorkspacePanel.planLabel.getText());
        assertTrue(sidebar.isTaskWaitingApproval());
    }
}
