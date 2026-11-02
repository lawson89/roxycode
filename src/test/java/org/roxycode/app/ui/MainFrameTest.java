package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kordamp.ikonli.codicons.Codicons;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.events.PhaseChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.events.SystemToolsUpdatedEvent;
import org.roxycode.app.events.WorkflowTransitionEvent;
import org.roxycode.app.model.ImplementationPlan;
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

    private ProjectService projectService;
    private PlanService planService;
    private TaskWorkspacePanel taskWorkspacePanel;
    private BuildConfigPanel buildConfigPanel;

    private MainFrame createMainFrame(SettingsService settingsService, CompletableFuture<List<SystemTool>> toolsFuture)
            throws Exception {
        ApplicationEventPublisher pub = mock(ApplicationEventPublisher.class);
        projectService = mock(ProjectService.class);
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.isBuildConfirmed()).thenReturn(false);
        when(projectService.getProjectName()).thenReturn("test-project");
        when(projectService.getEffectiveGeminiModel()).thenReturn("gemini-3.5-flash");

        GitService gitService = mock(GitService.class);
        when(gitService.getCurrentBranch()).thenReturn("main");

        planService = mock(PlanService.class);
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);

        AiService aiService = mock(AiService.class);
        ChatMemory chatMemory = mock(ChatMemory.class);

        HeaderPanel headerPanel = new HeaderPanel(projectService, gitService, settingsService);
        StatusPanel statusPanel = new StatusPanel(new EnvironmentService());
        QaWorkspacePanel qaWorkspacePanel = new QaWorkspacePanel(ChatPanel.createExplorePanel(aiService, planService,
                pub, chatMemory, settingsService, null, null, null), null);
        taskWorkspacePanel = new TaskWorkspacePanel(
                ChatPanel.createFeaturePanel(aiService, planService, pub, chatMemory, settingsService, null),
                planService, new PlanPanel(planService), null);
        CodebaseCachePanel codebaseCachePanel = new CodebaseCachePanel(mock(RepoMapPackerService.class),
                mock(PromptService.class));
        JexlApiPanel jexlApiPanel = new JexlApiPanel(new JexlServiceRegistry());

        SystemToolService systemToolService = mock(SystemToolService.class);
        when(systemToolService.detectTools()).thenReturn(toolsFuture);
        when(systemToolService.isDockerAvailable()).thenReturn(CompletableFuture.completedFuture(false));
        SystemToolsPanel systemToolsPanel = new SystemToolsPanel(systemToolService);

        SettingsPanel settingsPanel = new SettingsPanel(settingsService);
        GenericBuildToolService buildToolService = mock(GenericBuildToolService.class);
        when(buildToolService.getConfigContent()).thenReturn("");
        buildConfigPanel = new BuildConfigPanel(buildToolService, settingsService, projectService);
        ContextManagerPanel contextManagerPanel = new ContextManagerPanel(mock(PromptService.class));

        return new MainFrame(settingsService, projectService, chatMemory, headerPanel, statusPanel, qaWorkspacePanel,
                taskWorkspacePanel, codebaseCachePanel, jexlApiPanel, systemToolsPanel, settingsPanel, buildConfigPanel,
                contextManagerPanel);
    }

    @Test
    void testStartupWithMissingApiKeyStaysOnQaViewSetsSettingsBellAndKeepsBuildBadgeUntilConfirmed() throws Exception {
        SettingsService settingsService = new SettingsService(null, null);
        settingsService.setSettingsPathOverride(tempDir.resolve("missing-roxy").resolve("settings.toml"));

        List<SystemTool> allDetectedTools = List.of(SystemTool.ofKnownTool("Ripgrep", "14.1.0", "Detected"),
                SystemTool.ofKnownTool("Git", "2.43.0", "Detected"),
                SystemTool.ofKnownTool("Universal Ctags", "6.0.0", "Detected"));

        MainFrame frame = createMainFrame(settingsService, CompletableFuture.completedFuture(allDetectedTools));
        try {
            SwingTestUtils.flushEdt();
            assertEquals(MainFrame.QA_VIEW, frame.currentCard);
            String expectedMsg = MainFrame.buildFirstTimeSetupMessage(true, List.of());
            assertEquals(expectedMsg, frame.lastStartupAlertMessage);
            assertTrue(frame.sidebar.isSettingsAttentionNeeded());
            assertFalse(frame.sidebar.isToolsAttentionNeeded());

            NavButton settingsBtn = frame.sidebar.getCardButton(MainFrame.SETTINGS_VIEW);
            assertEquals(Codicons.BELL_DOT, settingsBtn.statusIcon.getIkon());
            assertEquals(SidebarPanel.SETTINGS_ATTENTION_TOOLTIP, settingsBtn.getToolTipText());

            settingsService.getSettings().setGeminiApiKey("   ");
            settingsService.saveSettings();
            AtomicReference<String> presentedMessage = new AtomicReference<>();
            frame.setStartupAlertPresenter(presentedMessage::set);
            frame.handleStartupSettingsCheck();
            assertEquals(expectedMsg, presentedMessage.get());

            settingsService.getSettings().setGeminiApiKey("valid-api-key");
            frame.onSettingsChanged(new SettingsChangedEvent(settingsService.getSettings()));
            SwingTestUtils.flushEdt();
            assertFalse(frame.sidebar.isSettingsAttentionNeeded());
            assertNull(settingsBtn.statusIcon);

            assertTrue(frame.sidebar.isBuildConfigAttentionNeeded());
            frame.showCard(MainFrame.BUILD_CONFIG_VIEW);
            assertTrue(frame.sidebar.isBuildConfigAttentionNeeded());

            doAnswer(inv -> {
                when(projectService.isBuildConfirmed()).thenReturn(true);
                return null;
            }).when(projectService).setBuildConfirmed(true);
            SwingTestUtils.clickOnEdt(buildConfigPanel.saveButton);
            SwingTestUtils.awaitFuture(buildConfigPanel.lastAsyncFuture);
            assertFalse(frame.sidebar.isBuildConfigAttentionNeeded());
        } finally {
            frame.dispose();
        }
    }

    @Test
    void testStartupWithMissingRequiredToolsTaskPhaseBadgeAndApprovalSync() throws Exception {
        SettingsService settingsService = new SettingsService(null, null);
        settingsService.setSettingsPathOverride(tempDir.resolve("configured-roxy").resolve("settings.toml"));
        settingsService.getSettings().setGeminiApiKey("valid-gemini-key");
        settingsService.saveSettings();

        List<SystemTool> missingCtagsTools = List.of(SystemTool.ofKnownTool("Ripgrep", "14.1.0", "Detected"),
                SystemTool.ofKnownTool("Git", "2.43.0", "Detected"),
                SystemTool.ofKnownTool("Universal Ctags", "Not found", "Missing"));

        MainFrame frame = createMainFrame(settingsService, CompletableFuture.completedFuture(missingCtagsTools));
        try {
            SwingTestUtils.flushEdt();
            assertEquals(MainFrame.buildFirstTimeSetupMessage(false, List.of("Universal Ctags")),
                    frame.lastStartupAlertMessage);
            assertTrue(frame.sidebar.isToolsAttentionNeeded());

            NavButton toolsBtn = frame.sidebar.getCardButton(MainFrame.TOOLS_VIEW);
            assertEquals(Codicons.BELL_DOT, toolsBtn.statusIcon.getIkon());

            List<SystemTool> allDetectedTools = List.of(SystemTool.ofKnownTool("Ripgrep", "14.1.0", "Detected"),
                    SystemTool.ofKnownTool("Git", "2.43.0", "Detected"),
                    SystemTool.ofKnownTool("Universal Ctags", "6.0.0", "Detected"));
            frame.onSystemToolsUpdated(new SystemToolsUpdatedEvent(allDetectedTools, false));
            SwingTestUtils.flushEdt();
            assertFalse(frame.sidebar.isToolsAttentionNeeded());

            String longTitle = "Consolidate EDT dispatch with Edt.run, clean up ProcessRunner buffers, and standardize PromptService";
            when(planService.getVisitedPhases()).thenReturn(EnumSet.of(WorkflowPhase.PLAN, WorkflowPhase.CODE));
            when(planService.getCurrentPlan()).thenReturn(new ImplementationPlan("  " + longTitle + "  ", "Goal",
                    List.of(), List.of(), false, null, null, null, null, List.of()));
            taskWorkspacePanel.updateActivePhase(WorkflowPhase.CODE);
            assertTrue(taskWorkspacePanel.planLabel.isVisible());
            assertEquals(longTitle.toUpperCase(Locale.ROOT).substring(0, 37) + "...",
                    taskWorkspacePanel.planLabel.getText());

            taskWorkspacePanel.onWorkflowTransition(
                    new WorkflowTransitionEvent(WorkflowPhase.PLAN, WorkflowPhase.CODE, "Ready", false));
            SwingTestUtils.flushEdt();
            assertTrue(frame.sidebar.isTaskWaitingApproval());

            when(planService.getPendingPhase()).thenReturn(null);
            taskWorkspacePanel.onPhaseChanged(new PhaseChangedEvent(WorkflowPhase.CODE));
            SwingTestUtils.flushEdt();
            assertFalse(frame.sidebar.isTaskWaitingApproval());
        } finally {
            frame.dispose();
        }
    }
}
