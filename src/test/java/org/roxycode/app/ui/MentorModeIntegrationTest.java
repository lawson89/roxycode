package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.Container;
import java.util.List;
import javax.swing.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.ai.services.cache.GeminiCacheService;
import org.roxycode.app.ai.services.cache.ProjectCacheMetaService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.events.TurnEventBridge;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.PromptService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.service.SystemToolService;
import org.springframework.ai.chat.memory.ChatMemory;

class MentorModeIntegrationTest {

    private SettingsService settingsService;
    private ProjectService projectService;
    private WorkflowService workflowService;
    private PlanService planService;
    private MainFrame mainFrame;

    @BeforeEach
    void setUp() {
        settingsService = mock(SettingsService.class);
        when(settingsService.getSettings()).thenReturn(new AppSettings());
        
        projectService = mock(ProjectService.class);
        when(projectService.getProjectName()).thenReturn("TestProject");
        
        workflowService = mock(WorkflowService.class);
        when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);
        
        AiService aiService = mock(AiService.class);
        SystemToolService toolService = mock(SystemToolService.class);
        GitService gitService = mock(GitService.class);
        EnvironmentService envService = mock(EnvironmentService.class);
        JexlServiceRegistry jexlServiceRegistry = mock(JexlServiceRegistry.class);
        planService = mock(PlanService.class);
        TurnEventBridge turnEventBridge = mock(TurnEventBridge.class);
        RepoMapPackerService packerService = mock(RepoMapPackerService.class);
        ProjectCacheMetaService cacheMetaService = mock(ProjectCacheMetaService.class);
        GeminiCacheService geminiCacheService = mock(GeminiCacheService.class);
        PromptService promptService = mock(PromptService.class);
        GenericBuildToolService buildToolService = mock(GenericBuildToolService.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        mainFrame = new MainFrame(
            settingsService, aiService, toolService, projectService, gitService,
            envService, jexlServiceRegistry, workflowService, planService,
            turnEventBridge, packerService, cacheMetaService, geminiCacheService,
            promptService, buildToolService, chatMemory
        );
    }

    @Test
    void testWorkspaceBarsLocatedInRespectiveViews() throws Exception {
        java.lang.reflect.Field mentorLabelField = MainFrame.class.getDeclaredField("mentorModeLabel");
        mentorLabelField.setAccessible(true);
        JLabel mentorModeLabel = (JLabel) mentorLabelField.get(mainFrame);

        java.lang.reflect.Field qaTabsField = MainFrame.class.getDeclaredField("qaTabs");
        qaTabsField.setAccessible(true);
        JTabbedPane qaTabs = (JTabbedPane) qaTabsField.get(mainFrame);

        java.lang.reflect.Field progressTrackerField = MainFrame.class.getDeclaredField("progressTracker");
        progressTrackerField.setAccessible(true);
        JPanel progressTracker = (JPanel) progressTrackerField.get(mainFrame);

        java.lang.reflect.Field planLabelField = MainFrame.class.getDeclaredField("planLabel");
        planLabelField.setAccessible(true);
        JLabel planLabel = (JLabel) planLabelField.get(mainFrame);

        java.lang.reflect.Field taskTabsField = MainFrame.class.getDeclaredField("taskTabs");
        taskTabsField.setAccessible(true);
        JTabbedPane taskTabs = (JTabbedPane) taskTabsField.get(mainFrame);

        // Verify mentorModeLabel is inside the QA view wrapper (same parent container as qaTabs)
        Container qaViewPanel = qaTabs.getParent();
        assertNotNull(qaViewPanel);
        assertSame(qaViewPanel, mentorModeLabel.getParent().getParent());
        assertEquals("READ-ONLY MENTOR MODE", mentorModeLabel.getText());

        // Verify progressTracker and planLabel are inside the Task view wrapper (same parent container as taskTabs)
        Container taskViewPanel = taskTabs.getParent();
        assertNotNull(taskViewPanel);
        assertSame(taskViewPanel, progressTracker.getParent().getParent());
        assertSame(taskViewPanel, planLabel.getParent().getParent());
        assertEquals(3, progressTracker.getComponentCount(), "Progress tracker should have Plan, Code, and Review indicators");

        // Verify planLabel updates when a plan is active
        ImplementationPlan mockPlan = new ImplementationPlan(
            "My Feature Plan", "Goal", List.of(), List.of(), false, null, null, null, List.of(), List.of());
        when(planService.getCurrentPlan()).thenReturn(mockPlan);

        java.lang.reflect.Method updatePhaseMethod = MainFrame.class.getDeclaredMethod("updateActivePhase", WorkflowPhase.class);
        updatePhaseMethod.setAccessible(true);
        updatePhaseMethod.invoke(mainFrame, WorkflowPhase.CODE);

        assertTrue(planLabel.isVisible());
        assertEquals("MY FEATURE PLAN", planLabel.getText());
    }

    @Test
    void testShowCardFocusesInputAndSetsCaretToEnd() throws Exception {
        java.lang.reflect.Field qaChatField = MainFrame.class.getDeclaredField("qaChatPanel");
        qaChatField.setAccessible(true);
        ChatPanel qaChatPanel = (ChatPanel) qaChatField.get(mainFrame);

        java.lang.reflect.Field taskChatField = MainFrame.class.getDeclaredField("taskChatPanel");
        taskChatField.setAccessible(true);
        ChatPanel taskChatPanel = (ChatPanel) taskChatField.get(mainFrame);

        java.lang.reflect.Field taskTabsField = MainFrame.class.getDeclaredField("taskTabs");
        taskTabsField.setAccessible(true);
        JTabbedPane taskTabs = (JTabbedPane) taskTabsField.get(mainFrame);

        qaChatPanel.getInputArea().setText("QA draft message");
        qaChatPanel.getInputArea().setCaretPosition(0);

        taskChatPanel.getInputArea().setText("Task draft message");
        taskChatPanel.getInputArea().setCaretPosition(0);

        // Select another tab in taskTabs (e.g., Plan tab at index 1)
        taskTabs.setSelectedIndex(1);

        java.lang.reflect.Method showCardMethod = MainFrame.class.getDeclaredMethod("showCard", String.class);
        showCardMethod.setAccessible(true);

        // Switch to TASK_VIEW
        showCardMethod.invoke(mainFrame, "TASK_VIEW");
        SwingUtilities.invokeAndWait(() -> {});

        assertEquals(taskChatPanel, taskTabs.getSelectedComponent(), "Chat tab should be selected in TASK_VIEW");
        assertEquals(
            "Task draft message".length(),
            taskChatPanel.getInputArea().getCaretPosition(),
            "Task input caret should be at the end of the text");

        // Switch back to QA_VIEW
        showCardMethod.invoke(mainFrame, "QA_VIEW");
        SwingUtilities.invokeAndWait(() -> {});

        assertEquals(
            "QA draft message".length(),
            qaChatPanel.getInputArea().getCaretPosition(),
            "QA input caret should be at the end of the text");
    }
}
