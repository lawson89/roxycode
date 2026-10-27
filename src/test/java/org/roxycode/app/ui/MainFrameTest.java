package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.miginfocom.swing.MigLayout;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.*;
import org.roxycode.app.ai.services.cache.*;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.*;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.ApplicationEventPublisher;

class MainFrameTest {
    @Test
    void testProjectChangedEventClearsQaAndTaskConversationsAndUpdatesTitle() throws Exception {
        Assumptions.assumeFalse(
                GraphicsEnvironment.isHeadless(), "Skipping JFrame test in headless environment");
        SettingsService settingsService = mock(SettingsService.class);
        when(settingsService.getSettings()).thenReturn(new AppSettings());
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.getProjectName()).thenReturn("OldProject");

        PlanService planService = mock(PlanService.class);
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);

        AiService aiService = mock(AiService.class);
        SystemToolService toolService = mock(SystemToolService.class);
        when(toolService.detectTools()).thenReturn(CompletableFuture.completedFuture(List.of()));
        GitService gitService = mock(GitService.class);
        EnvironmentService envService = mock(EnvironmentService.class);
        JexlServiceRegistry jexlServiceRegistry = mock(JexlServiceRegistry.class);
        when(jexlServiceRegistry.getDocumentation(any())).thenReturn("");
        ApplicationEventPublisher pub = mock(ApplicationEventPublisher.class);
        PromptService promptService = mock(PromptService.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        ExploreChatPanel exploreChatPanel =
                new ExploreChatPanel(
                        aiService,
                        planService,
                        pub,
                        chatMemory,
                        settingsService,
                        null,
                        null,
                        envService);
        FeatureChatPanel featureChatPanel =
                new FeatureChatPanel(
                        aiService, planService, pub, chatMemory, settingsService, null);

        HeaderPanel headerPanel = new HeaderPanel(projectService, gitService, settingsService);
        QaWorkspacePanel qaWorkspacePanel = new QaWorkspacePanel(exploreChatPanel, null);
        TaskWorkspacePanel taskWorkspacePanel =
                new TaskWorkspacePanel(
                        featureChatPanel,
                        planService,
                        new PlanPanel(planService, gitService),
                        null);

        MainFrame mainFrame =
                new MainFrame(
                        settingsService,
                        projectService,
                        chatMemory,
                        headerPanel,
                        new StatusPanel(envService),
                        qaWorkspacePanel,
                        taskWorkspacePanel,
                        new CodebaseCachePanel(
                                mock(RepoMapPackerService.class),
                                mock(ProjectCacheMetaService.class),
                                promptService),
                        new JexlApiPanel(jexlServiceRegistry),
                        new SystemToolsPanel(toolService),
                        new SettingsPanel(settingsService),
                        new BuildConfigPanel(
                                mock(GenericBuildToolService.class), settingsService, toolService),
                        new ContextManagerPanel(promptService));

        assertEquals(1, MainFrame.class.getDeclaredConstructors().length);
        assertEquals("RoxyCode - [OldProject]", mainFrame.getTitle());

        MigLayout rootLayout =
                assertInstanceOf(MigLayout.class, mainFrame.getContentPane().getLayout());
        assertEquals("[210!, fill]0[fill, grow]", rootLayout.getColumnConstraints());
        Component sidebarComp = mainFrame.getContentPane().getComponent(0);
        assertInstanceOf(SidebarPanel.class, sidebarComp);
        assertEquals("grow", rootLayout.getComponentConstraints(sidebarComp));

        HeaderPanel header = mainFrame.getHeader();
        MigLayout workspaceLayout = (MigLayout) ((JPanel) header.getParent()).getLayout();
        assertEquals("h 36!, wrap", workspaceLayout.getComponentConstraints(header));

        featureChatPanel.getInputArea().setText("Task draft");
        featureChatPanel.getInputArea().setCaretPosition(0);
        taskWorkspacePanel.getTaskTabs().setSelectedIndex(1);
        mainFrame.showCard("TASK_VIEW");
        SwingTestUtils.flushEdt();
        assertEquals(featureChatPanel, taskWorkspacePanel.getTaskTabs().getSelectedComponent());
        assertEquals("Task draft".length(), featureChatPanel.getInputArea().getCaretPosition());

        exploreChatPanel.getInputArea().setText("QA draft");
        exploreChatPanel.getInputArea().setCaretPosition(0);
        mainFrame.showCard("QA_VIEW");
        SwingTestUtils.flushEdt();
        assertEquals("QA draft".length(), exploreChatPanel.getInputArea().getCaretPosition());

        CountDownLatch turnStarted = new CountDownLatch(2);
        CountDownLatch blockTurn = new CountDownLatch(1);
        when(aiService.chat(anyString(), any(), anyString(), any()))
                .thenAnswer(
                        inv -> {
                            turnStarted.countDown();
                            blockTurn.await();
                            return "Done";
                        });

        exploreChatPanel.getController().triggerTurn("QA in progress");
        featureChatPanel.getController().triggerTurn("Task in progress");
        assertTrue(turnStarted.await(5, TimeUnit.SECONDS));
        assertTrue(exploreChatPanel.getController().isTurnActive());
        assertTrue(featureChatPanel.getController().isTurnActive());

        exploreChatPanel
                .getModel()
                .addPendingImage(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB));
        featureChatPanel
                .getModel()
                .addPendingImage(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB));

        when(projectService.getProjectName()).thenReturn("NewProject");
        assertFalse(SwingUtilities.isEventDispatchThread());
        mainFrame.onProjectChanged(new ProjectChangedEvent(Path.of("/tmp/new")));
        SwingTestUtils.flushEdt();
        blockTurn.countDown();

        try {
            assertEquals("RoxyCode - [NewProject]", mainFrame.getTitle());
            assertFalse(exploreChatPanel.getController().isTurnActive());
            assertFalse(featureChatPanel.getController().isTurnActive());
            assertFalse(exploreChatPanel.getModel().hasPendingImages());
            assertFalse(featureChatPanel.getModel().hasPendingImages());
            verify(planService).resetWorkflow();
            verify(chatMemory).clear(ChatModel.QA_CONVERSATION_ID);
            verify(chatMemory).clear(ChatModel.TASK_CONVERSATION_ID);
        } finally {
            SwingTestUtils.runOnEdt(mainFrame::dispose);
        }
    }
}
