package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kordamp.ikonli.codicons.Codicons;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.events.PhaseChangedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
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
    private GitService gitService;
    private PlanService planService;
    private RepoMapPackerService repoMapPackerService;
    private JexlServiceRegistry jexlServiceRegistry;
    private JexlApiPanel jexlApiPanel;
    private SystemToolsPanel systemToolsPanel;
    private TaskWorkspacePanel taskWorkspacePanel;
    private SettingsPanel settingsPanel;
    private BuildConfigPanel buildConfigPanel;

    private MainFrame createMainFrame(SettingsService settingsService, CompletableFuture<List<SystemTool>> toolsFuture)
            throws Exception {
        ApplicationEventPublisher pub = mock(ApplicationEventPublisher.class);
        projectService = mock(ProjectService.class);
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.isBuildConfirmed()).thenReturn(false);
        when(projectService.getProjectName()).thenReturn("test-project");
        when(projectService.getEffectiveGeminiModel()).thenReturn("gemini-3.5-flash");

        gitService = mock(GitService.class);
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
        repoMapPackerService = mock(RepoMapPackerService.class);
        CodebaseCachePanel codebaseCachePanel = new CodebaseCachePanel(repoMapPackerService, mock(PromptService.class),
                null);
        jexlServiceRegistry = mock(JexlServiceRegistry.class);
        when(jexlServiceRegistry.getDocumentation(null)).thenReturn("// initial doc");
        jexlApiPanel = new JexlApiPanel(jexlServiceRegistry);

        SystemToolService systemToolService = mock(SystemToolService.class);
        when(systemToolService.detectTools()).thenReturn(toolsFuture);
        when(systemToolService.isDockerAvailable()).thenReturn(CompletableFuture.completedFuture(false));
        systemToolsPanel = new SystemToolsPanel(systemToolService);

        settingsPanel = new SettingsPanel(settingsService, null);
        GenericBuildToolService buildToolService = mock(GenericBuildToolService.class);
        when(buildToolService.getConfigContent()).thenReturn("");
        buildConfigPanel = new BuildConfigPanel(buildToolService, settingsService, projectService);
        ContextManagerPanel contextManagerPanel = new ContextManagerPanel(mock(PromptService.class), projectService);
        AgentMemoryPanel agentMemoryPanel = new AgentMemoryPanel(mock(MemoryService.class), projectService,
                settingsService);

        return new MainFrame(settingsService, projectService, headerPanel, statusPanel, qaWorkspacePanel,
                taskWorkspacePanel, codebaseCachePanel, jexlApiPanel, systemToolsPanel, settingsPanel, buildConfigPanel,
                contextManagerPanel, agentMemoryPanel);
    }

    @Test
    void testProgressiveOnboardingFlowFromSettingsToBuildConfigToQaView() throws Exception {
        SettingsService settingsService = new SettingsService(null, event -> {
        });
        settingsService.setSettingsPathOverride(tempDir.resolve("missing-roxy").resolve("settings.toml"));

        List<SystemTool> allDetectedTools = List.of(SystemTool.ofKnownTool("Ripgrep", "14.1.0", "Detected"),
                SystemTool.ofKnownTool("Git", "2.43.0", "Detected"),
                SystemTool.ofKnownTool("Universal Ctags", "6.0.0", "Detected"));

        MainFrame frame = createMainFrame(settingsService, CompletableFuture.completedFuture(allDetectedTools));
        try {
            SwingTestUtils.flushEdt();
            assertEquals(MainFrame.SETTINGS_VIEW, frame.currentCard);
            assertTrue(settingsPanel.onboardingBanner.isVisible());
            assertFalse(systemToolsPanel.warningBanner.isVisible());
            assertFalse(frame.sidebar.isToolsAttentionNeeded());

            NavButton settingsBtn = frame.sidebar.getCardButton(MainFrame.SETTINGS_VIEW);
            NavButton qaBtn = frame.sidebar.getCardButton(MainFrame.QA_VIEW);
            NavButton taskBtn = frame.sidebar.getCardButton(MainFrame.TASK_VIEW);
            NavButton cacheBtn = frame.sidebar.getCardButton(MainFrame.CACHE_VIEW);
            NavButton memoryBtn = frame.sidebar.getCardButton(MainFrame.MEMORY_VIEW);
            NavButton buildConfigBtn = frame.sidebar.getCardButton(MainFrame.BUILD_CONFIG_VIEW);
            assertTrue(settingsBtn.isEnabled());
            assertNull(settingsBtn.statusIcon);
            assertFalse(qaBtn.isEnabled());
            assertFalse(taskBtn.isEnabled());
            assertFalse(cacheBtn.isEnabled());
            assertFalse(memoryBtn.isEnabled());
            assertFalse(buildConfigBtn.isEnabled());
            assertEquals(SidebarPanel.MISSING_API_KEY_TOOLTIP, qaBtn.getToolTipText());

            SwingTestUtils.runOnEdt(() -> {
                settingsPanel.geminiApiKeyField.setText("valid-api-key");
                settingsPanel.saveButton.doClick();
            });
            SwingTestUtils.flushEdt();

            assertFalse(settingsPanel.onboardingBanner.isVisible());
            assertEquals(MainFrame.BUILD_CONFIG_VIEW, frame.currentCard);
            assertTrue(buildConfigBtn.isEnabled());
            assertTrue(memoryBtn.isEnabled());
            assertTrue(qaBtn.isEnabled());
            assertNull(qaBtn.getToolTipText());
            assertTrue(cacheBtn.isEnabled());
            assertNull(cacheBtn.getToolTipText());
            assertFalse(taskBtn.isEnabled());
            assertEquals(SidebarPanel.UNCONFIRMED_BUILD_TOOLTIP, taskBtn.getToolTipText());

            // Switching to QA_VIEW is permitted even when buildConfirmed is false; only TASK_VIEW redirects to
            // BUILD_CONFIG_VIEW
            frame.showCard(MainFrame.QA_VIEW);
            frame.updateOnboardingAndNavigationState();
            assertEquals(MainFrame.QA_VIEW, frame.currentCard);
            frame.showCard(MainFrame.TASK_VIEW);
            frame.updateOnboardingAndNavigationState();
            assertEquals(MainFrame.BUILD_CONFIG_VIEW, frame.currentCard);

            doAnswer(inv -> {
                when(projectService.isBuildConfirmed()).thenReturn(true);
                return null;
            }).when(projectService).setBuildConfirmed(true);
            SwingTestUtils.clickOnEdt(buildConfigPanel.saveButton);
            SwingTestUtils.awaitFuture(buildConfigPanel.lastAsyncFuture);

            assertEquals(MainFrame.QA_VIEW, frame.currentCard);
            assertTrue(qaBtn.isEnabled());
            assertNull(qaBtn.getToolTipText());
            assertTrue(taskBtn.isEnabled());
            assertNull(taskBtn.getToolTipText());

            frame.showCard(MainFrame.BUILD_CONFIG_VIEW);
            SwingTestUtils.clickOnEdt(buildConfigPanel.saveButton);
            SwingTestUtils.awaitFuture(buildConfigPanel.lastAsyncFuture);
            assertEquals(MainFrame.BUILD_CONFIG_VIEW, frame.currentCard,
                    "Subsequent saves on an already-confirmed project should stay on Project Settings");

            frame.showCard(MainFrame.SETTINGS_VIEW);
            SwingTestUtils.clickOnEdt(settingsPanel.saveButton);
            SwingTestUtils.flushEdt();
            assertEquals(MainFrame.SETTINGS_VIEW, frame.currentCard,
                    "Subsequent saves when API key was already configured should stay on Global Settings");

            when(projectService.hasActiveProject()).thenReturn(false);
            frame.showCard(MainFrame.MEMORY_VIEW);
            frame.onProjectChanged(new ProjectChangedEvent(null));
            SwingTestUtils.flushEdt();
            assertEquals(MainFrame.MEMORY_VIEW, frame.currentCard,
                    "Agent Memory view should remain accessible even without an active project");
            assertTrue(memoryBtn.isEnabled());

            frame.showCard(MainFrame.QA_VIEW);
            frame.onProjectChanged(new ProjectChangedEvent(null));
            SwingTestUtils.flushEdt();
            assertEquals(MainFrame.SETTINGS_VIEW, frame.currentCard);
            assertFalse(qaBtn.isEnabled());
            assertFalse(buildConfigBtn.isEnabled());
            assertEquals(SidebarPanel.MISSING_PROJECT_TOOLTIP, qaBtn.getToolTipText());
        } finally {
            frame.dispose();
        }
    }

    @Test
    void testStartupWithMissingRequiredToolsTaskPhaseBadgeAndApprovalSync() throws Exception {
        SettingsService settingsService = new SettingsService(null, event -> {
        });
        settingsService.setSettingsPathOverride(tempDir.resolve("configured-roxy").resolve("settings.toml"));
        settingsService.getSettings().setGeminiApiKey("valid-gemini-key");
        settingsService.saveSettings();

        List<SystemTool> missingCtagsTools = List.of(SystemTool.ofKnownTool("Ripgrep", "14.1.0", "Detected"),
                SystemTool.ofKnownTool("Git", "2.43.0", "Detected"),
                SystemTool.ofKnownTool("Universal Ctags", "Not found", "Missing"));

        MainFrame frame = createMainFrame(settingsService, CompletableFuture.completedFuture(missingCtagsTools));
        try {
            SwingTestUtils.flushEdt();
            assertEquals(MainFrame.TOOLS_VIEW, frame.currentCard);
            assertEquals(1, frame.settingsHubTabs.getSelectedIndex());
            assertTrue(systemToolsPanel.warningBanner.isVisible());
            assertTrue(systemToolsPanel.warningBanner.messageLabel.getText().contains("Universal Ctags"));
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
            when(planService.getCurrentPlan())
                    .thenReturn(new ImplementationPlan("  " + longTitle + "  ", "Goal", List.of(),
                            List.of(new ImplementationPlan.TechStep("Step 1", true),
                                    new ImplementationPlan.TechStep("Step 2", false)),
                            false, null, null, null, null, List.of()));
            taskWorkspacePanel.updateActivePhase(WorkflowPhase.CODE);
            assertTrue(taskWorkspacePanel.planLabel.isVisible());
            assertEquals(longTitle.substring(0, 37) + "...", taskWorkspacePanel.planLabel.getText());
            assertEquals(longTitle, taskWorkspacePanel.planLabel.getToolTipText());
            assertEquals("Plan (1/2)", taskWorkspacePanel.taskTabs.getTitleAt(1));

            frame.onWorkflowTransition(
                    new WorkflowTransitionEvent(WorkflowPhase.PLAN, WorkflowPhase.CODE, "Ready", false));
            SwingTestUtils.flushEdt();
            assertTrue(frame.sidebar.isTaskWaitingApproval());

            when(planService.getPendingPhase()).thenReturn(null);
            frame.onPhaseChanged(new PhaseChangedEvent(WorkflowPhase.CODE));
            SwingTestUtils.flushEdt();
            assertFalse(frame.sidebar.isTaskWaitingApproval());

            frame.onWorkflowTransition(
                    new WorkflowTransitionEvent(WorkflowPhase.REVIEW, WorkflowPhase.PLAN, "Finished", false));
            SwingTestUtils.flushEdt();
            assertTrue(frame.sidebar.isTaskWaitingApproval());

            frame.onPhaseChanged(new PhaseChangedEvent(WorkflowPhase.PLAN));
            SwingTestUtils.flushEdt();
            assertFalse(frame.sidebar.isTaskWaitingApproval());

            clearInvocations(repoMapPackerService);
            frame.showCard(MainFrame.CACHE_VIEW);
            assertEquals(1, frame.contextHubTabs.getSelectedIndex());
            verify(repoMapPackerService, timeout(2000).atLeastOnce()).getCachedSnapshot();
            frame.showCard(MainFrame.API_VIEW);
            assertEquals(2, frame.contextHubTabs.getSelectedIndex());

            when(jexlServiceRegistry.getDocumentation(null)).thenReturn("// updated git doc");
            jexlApiPanel.onSystemToolsUpdated(new SystemToolsUpdatedEvent(allDetectedTools, false));
            SwingTestUtils.flushEdt();
            assertTrue(jexlApiPanel.textPane.getText().contains("updated git doc"));
        } finally {
            frame.dispose();
        }
    }

    @Test
    void testDynamicWindowTitlesAndStatusLifecycle() throws Exception {
        SettingsService settingsService = new SettingsService(null, event -> {
        });
        settingsService.setSettingsPathOverride(tempDir.resolve("configured-roxy-status").resolve("settings.toml"));
        settingsService.getSettings().setGeminiApiKey("valid-gemini-key");
        settingsService.saveSettings();

        List<SystemTool> allDetectedTools = List.of(SystemTool.ofKnownTool("Ripgrep", "14.1.0", "Detected"),
                SystemTool.ofKnownTool("Git", "2.43.0", "Detected"),
                SystemTool.ofKnownTool("Universal Ctags", "6.0.0", "Detected"));

        MainFrame frame = createMainFrame(settingsService, CompletableFuture.completedFuture(allDetectedTools));
        try {
            SwingTestUtils.flushEdt();

            // Default IDLE state
            assertEquals(AppIconRenderer.AgentStatus.IDLE, frame.resolveCurrentStatus());
            assertEquals("test-project — RoxyCode", frame.getTitle());

            // Test BUSY state in QA mode
            frame.showCard(MainFrame.QA_VIEW);
            SwingTestUtils.runOnEdt(() -> frame.qaChatPanel().setLoading(true));
            assertTrue(frame.sidebar.isQaBusy());
            assertFalse(frame.sidebar.isTaskBusy());
            assertEquals(AppIconRenderer.AgentStatus.BUSY, frame.resolveCurrentStatus());
            frame.updateWorkspaceState();
            assertEquals(AppIconRenderer.AgentStatus.BUSY, frame.currentStatus);
            assertEquals("test-project — RoxyCode", frame.getTitle());

            // Test BUSY state in Task mode with plan phase
            SwingTestUtils.runOnEdt(() -> {
                frame.qaChatPanel().setLoading(false);
                frame.showCard(MainFrame.TASK_VIEW);
                frame.taskChatPanel().setLoading(true);
            });
            assertFalse(frame.sidebar.isQaBusy());
            assertTrue(frame.sidebar.isTaskBusy());
            assertEquals(AppIconRenderer.AgentStatus.BUSY, frame.resolveCurrentStatus());
            frame.updateWorkspaceState();
            assertEquals(AppIconRenderer.AgentStatus.BUSY, frame.currentStatus);
            assertEquals("test-project — RoxyCode", frame.getTitle());

            // Test WAITING_FEEDBACK state (takes precedence over BUSY)
            frame.sidebar.setTaskWaitingApproval(true);
            assertEquals(AppIconRenderer.AgentStatus.WAITING_FEEDBACK, frame.resolveCurrentStatus());
            frame.updateWorkspaceState();
            assertEquals(AppIconRenderer.AgentStatus.WAITING_FEEDBACK, frame.currentStatus);
            assertEquals("test-project — RoxyCode", frame.getTitle());

            // Test returning to IDLE
            frame.sidebar.setTaskWaitingApproval(false);
            SwingTestUtils.runOnEdt(() -> frame.taskChatPanel().setLoading(false));
            assertFalse(frame.sidebar.isTaskBusy());
            assertEquals(AppIconRenderer.AgentStatus.IDLE, frame.resolveCurrentStatus());
            frame.updateWorkspaceState();
            assertEquals(AppIconRenderer.AgentStatus.IDLE, frame.currentStatus);
            assertEquals("test-project — RoxyCode", frame.getTitle());

            // Test with no active project and window focus branch refresh
            clearInvocations(gitService);
            frame.onWindowGainedFocus();
            verify(gitService, timeout(2000).atLeastOnce()).getCurrentBranch();
            when(projectService.hasActiveProject()).thenReturn(false);
            frame.updateWorkspaceState();
            assertEquals("RoxyCode", frame.getTitle());
        } finally {
            frame.dispose();
        }
    }

    @Test
    void testWindowBoundsClampingMultiMonitorValidationAndPersistenceOnClose() throws Exception {
        java.awt.Rectangle screenBounds = new java.awt.Rectangle(0, 0, 1920, 1080);
        java.awt.Rectangle secondMonitor = new java.awt.Rectangle(1920, 0, 2560, 1440);
        List<java.awt.Rectangle> monitors = List.of(screenBounds, secondMonitor);

        // Size clamping: min 800x600 and max screen bounds
        assertEquals(new java.awt.Dimension(800, 600), MainFrame.clampWindowSize(400, 300, screenBounds));
        assertEquals(new java.awt.Dimension(1920, 1080), MainFrame.clampWindowSize(3000, 2000, screenBounds));
        assertEquals(new java.awt.Dimension(1200, 900), MainFrame.clampWindowSize(0, -1, screenBounds));
        assertEquals(new java.awt.Dimension(1400, 950), MainFrame.clampWindowSize(1400, 950, null));

        // Multi-monitor visibility validation
        assertTrue(MainFrame.isWindowBoundsVisibleOnScreens(new java.awt.Rectangle(100, 100, 1200, 900), monitors));
        assertTrue(MainFrame.isWindowBoundsVisibleOnScreens(new java.awt.Rectangle(2100, 120, 1200, 900), monitors));
        assertFalse(MainFrame.isWindowBoundsVisibleOnScreens(new java.awt.Rectangle(-2000, 100, 1200, 900), monitors));
        assertFalse(MainFrame.isWindowBoundsVisibleOnScreens(new java.awt.Rectangle(4450, 100, 1200, 900), monitors),
                "Window with less than 100px visible on any monitor should be rejected");
        assertFalse(MainFrame.isWindowBoundsVisibleOnScreens(null, monitors));
        assertFalse(MainFrame.isWindowBoundsVisibleOnScreens(new java.awt.Rectangle(0, 0, 1200, 900), List.of()));

        // Persistence on windowClosing & preserving un-maximized bounds when closed while maximized
        Path settingsFile = tempDir.resolve("window-bounds-roxy").resolve("settings.toml");
        SettingsService settingsService = new SettingsService(null, event -> {
        });
        settingsService.setSettingsPathOverride(settingsFile);
        settingsService.getSettings().setGeminiApiKey("valid-gemini-key");
        settingsService.getSettings().setWindowX(150);
        settingsService.getSettings().setWindowY(90);
        settingsService.getSettings().setWindowWidth(1360);
        settingsService.getSettings().setWindowHeight(860);
        settingsService.getSettings().setWindowMaximized(false);
        settingsService.saveSettingsSync();

        List<SystemTool> allDetectedTools = List.of(SystemTool.ofKnownTool("Ripgrep", "14.1.0", "Detected"),
                SystemTool.ofKnownTool("Git", "2.43.0", "Detected"),
                SystemTool.ofKnownTool("Universal Ctags", "6.0.0", "Detected"));

        MainFrame frame = createMainFrame(settingsService, CompletableFuture.completedFuture(allDetectedTools));
        try {
            SwingTestUtils.flushEdt();
            assertEquals(1360, frame.getWidth());
            assertEquals(860, frame.getHeight());
            assertNotNull(frame.lastNormalBounds);

            // Simulate user moving/resizing the normal window
            frame.lastNormalBounds = new java.awt.Rectangle(240, 160, 1420, 880);

            // Simulate user maximizing the window and resizing to full monitor dimensions
            frame.simulatedExtendedState = java.awt.Frame.MAXIMIZED_BOTH;
            frame.recordNormalWindowBounds();
            assertEquals(new java.awt.Rectangle(240, 160, 1420, 880), frame.lastNormalBounds,
                    "Maximized resize must not overwrite lastNormalBounds");

            // Simulate closing the window while maximized
            frame.saveWindowStateOnClose();

            org.roxycode.app.model.AppSettings reloaded = SettingsService.loadFromDisk(settingsFile,
                    org.roxycode.app.ai.AiConfig.SHARED_TOML_MAPPER);
            assertEquals(240, reloaded.getWindowX());
            assertEquals(160, reloaded.getWindowY());
            assertEquals(1420, reloaded.getWindowWidth());
            assertEquals(880, reloaded.getWindowHeight());
            assertTrue(reloaded.isWindowMaximized());
        } finally {
            frame.dispose();
        }
    }
}
