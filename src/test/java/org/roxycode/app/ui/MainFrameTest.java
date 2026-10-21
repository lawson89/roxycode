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
    void testProjectChangedEventClearsQaAndTaskConversationsAndUpdatesTitle() {
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
                        aiService, planService, eventPublisher, chatMemory, settingsService);
        ExploreHistoryPanel exploreHistoryPanel = new ExploreHistoryPanel(chatMemory);
        FeatureChatPanel featureChatPanel =
                new FeatureChatPanel(
                        aiService, planService, eventPublisher, chatMemory, settingsService);
        FeatureHistoryPanel featureHistoryPanel = new FeatureHistoryPanel(chatMemory);

        HeaderPanel headerPanel = new HeaderPanel(projectService, gitService, settingsService);
        StatusPanel statusPanel = new StatusPanel(envService);
        QaWorkspacePanel qaWorkspacePanel =
                new QaWorkspacePanel(exploreChatPanel, exploreHistoryPanel);
        PlanPanel planPanel = new PlanPanel(planService);
        GitChangesPanel gitChangesPanel = new GitChangesPanel(gitService);
        TaskWorkspacePanel taskWorkspacePanel =
                new TaskWorkspacePanel(
                        featureChatPanel,
                        featureHistoryPanel,
                        planService,
                        planPanel,
                        gitChangesPanel);
        CodebaseCachePanel codebaseCachePanel =
                new CodebaseCachePanel(packerService, cacheMetaService, promptService);
        JexlApiPanel jexlApiPanel = new JexlApiPanel(jexlServiceRegistry);
        SystemToolsPanel systemToolsPanel = new SystemToolsPanel(toolService);
        SettingsPanel settingsPanel = new SettingsPanel(settingsService);
        BuildConfigPanel buildConfigPanel = new BuildConfigPanel(buildToolService, settingsService);
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

        when(projectService.getProjectName()).thenReturn("NewProject");
        mainFrame.onProjectChanged(new ProjectChangedEvent(Path.of("/tmp/new")));

        assertEquals("RoxyCode - [NewProject]", mainFrame.getTitle());
        verify(chatMemory).clear(ChatModel.QA_CONVERSATION_ID);
        verify(chatMemory).clear(ChatModel.TASK_CONVERSATION_ID);
    }
}
