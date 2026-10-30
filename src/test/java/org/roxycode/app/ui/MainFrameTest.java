package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
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
import org.roxycode.app.ai.services.cache.ProjectCacheMetaService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.events.SystemToolsUpdatedEvent;
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

    private MainFrame createMainFrame(SettingsService settingsService, CompletableFuture<List<SystemTool>> toolsFuture)
            throws Exception {
        ApplicationEventPublisher pub = mock(ApplicationEventPublisher.class);
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.getProjectName()).thenReturn("test-project");
        when(projectService.getEffectiveGeminiModel()).thenReturn("gemini-3.5-flash");

        GitService gitService = mock(GitService.class);
        when(gitService.getCurrentBranch()).thenReturn("main");

        PlanService planService = mock(PlanService.class);
        when(planService.getCurrentPhase()).thenReturn(WorkflowPhase.PLAN);

        AiService aiService = mock(AiService.class);
        ChatMemory chatMemory = mock(ChatMemory.class);
        EnvironmentService envService = new EnvironmentService();

        HeaderPanel headerPanel = new HeaderPanel(projectService, gitService, settingsService);
        StatusPanel statusPanel = new StatusPanel(envService);
        QaWorkspacePanel qaWorkspacePanel = new QaWorkspacePanel(
                new ExploreChatPanel(aiService, planService, pub, chatMemory, settingsService, null, null, null), null);
        TaskWorkspacePanel taskWorkspacePanel = new TaskWorkspacePanel(
                new FeatureChatPanel(aiService, planService, pub, chatMemory, settingsService, null), planService,
                new PlanPanel(planService, gitService), null);
        CodebaseCachePanel codebaseCachePanel = new CodebaseCachePanel(mock(RepoMapPackerService.class),
                mock(ProjectCacheMetaService.class), mock(PromptService.class));
        JexlApiPanel jexlApiPanel = new JexlApiPanel(new JexlServiceRegistry());

        SystemToolService systemToolService = mock(SystemToolService.class);
        when(systemToolService.detectTools()).thenReturn(toolsFuture);
        when(systemToolService.isDockerAvailable()).thenReturn(CompletableFuture.completedFuture(false));
        SystemToolsPanel systemToolsPanel = new SystemToolsPanel(systemToolService);

        SettingsPanel settingsPanel = new SettingsPanel(settingsService);
        GenericBuildToolService buildToolService = mock(GenericBuildToolService.class);
        when(buildToolService.getConfigContent()).thenReturn("");
        BuildConfigPanel buildConfigPanel = new BuildConfigPanel(buildToolService, settingsService, systemToolService,
                projectService);
        ContextManagerPanel contextManagerPanel = new ContextManagerPanel(mock(PromptService.class));

        return new MainFrame(settingsService, projectService, chatMemory, headerPanel, statusPanel, qaWorkspacePanel,
                taskWorkspacePanel, codebaseCachePanel, jexlApiPanel, systemToolsPanel, settingsPanel, buildConfigPanel,
                contextManagerPanel);
    }

    @Test
    void testStartupWithMissingApiKeyStaysOnQaViewSetsSettingsBellAndClearsOnEvent() throws Exception {
        SettingsService settingsService = new SettingsService(null, null);
        Path missingSettingsFile = tempDir.resolve("missing-roxy").resolve("settings.toml");
        settingsService.setSettingsPathOverride(missingSettingsFile);

        List<SystemTool> allDetectedTools = List.of(SystemTool.ofKnownTool("Ripgrep", "14.1.0", "Detected"),
                SystemTool.ofKnownTool("Git", "2.43.0", "Detected"),
                SystemTool.ofKnownTool("Universal Ctags", "6.0.0", "Detected"));

        MainFrame frame = createMainFrame(settingsService, CompletableFuture.completedFuture(allDetectedTools));
        try {
            SwingTestUtils.flushEdt();
            assertEquals(MainFrame.QA_VIEW, frame.getCurrentCard());
            assertEquals(MainFrame.MISSING_API_KEY_MESSAGE, frame.getLastMissingApiKeyMessage());
            assertTrue(frame.getSidebar().isSettingsAttentionNeeded());
            assertFalse(frame.getSidebar().isToolsAttentionNeeded());

            NavButton settingsBtn = frame.getSidebar().getCardButton(MainFrame.SETTINGS_VIEW);
            assertNotNull(settingsBtn);
            assertEquals(Codicons.BELL_DOT, settingsBtn.getStatusIcon().getIkon());
            assertEquals(SidebarPanel.SETTINGS_ATTENTION_TOOLTIP, settingsBtn.getToolTipText());

            // Verify custom presenter hook with blank key in existing settings file
            settingsService.getSettings().setGeminiApiKey("   ");
            settingsService.saveSettings();
            assertTrue(settingsService.hasSettingsFile());
            assertFalse(settingsService.hasConfiguredApiKey());

            AtomicReference<String> presentedMessage = new AtomicReference<>();
            frame.setMissingApiKeyDialogPresenter(presentedMessage::set);
            frame.handleStartupSettingsCheck();

            assertEquals(MainFrame.QA_VIEW, frame.getCurrentCard());
            assertEquals(MainFrame.MISSING_API_KEY_MESSAGE, presentedMessage.get());
            assertTrue(frame.getSidebar().isSettingsAttentionNeeded());

            // Saving a valid API key and firing SettingsChangedEvent clears the bell indicator
            settingsService.getSettings().setGeminiApiKey("valid-api-key");
            frame.onSettingsChanged(new SettingsChangedEvent(settingsService.getSettings()));
            SwingTestUtils.flushEdt();

            assertFalse(frame.getSidebar().isSettingsAttentionNeeded());
            assertNull(settingsBtn.getStatusIcon());
            assertNull(settingsBtn.getToolTipText());
        } finally {
            frame.dispose();
        }
    }

    @Test
    void testStartupWithMissingRequiredToolsStaysOnQaViewSetsToolsBellAndClearsOnEvent() throws Exception {
        SettingsService settingsService = new SettingsService(null, null);
        Path configuredSettingsFile = tempDir.resolve("configured-roxy").resolve("settings.toml");
        settingsService.setSettingsPathOverride(configuredSettingsFile);
        settingsService.getSettings().setGeminiApiKey("valid-gemini-key");
        settingsService.saveSettings();

        List<SystemTool> missingCtagsTools = List.of(SystemTool.ofKnownTool("Ripgrep", "14.1.0", "Detected"),
                SystemTool.ofKnownTool("Git", "2.43.0", "Detected"),
                SystemTool.ofKnownTool("Universal Ctags", "Not found", "Missing"));

        MainFrame frame = createMainFrame(settingsService, CompletableFuture.completedFuture(missingCtagsTools));
        try {
            SwingTestUtils.flushEdt();
            assertEquals(MainFrame.QA_VIEW, frame.getCurrentCard());
            assertNull(frame.getLastMissingApiKeyMessage());
            assertEquals(MainFrame.MISSING_REQUIRED_TOOLS_MESSAGE, frame.getLastMissingToolsMessage());
            assertFalse(frame.getSidebar().isSettingsAttentionNeeded());
            assertTrue(frame.getSidebar().isToolsAttentionNeeded());

            NavButton toolsBtn = frame.getSidebar().getCardButton(MainFrame.TOOLS_VIEW);
            assertNotNull(toolsBtn);
            assertEquals(Codicons.BELL_DOT, toolsBtn.getStatusIcon().getIkon());
            assertEquals(SidebarPanel.TOOLS_ATTENTION_TOOLTIP, toolsBtn.getToolTipText());

            // Verify custom presenter hook for missing tools
            AtomicReference<String> presentedToolsMsg = new AtomicReference<>();
            frame.setMissingToolsDialogPresenter(presentedToolsMsg::set);
            frame.handleStartupToolsCheck(missingCtagsTools);
            assertEquals(MainFrame.QA_VIEW, frame.getCurrentCard());
            assertEquals(MainFrame.MISSING_REQUIRED_TOOLS_MESSAGE, presentedToolsMsg.get());

            // Firing SystemToolsUpdatedEvent with all required tools detected clears the bell indicator
            List<SystemTool> allDetectedTools = List.of(SystemTool.ofKnownTool("Ripgrep", "14.1.0", "Detected"),
                    SystemTool.ofKnownTool("Git", "2.43.0", "Detected"),
                    SystemTool.ofKnownTool("Universal Ctags", "6.0.0", "Detected"));
            frame.onSystemToolsUpdated(new SystemToolsUpdatedEvent(allDetectedTools, false));
            SwingTestUtils.flushEdt();

            assertFalse(frame.getSidebar().isToolsAttentionNeeded());
            assertNull(toolsBtn.getStatusIcon());
            assertNull(toolsBtn.getToolTipText());
        } finally {
            frame.dispose();
        }
    }
}
