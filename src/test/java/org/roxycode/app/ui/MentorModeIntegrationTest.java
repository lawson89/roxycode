package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Container;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.border.CompoundBorder;
import javax.swing.border.MatteBorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
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
    void testWorkspaceBarsLocatedInRespectiveViews() {
        JTabbedPane qaTabs = qaWorkspacePanel.getQaTabs();
        assertEquals(2, qaTabs.getTabCount());
        assertEquals("Chat", qaTabs.getTitleAt(0));
        assertEquals("Past Topics", qaTabs.getTitleAt(1));
        assertInstanceOf(ArchiveBrowserPanel.class, qaTabs.getComponentAt(1));
        assertEquals(FlatClientProperties.TABBED_PANE_TAB_TYPE_UNDERLINED,
                qaTabs.getClientProperty(FlatClientProperties.TABBED_PANE_TAB_TYPE));
        JPanel qaHeaderBar = (JPanel) qaTabs.getClientProperty(FlatClientProperties.TABBED_PANE_TRAILING_COMPONENT);
        assertSame(qaWorkspacePanel.getQaHeaderBar(), qaHeaderBar);

        JPanel mentorBanner = assertInstanceOf(JPanel.class, qaHeaderBar.getComponent(0));
        assertSame(qaWorkspacePanel.getMentorBanner(), mentorBanner);
        assertEquals(255, mentorBanner.getBackground().getAlpha());
        String bannerStyle = (String) mentorBanner.getClientProperty(FlatClientProperties.STYLE);
        assertTrue(bannerStyle != null && bannerStyle.startsWith("background: #"));
        assertFalse(bannerStyle.contains("fade("));

        JLabel mentorModeLabel = qaWorkspacePanel.getMentorModeLabel();
        assertEquals("READ-ONLY MENTOR MODE", mentorModeLabel.getText());
        assertEquals(Codicons.SEARCH, assertInstanceOf(FontIcon.class, mentorModeLabel.getIcon()).getIkon());
        assertTrue(qaWorkspacePanel.getMentorSubtitleLabel().getText().contains(QaWorkspacePanel.MENTOR_MODE_SUBTITLE));

        qaWorkspacePanel.updateUI();
        assertNotNull(mentorModeLabel.getIcon());

        JPanel progressTracker = taskWorkspacePanel.getProgressTracker();
        JLabel phaseHeaderLabel = taskWorkspacePanel.getPhaseHeaderLabel();
        JLabel planLabel = taskWorkspacePanel.getPlanLabel();
        JTabbedPane taskTabs = taskWorkspacePanel.getTaskTabs();
        assertEquals(3, taskTabs.getTabCount());
        assertEquals("Chat", taskTabs.getTitleAt(0));
        assertEquals("Plan", taskTabs.getTitleAt(1));
        assertEquals("Past Plans", taskTabs.getTitleAt(2));
        assertInstanceOf(ArchiveBrowserPanel.class, taskTabs.getComponentAt(2));
        assertEquals(FlatClientProperties.TABBED_PANE_TAB_TYPE_UNDERLINED,
                taskTabs.getClientProperty(FlatClientProperties.TABBED_PANE_TAB_TYPE));
        Container taskHeaderBar = progressTracker.getParent();
        assertSame(taskHeaderBar, taskTabs.getClientProperty(FlatClientProperties.TABBED_PANE_TRAILING_COMPONENT));
        assertSame(taskWorkspacePanel.getTopBar(), taskHeaderBar);
        assertSame(taskHeaderBar, planLabel.getParent());
        assertEquals(0, taskHeaderBar.getComponentZOrder(progressTracker));
        assertEquals(1, taskHeaderBar.getComponentZOrder(planLabel));

        assertTrue(progressTracker.isOpaque());
        assertEquals(255, progressTracker.getBackground().getAlpha());
        CompoundBorder trackerBorder = assertInstanceOf(CompoundBorder.class, progressTracker.getBorder());
        assertEquals(4, assertInstanceOf(MatteBorder.class, trackerBorder.getOutsideBorder()).getBorderInsets().left);
        String trackerStyle = (String) progressTracker.getClientProperty(FlatClientProperties.STYLE);
        assertTrue(trackerStyle != null && trackerStyle.startsWith("background: #") && !trackerStyle.contains("arc:")
                && !trackerStyle.contains("fade("));

        assertEquals(6, progressTracker.getComponentCount());
        assertSame(phaseHeaderLabel, progressTracker.getComponent(0));
        assertEquals("CURRENT PHASE", phaseHeaderLabel.getText());
        assertEquals("font: bold -2", phaseHeaderLabel.getClientProperty(FlatClientProperties.STYLE));
        assertEquals(SwingHtmlUtils.getAccentColor(), phaseHeaderLabel.getForeground());
        for (int idx : new int[]{1, 3, 5}) {
            assertInstanceOf(WorkflowPhaseIndicator.class, progressTracker.getComponent(idx));
        }
        for (int idx : new int[]{2, 4}) {
            FontIcon chevron = assertInstanceOf(FontIcon.class,
                    assertInstanceOf(JLabel.class, progressTracker.getComponent(idx)).getIcon());
            assertEquals(Codicons.CHEVRON_RIGHT, chevron.getIkon());
            assertEquals(12, chevron.getIconSize());
        }

        when(planService.getVisitedPhases()).thenReturn(EnumSet.of(WorkflowPhase.PLAN, WorkflowPhase.CODE));
        when(planService.getCurrentPlan()).thenReturn(planWithTitle("My Feature Plan"));
        taskWorkspacePanel.updateActivePhase(WorkflowPhase.CODE);
        assertTrue(planLabel.isVisible());
        assertEquals("MY FEATURE PLAN", planLabel.getText());
        assertEquals("My Feature Plan", planLabel.getToolTipText());
        assertEquals(Codicons.PROJECT, assertInstanceOf(FontIcon.class, planLabel.getIcon()).getIkon());
        String planStyle = (String) planLabel.getClientProperty(FlatClientProperties.STYLE);
        assertTrue(planStyle != null && planStyle.contains("background: #") && !planStyle.contains("fade("));

        taskWorkspacePanel.updateUI();
        assertNotNull(planLabel.getIcon());

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
