package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.model.AgentProfile;
import org.roxycode.app.model.SystemTool;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.PromptService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.service.SystemToolService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.ApplicationEventPublisher;

class MainFrameTest {

    @TempDir
    Path tempDir;

    @Test
    void testApplicationUiLaunchesAndInitializesCleanly() throws Exception {
        SettingsService settingsService = new SettingsService(null, event -> {
        });
        settingsService.setSettingsPathOverride(tempDir.resolve(".roxycode").resolve("settings.toml"));

        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.isBuildConfirmed()).thenReturn(false);
        when(projectService.getProjectName()).thenReturn("smoke-test-project");
        lenient().when(projectService.getEffectiveAgentProfile()).thenReturn(AgentProfile.NORMAL);

        GitService gitService = mock(GitService.class);
        when(gitService.getCurrentBranch()).thenReturn("main");

        PlanService planService = mock(PlanService.class);
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);

        AiService aiService = mock(AiService.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        MemoryService memoryService = mock(MemoryService.class);

        HeaderPanel headerPanel = new HeaderPanel(projectService, gitService, settingsService);
        StatusPanel statusPanel = new StatusPanel(new EnvironmentService());
        QaWorkspacePanel qaWorkspacePanel = new QaWorkspacePanel(ChatPanel.createExplorePanel(aiService, planService,
                publisher, chatMemory, settingsService, null, null, null), memoryService);
        TaskWorkspacePanel taskWorkspacePanel = new TaskWorkspacePanel(
                ChatPanel.createFeaturePanel(aiService, planService, publisher, chatMemory, settingsService, null),
                planService, new PlanPanel(planService), memoryService);
        CodebaseCachePanel codebaseCachePanel = new CodebaseCachePanel(mock(RepoMapPackerService.class),
                mock(PromptService.class), null);

        JexlServiceRegistry jexlServiceRegistry = mock(JexlServiceRegistry.class);
        when(jexlServiceRegistry.getDocumentation(null)).thenReturn("// initial doc");
        JexlApiPanel jexlApiPanel = new JexlApiPanel(jexlServiceRegistry);

        SystemToolService systemToolService = mock(SystemToolService.class);
        List<SystemTool> detectedTools = List.of(SystemTool.ofKnownTool("Ripgrep", "14.1.0", "Detected"),
                SystemTool.ofKnownTool("Git", "2.43.0", "Detected"),
                SystemTool.ofKnownTool("Universal Ctags", "6.0.0", "Detected"));
        when(systemToolService.detectTools()).thenReturn(CompletableFuture.completedFuture(detectedTools));
        when(systemToolService.isDockerAvailable()).thenReturn(CompletableFuture.completedFuture(false));
        SystemToolsPanel systemToolsPanel = new SystemToolsPanel(systemToolService);

        SettingsPanel settingsPanel = new SettingsPanel(settingsService, null);
        GenericBuildToolService buildToolService = mock(GenericBuildToolService.class);
        when(buildToolService.getConfigContent()).thenReturn("");
        when(buildToolService.buildInteractiveConfigurationPrompt(any(), anyString())).thenReturn("Interactive prompt");
        BuildConfigPanel buildConfigPanel = new BuildConfigPanel(buildToolService, settingsService, projectService);
        AgentMemoryPanel agentMemoryPanel = new AgentMemoryPanel(memoryService, projectService, settingsService);

        MainFrame frame = new MainFrame(settingsService, projectService, headerPanel, statusPanel, qaWorkspacePanel,
                taskWorkspacePanel, codebaseCachePanel, jexlApiPanel, systemToolsPanel, settingsPanel, buildConfigPanel,
                agentMemoryPanel);
        try {
            SwingTestUtils.flushEdt();
            assertNotNull(frame.sidebar);
            assertTrue(frame.getComponentCount() > 0);
            assertEquals(MainFrame.SETTINGS_VIEW, frame.currentCard);
            assertTrue(settingsPanel.getComponentCount() > 0);
            assertTrue(buildConfigPanel.getComponentCount() > 0);
            assertTrue(agentMemoryPanel.getComponentCount() > 0);
            assertTrue(qaWorkspacePanel.getComponentCount() > 0);
            assertTrue(taskWorkspacePanel.getComponentCount() > 0);
        } finally {
            frame.dispose();
        }
    }
}
