package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.ai.services.cache.ProjectCacheMetaService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.PromptService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.service.SystemToolService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.ApplicationEventPublisher;

class MainFrameTest {
    @Test
    void testProjectChangedEventClearsQaAndTaskConversationsAndUpdatesTitle() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                java.awt.GraphicsEnvironment.isHeadless(),
                "Skipping JFrame test in headless environment");
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
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        RepoMapPackerService packerService = mock(RepoMapPackerService.class);
        ProjectCacheMetaService cacheMetaService = mock(ProjectCacheMetaService.class);
        PromptService promptService = mock(PromptService.class);
        GenericBuildToolService buildToolService = mock(GenericBuildToolService.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        ExploreChatPanel exploreChatPanel =
                new ExploreChatPanel(
                        aiService, planService, eventPublisher, chatMemory, settingsService, null);
        FeatureChatPanel featureChatPanel =
                new FeatureChatPanel(
                        aiService, planService, eventPublisher, chatMemory, settingsService, null);

        HeaderPanel headerPanel = new HeaderPanel(projectService, gitService, settingsService);
        StatusPanel statusPanel = new StatusPanel(envService);
        QaWorkspacePanel qaWorkspacePanel = new QaWorkspacePanel(exploreChatPanel);
        PlanPanel planPanel = new PlanPanel(planService, gitService);
        TaskWorkspacePanel taskWorkspacePanel =
                new TaskWorkspacePanel(featureChatPanel, planService, planPanel);
        CodebaseCachePanel codebaseCachePanel =
                new CodebaseCachePanel(packerService, cacheMetaService, promptService);
        JexlApiPanel jexlApiPanel = new JexlApiPanel(jexlServiceRegistry);
        SystemToolsPanel systemToolsPanel = new SystemToolsPanel(toolService);
        SettingsPanel settingsPanel = new SettingsPanel(settingsService);
        BuildConfigPanel buildConfigPanel =
                new BuildConfigPanel(buildToolService, settingsService, toolService);
        ContextManagerPanel contextManagerPanel = new ContextManagerPanel(promptService);

        MainFrame mainFrame =
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

        assertEquals(1, MainFrame.class.getDeclaredConstructors().length);
        assertEquals("RoxyCode - [OldProject]", mainFrame.getTitle());

        net.miginfocom.swing.MigLayout rootLayout =
                assertInstanceOf(
                        net.miginfocom.swing.MigLayout.class,
                        mainFrame.getContentPane().getLayout());
        assertEquals("[210!, fill]0[fill, grow]", rootLayout.getColumnConstraints());
        java.awt.Component sidebarComp = mainFrame.getContentPane().getComponent(0);
        assertInstanceOf(SidebarPanel.class, sidebarComp);
        assertEquals("grow", rootLayout.getComponentConstraints(sidebarComp));

        HeaderPanel header = mainFrame.getHeader();
        javax.swing.JPanel workspace = (javax.swing.JPanel) header.getParent();
        net.miginfocom.swing.MigLayout workspaceLayout =
                (net.miginfocom.swing.MigLayout) workspace.getLayout();
        assertEquals(
                "h 36!, wrap",
                workspaceLayout.getComponentConstraints(header),
                "Main header bar should have a compact height constraint of h 36!");

        featureChatPanel.getInputArea().setText("Task draft");
        featureChatPanel.getInputArea().setCaretPosition(0);
        taskWorkspacePanel.getTaskTabs().setSelectedIndex(1);
        mainFrame.showCard("TASK_VIEW");
        javax.swing.SwingUtilities.invokeAndWait(() -> {});
        assertEquals(featureChatPanel, taskWorkspacePanel.getTaskTabs().getSelectedComponent());
        assertEquals("Task draft".length(), featureChatPanel.getInputArea().getCaretPosition());

        exploreChatPanel.getInputArea().setText("QA draft");
        exploreChatPanel.getInputArea().setCaretPosition(0);
        mainFrame.showCard("QA_VIEW");
        javax.swing.SwingUtilities.invokeAndWait(() -> {});
        assertEquals("QA draft".length(), exploreChatPanel.getInputArea().getCaretPosition());

        // Simulate active turns and pending images on both panels before switching project
        java.util.concurrent.CountDownLatch turnStarted =
                new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch blockTurn = new java.util.concurrent.CountDownLatch(1);
        when(aiService.chat(anyString(), any(), anyString(), any()))
                .thenAnswer(
                        inv -> {
                            turnStarted.countDown();
                            blockTurn.await();
                            return "Done";
                        });

        exploreChatPanel.getController().triggerTurn("QA in progress");
        featureChatPanel.getController().triggerTurn("Task in progress");
        try {
            assertTrue(
                    turnStarted.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "Both turns should start");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted waiting for turns to start");
        }
        assertTrue(exploreChatPanel.getController().isTurnActive());
        assertTrue(featureChatPanel.getController().isTurnActive());

        exploreChatPanel
                .getModel()
                .addPendingImage(
                        new java.awt.image.BufferedImage(
                                4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB));
        featureChatPanel
                .getModel()
                .addPendingImage(
                        new java.awt.image.BufferedImage(
                                4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB));

        when(projectService.getProjectName()).thenReturn("NewProject");
        assertFalse(javax.swing.SwingUtilities.isEventDispatchThread());
        mainFrame.onProjectChanged(new ProjectChangedEvent(Path.of("/tmp/new")));
        javax.swing.SwingUtilities.invokeAndWait(() -> {});
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
            javax.swing.SwingUtilities.invokeAndWait(mainFrame::dispose);
        }
    }
}
