package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.formdev.flatlaf.FlatClientProperties;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.GeminiModelConfig;
import org.roxycode.app.model.ThinkingLevel;
import org.roxycode.app.model.ThinkingLevelOption;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;

class BuildConfigPanelTest {

    private GenericBuildToolService buildToolService;
    private SettingsService settingsService;
    private ProjectService projectService;

    @BeforeEach
    void setUp() throws Exception {
        buildToolService = mock(GenericBuildToolService.class);
        settingsService = mock(SettingsService.class);
        projectService = mock(ProjectService.class);

        when(settingsService.getSettings()).thenReturn(new AppSettings());
        when(settingsService.getAvailableModels())
                .thenReturn(List.of(new GeminiModelConfig("gemini-3.5-flash", "Gemini 3.5 Flash", "Fast default model"),
                        new GeminiModelConfig("gemini-3.5-pro", "Gemini 3.5 Pro", "Advanced reasoning model")));
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getProjectGeminiModel()).thenReturn("");
        when(projectService.getProjectThinkingLevel()).thenReturn("");
        when(buildToolService.getConfigContent()).thenReturn("""
                [commands]
                compile = './mvnw test-compile'

                [sandbox]
                mode = "none"
                writable_paths = ["~/.m2"]
                image = "eclipse-temurin:25-jdk"
                workdir = "/workspace"
                """);
    }

    private BuildConfigPanel createPanelOnEdt() {
        BuildConfigPanel panel = SwingTestUtils
                .callOnEdt(() -> new BuildConfigPanel(buildToolService, settingsService, projectService));
        SwingTestUtils.awaitFuture(panel.lastAsyncFuture);
        return panel;
    }

    @Test
    void testLoadAndSaveConfigRunOffEdtAndSaveProjectModelOverride() throws Exception {
        AtomicBoolean loadCalledOnEdt = new AtomicBoolean(false);
        AtomicBoolean saveCalledOnEdt = new AtomicBoolean(false);
        AtomicBoolean modelSaveCalledOnEdt = new AtomicBoolean(false);
        when(buildToolService.getConfigContent()).thenAnswer(inv -> {
            loadCalledOnEdt.set(SwingUtilities.isEventDispatchThread());
            return """
                    [commands]
                    compile = './mvnw test-compile'
                    """;
        });
        doAnswer(inv -> {
            saveCalledOnEdt.set(SwingUtilities.isEventDispatchThread());
            return null;
        }).when(buildToolService).saveConfigContent(anyString());
        doAnswer(inv -> {
            modelSaveCalledOnEdt.set(SwingUtilities.isEventDispatchThread());
            return null;
        }).when(projectService).setProjectGeminiModel(anyString());
        doAnswer(inv -> {
            when(projectService.isBuildConfirmed()).thenReturn(inv.getArgument(0, Boolean.class));
            return null;
        }).when(projectService).setBuildConfirmed(anyBoolean());

        BuildConfigPanel panel = createPanelOnEdt();
        assertFalse(loadCalledOnEdt.get());
        assertEquals(BuildConfigPanel.TITLE_TEXT, panel.titleLabel.getText());
        assertEquals(UiComponents.formatWrappingHtml(BuildConfigPanel.SUBTITLE_TEXT), panel.subtitleLabel.getText());
        assertEquals(BuildConfigPanel.BUILD_CONFIG_HINT_TEXT, panel.buildConfigHintLabel.getText());
        assertSame(panel.buildSectionHeaderLabel.getParent(), panel.buildConfigHintLabel.getParent());
        assertSame(panel.buildSectionHeaderLabel.getParent(), panel.configureWithRoxyButton.getParent());
        assertNotNull(panel.buildCardPanel);
        assertSame(panel.saveButton.getParent(), panel.statusLabel.getParent());
        assertEquals(BuildConfigPanel.SAVE_BUTTON_TEXT, panel.saveButton.getText());
        assertTrue(panel.textArea.getText().contains("./mvnw test-compile"));
        assertTrue(panel.unconfirmedBanner.isVisible());
        assertEquals(UiComponents.formatWrappingHtml(BuildConfigPanel.UNCONFIRMED_BUILD_NOTICE_TEXT),
                panel.unconfirmedBanner.messageLabel.getText());

        JComboBox<GeminiModelConfig> modelCombo = panel.modelCombo;
        assertEquals(3, modelCombo.getItemCount());
        assertEquals("", modelCombo.getItemAt(0).apiName());
        assertEquals("gemini-3.5-flash", panel.modelApiBadgeLabel.getText());

        JComboBox<ThinkingLevelOption> thinkingCombo = panel.thinkingLevelCombo;
        assertEquals(1 + ThinkingLevel.values().length, thinkingCombo.getItemCount());
        assertEquals("", thinkingCombo.getItemAt(0).id());
        assertEquals("Use Global Default (Model Default)", thinkingCombo.getItemAt(0).displayName());

        java.util.concurrent.atomic.AtomicInteger confirmCallbackCount = new java.util.concurrent.atomic.AtomicInteger();
        panel.setOnConfirmSavedCallback(confirmCallbackCount::incrementAndGet);

        String updatedToml = """
                [commands]
                compile = 'mvn test'
                """;
        SwingTestUtils.runOnEdt(() -> {
            modelCombo.setSelectedIndex(2);
            UiComponents.selectThinkingLevelOptionById(thinkingCombo, "HIGH");
            assertEquals("gemini-3.5-pro", panel.modelApiBadgeLabel.getText());
            assertEquals("Advanced reasoning model", panel.modelDescriptionArea.getText());
            panel.textArea.setText(updatedToml);
            panel.saveButton.doClick();
        });
        SwingTestUtils.awaitFuture(panel.lastAsyncFuture);

        assertFalse(saveCalledOnEdt.get());
        assertFalse(modelSaveCalledOnEdt.get());
        assertTrue(panel.saveButton.isEnabled());
        assertEquals(BuildConfigPanel.SAVED_FEEDBACK_BUTTON_TEXT, panel.saveButton.getText());
        assertFalse(panel.unconfirmedBanner.isVisible());
        assertEquals("Configuration saved successfully!", panel.statusLabel.getText());
        assertEquals(1, confirmCallbackCount.get());
        verify(projectService).setProjectGeminiModel("gemini-3.5-pro");
        verify(projectService).setProjectThinkingLevel("HIGH");
        verify(projectService).setBuildConfirmed(true);
        verify(buildToolService).saveConfigContent(updatedToml);

        SwingTestUtils.clickOnEdt(panel.saveButton);
        SwingTestUtils.awaitFuture(panel.lastAsyncFuture);
        assertEquals(1, confirmCallbackCount.get(), "Confirm callback should only fire on first-time confirmation");

        when(projectService.getProjectGeminiModel()).thenReturn("gemini-3.5-pro");
        when(projectService.getProjectThinkingLevel()).thenReturn("MEDIUM");
        SwingTestUtils.runOnEdt(() -> panel.onProjectChanged(new ProjectChangedEvent(null)));
        SwingTestUtils.awaitFuture(panel.lastAsyncFuture);
        assertEquals("gemini-3.5-pro", ((GeminiModelConfig) modelCombo.getSelectedItem()).apiName());
        assertEquals("MEDIUM", ((ThinkingLevelOption) thinkingCombo.getSelectedItem()).id());

        clearInvocations(projectService);
        when(projectService.isBuildConfirmed()).thenReturn(false);
        doThrow(new IOException("Disk full")).when(buildToolService).saveConfigContent(anyString());

        SwingTestUtils.clickOnEdt(panel.saveButton);
        SwingTestUtils.awaitFuture(panel.lastAsyncFuture);

        assertTrue(panel.saveButton.isEnabled());
        assertEquals(BuildConfigPanel.SAVE_BUTTON_TEXT, panel.saveButton.getText());
        assertEquals("Error saving config: Disk full", panel.statusLabel.getText());
        assertEquals("Error saving config: Disk full", panel.statusLabel.getToolTipText());
        verify(projectService, never()).setBuildConfirmed(true);
    }

    @Test
    void testSaveButtonConfirmedAndDirtyStateTransitions() throws Exception {
        doAnswer(inv -> {
            when(projectService.isBuildConfirmed()).thenReturn(inv.getArgument(0, Boolean.class));
            return null;
        }).when(projectService).setBuildConfirmed(anyBoolean());

        BuildConfigPanel panel = createPanelOnEdt();
        assertEquals(BuildConfigPanel.SAVE_BUTTON_TEXT, panel.saveButton.getText());
        assertFalse(panel.hasUnsavedChanges());

        // Clicking save transitions to immediate post-save feedback state ("Confirmed & Saved!")
        SwingTestUtils.clickOnEdt(panel.saveButton);
        SwingTestUtils.awaitFuture(panel.lastAsyncFuture);
        assertEquals(BuildConfigPanel.SAVED_FEEDBACK_BUTTON_TEXT, panel.saveButton.getText());
        assertTrue(String.valueOf(panel.saveButton.getClientProperty(FlatClientProperties.STYLE))
                .contains("$Actions.Green"));

        // Firing the feedback timer transitions to the persistent clean confirmed state ("Settings Confirmed")
        SwingTestUtils.runOnEdt(() -> {
            for (java.awt.event.ActionListener listener : panel.saveFeedbackTimer.getActionListeners()) {
                listener.actionPerformed(null);
            }
        });
        assertEquals(BuildConfigPanel.CONFIRMED_BUTTON_TEXT, panel.saveButton.getText());
        assertFalse(panel.hasUnsavedChanges());

        // Editing TOML marks panel dirty and transitions button back to "Confirm & Save Settings"
        String originalToml = SwingTestUtils.callOnEdt(panel.textArea::getText);
        SwingTestUtils.runOnEdt(() -> panel.textArea.setText(originalToml + "\n# dirty edit"));
        assertTrue(panel.hasUnsavedChanges());
        assertEquals(BuildConfigPanel.SAVE_BUTTON_TEXT, panel.saveButton.getText());

        // Reverting TOML to saved baseline restores "Settings Confirmed"
        SwingTestUtils.runOnEdt(() -> panel.textArea.setText(originalToml));
        assertFalse(panel.hasUnsavedChanges());
        assertEquals(BuildConfigPanel.CONFIRMED_BUTTON_TEXT, panel.saveButton.getText());

        // Changing modelCombo marks panel dirty
        SwingTestUtils.runOnEdt(() -> panel.modelCombo.setSelectedIndex(1));
        assertTrue(panel.hasUnsavedChanges());
        assertEquals(BuildConfigPanel.SAVE_BUTTON_TEXT, panel.saveButton.getText());

        SwingTestUtils.runOnEdt(() -> panel.modelCombo.setSelectedIndex(0));
        assertFalse(panel.hasUnsavedChanges());
        assertEquals(BuildConfigPanel.CONFIRMED_BUTTON_TEXT, panel.saveButton.getText());

        // Changing thinkingLevelCombo marks panel dirty
        SwingTestUtils.runOnEdt(() -> UiComponents.selectThinkingLevelOptionById(panel.thinkingLevelCombo, "HIGH"));
        assertTrue(panel.hasUnsavedChanges());
        assertEquals(BuildConfigPanel.SAVE_BUTTON_TEXT, panel.saveButton.getText());

        SwingTestUtils.runOnEdt(() -> UiComponents.selectThinkingLevelOptionById(panel.thinkingLevelCombo, ""));
        assertFalse(panel.hasUnsavedChanges());
        assertEquals(BuildConfigPanel.CONFIRMED_BUTTON_TEXT, panel.saveButton.getText());
    }

    @Test
    void testConfigureWithRoxyShowsProgressIndicatorPopulatesEditorAndPromptsOnUnsavedEdits() throws Exception {
        org.roxycode.app.service.AiService aiService = mock(org.roxycode.app.service.AiService.class);
        when(buildToolService.detectTemplate()).thenReturn("maven");
        when(buildToolService.collectBuildConfigurationContext(eq("maven"), anyString()))
                .thenReturn("## Maven Context");
        String tailoredToml = """
                [commands]
                compile = "./mvnw test-compile"
                lint = "./mvnw spotless:check pmd:check"
                [env]
                JAVA_HOME = "/Users/dev/.sdkman/candidates/java/21.0.5-tem"
                [sandbox]
                mode = "native"
                readable_paths = ["~/.sdkman"]
                writable_paths = ["~/.m2"]
                """;
        CountDownLatch inFlightLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        when(aiService.generateBuildToml("## Maven Context")).thenAnswer(inv -> {
            inFlightLatch.countDown();
            releaseLatch.await(5, TimeUnit.SECONDS);
            return tailoredToml;
        });
        when(buildToolService.extractAndValidateGeneratedToml(tailoredToml)).thenReturn(tailoredToml);

        BuildConfigPanel panel = SwingTestUtils
                .callOnEdt(() -> new BuildConfigPanel(buildToolService, settingsService, projectService, aiService));
        SwingTestUtils.awaitFuture(panel.lastAsyncFuture);
        assertFalse(panel.configureProgressBar.isVisible());
        assertFalse(panel.configureStatusLabel.isVisible());

        SwingTestUtils.clickOnEdt(panel.configureWithRoxyButton);
        assertTrue(inFlightLatch.await(5, TimeUnit.SECONDS));

        // Verify progress bar and inline status label are visible while Roxy is configuring
        SwingTestUtils.runOnEdt(() -> {
            assertFalse(panel.configureWithRoxyButton.isEnabled());
            assertEquals(BuildConfigPanel.CONFIGURING_WITH_ROXY_TEXT, panel.configureWithRoxyButton.getText());
            assertTrue(panel.configureProgressBar.isVisible());
            assertTrue(panel.configureProgressBar.isIndeterminate());
            assertTrue(panel.configureStatusLabel.isVisible());
            assertEquals(BuildConfigPanel.CONFIGURING_WITH_ROXY_STATUS, panel.configureStatusLabel.getText());
        });

        releaseLatch.countDown();
        SwingTestUtils.awaitFuture(panel.lastAsyncFuture);

        assertEquals(tailoredToml, panel.textArea.getText());
        assertTrue(panel.hasUnsavedChanges());
        assertTrue(panel.configureWithRoxyButton.isEnabled());
        assertEquals(BuildConfigPanel.CONFIGURE_WITH_ROXY_TEXT, panel.configureWithRoxyButton.getText());
        assertFalse(panel.configureProgressBar.isVisible());
        assertFalse(panel.configureProgressBar.isIndeterminate());
        assertFalse(panel.configureStatusLabel.isVisible());
        assertEquals("Roxy configured build.toml! Review and click 'Confirm & Save Settings'.",
                panel.statusLabel.getText());

        // Verify unsaved-edit confirmation prompt and error handling
        SwingTestUtils.runOnEdt(() -> {
            panel.textArea.setText("# Unsaved user edit");
            panel.configureWithRoxyButton.doClick();
        });
        assertTrue(panel.templateConfirmBar.isPromptVisible());
        assertEquals("Replace unsaved build.toml edits with Roxy's auto-generated configuration?",
                panel.templateConfirmBar.getMessage());

        when(buildToolService.extractAndValidateGeneratedToml(anyString()))
                .thenThrow(new IOException("Invalid TOML syntax"));
        SwingTestUtils.clickOnEdt(panel.templateConfirmBar.confirmButton);
        SwingTestUtils.awaitFuture(panel.lastAsyncFuture);

        assertTrue(panel.configureWithRoxyButton.isEnabled());
        assertFalse(panel.configureProgressBar.isVisible());
        assertFalse(panel.configureStatusLabel.isVisible());
        assertEquals("Roxy configuration failed: Invalid TOML syntax", panel.statusLabel.getText());
        assertEquals("# Unsaved user edit", panel.textArea.getText());

        // Verify interactive callback routing and applyGeneratedBuildToml
        String[] interactivePromptCaptured = new String[1];
        when(buildToolService.buildInteractiveConfigurationPrompt(eq("maven"), anyString()))
                .thenReturn("Interactive prompt for Ask Roxy");
        panel.setOnConfigureWithRoxyCallback(p -> interactivePromptCaptured[0] = p);
        SwingTestUtils.clickOnEdt(panel.configureWithRoxyButton);
        SwingTestUtils.awaitFuture(panel.lastAsyncFuture);
        assertEquals("Interactive prompt for Ask Roxy", interactivePromptCaptured[0]);

        SwingTestUtils.runOnEdt(() -> panel.applyGeneratedBuildToml(tailoredToml));
        assertEquals(tailoredToml, panel.textArea.getText());
        assertTrue(panel.hasUnsavedChanges());
        assertEquals(BuildConfigPanel.APPLIED_FROM_ASK_ROXY_STATUS, panel.statusLabel.getText());
    }
}
