package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.*;
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
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.service.ProjectAnalysisService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.PromptService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.service.SystemToolService;
import org.roxycode.app.model.AppSettings;
import org.springframework.ai.chat.memory.ChatMemory;
import java.util.function.Consumer;

class MentorModeIntegrationTest {

    private SettingsService settingsService;
    private ProjectService projectService;
    private WorkflowService workflowService;
    private MainFrame mainFrame;

    @BeforeEach
    void setUp() {
        settingsService = mock(SettingsService.class);
        when(settingsService.getSettings()).thenReturn(new AppSettings());
        
        projectService = mock(ProjectService.class);
        when(projectService.getProjectName()).thenReturn("TestProject");
        
        workflowService = mock(WorkflowService.class);
        when(workflowService.getCurrentPhase()).thenReturn(WorkflowPhase.EXPLORE);
        
        AiService aiService = mock(AiService.class);
        SystemToolService toolService = mock(SystemToolService.class);
        GitService gitService = mock(GitService.class);
        EnvironmentService envService = mock(EnvironmentService.class);
        JexlServiceRegistry jexlServiceRegistry = mock(JexlServiceRegistry.class);
        PlanService planService = mock(PlanService.class);
        TurnEventBridge turnEventBridge = mock(TurnEventBridge.class);
        RepoMapPackerService packerService = mock(RepoMapPackerService.class);
        ProjectCacheMetaService cacheMetaService = mock(ProjectCacheMetaService.class);
        GeminiCacheService geminiCacheService = mock(GeminiCacheService.class);
        PromptService promptService = mock(PromptService.class);
        GenericBuildToolService buildToolService = mock(GenericBuildToolService.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        ProjectAnalysisService projectAnalysisService = mock(ProjectAnalysisService.class);

        mainFrame = new MainFrame(
            settingsService, aiService, toolService, projectService, gitService,
            envService, jexlServiceRegistry, workflowService, planService,
            turnEventBridge, packerService, cacheMetaService, geminiCacheService,
            promptService, buildToolService, chatMemory, projectAnalysisService
        );
    }

    @Test
    void testShowCardTogglesMentorMode() throws Exception {
        // Access private fields via reflection for verification
        java.lang.reflect.Field headerField = MainFrame.class.getDeclaredField("header");
        headerField.setAccessible(true);
        HeaderPanel header = (HeaderPanel) headerField.get(mainFrame);
        
        java.lang.reflect.Field mentorModeField = HeaderPanel.class.getDeclaredField("mentorMode");
        mentorModeField.setAccessible(true);

        // Initially QA_VIEW should be mentor mode
        assertTrue((Boolean) mentorModeField.get(header));

        // Switch to TASK_VIEW
        java.lang.reflect.Method showCardMethod = MainFrame.class.getDeclaredMethod("showCard", String.class);
        showCardMethod.setAccessible(true);
        showCardMethod.invoke(mainFrame, "TASK_VIEW");
        
        assertFalse((Boolean) mentorModeField.get(header));

                // Switch back to QA_VIEW
        showCardMethod.invoke(mainFrame, "QA_VIEW");
        assertTrue((Boolean) mentorModeField.get(header));
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
