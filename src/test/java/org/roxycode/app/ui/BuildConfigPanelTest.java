package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.formdev.flatlaf.FlatClientProperties;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.model.AgentProfile;
import org.roxycode.app.model.AgentProfileOption;
import org.roxycode.app.model.AppSettings;
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
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getProjectAgentProfile()).thenReturn("");
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
    void testTabbedLayoutIndependentBuildConfigAndModelOverrideSaves() throws Exception {
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
        }).when(projectService).setProjectAgentProfile(anyString());
        doAnswer(inv -> {
            when(projectService.isBuildConfirmed()).thenReturn(inv.getArgument(0, Boolean.class));
            return null;
        }).when(projectService).setBuildConfirmed(anyBoolean());

        BuildConfigPanel panel = createPanelOnEdt();
        assertFalse(loadCalledOnEdt.get());

        // Verify two-tab layout structure
        assertEquals(2, panel.settingsTabs.getTabCount());
        assertEquals(BuildConfigPanel.TAB_BUILD_CONFIG_TITLE,
                panel.settingsTabs.getTitleAt(BuildConfigPanel.TAB_INDEX_BUILD_CONFIG));
        assertEquals(BuildConfigPanel.TAB_AI_MODEL_TITLE,
                panel.settingsTabs.getTitleAt(BuildConfigPanel.TAB_INDEX_AI_MODEL));
        assertEquals(BuildConfigPanel.TAB_INDEX_BUILD_CONFIG, panel.settingsTabs.getSelectedIndex());
        assertSame(panel.buildTabPanel, panel.settingsTabs.getComponentAt(BuildConfigPanel.TAB_INDEX_BUILD_CONFIG));
        assertSame(panel.modelTabPanel, panel.settingsTabs.getComponentAt(BuildConfigPanel.TAB_INDEX_AI_MODEL));

        // Tab 1: Build Configuration header & controls
        assertEquals(BuildConfigPanel.TITLE_TEXT, panel.titleLabel.getText());
        assertEquals(UiComponents.formatWrappingHtml(BuildConfigPanel.SUBTITLE_TEXT), panel.subtitleLabel.getText());
        assertEquals(BuildConfigPanel.BUILD_CONFIG_HINT_TEXT, panel.buildConfigHintLabel.getText());
        assertSame(panel.buildSectionHeaderLabel.getParent(), panel.buildConfigHintLabel.getParent());
        assertSame(panel.buildSectionHeaderLabel.getParent(), panel.configureWithRoxyButton.getParent());
        assertSame(panel.buildSectionHeaderLabel.getParent(), panel.statusLabel.getParent());
        assertNotSame(panel.saveButton.getParent(), panel.statusLabel.getParent());
        assertSame(panel.titleLabel.getParent().getParent(), panel.saveButton.getParent());
        assertFalse(panel.statusLabel.isVisible());
        assertTrue(panel.buildConfigHintLabel.isVisible());
        assertNotNull(panel.buildCardPanel);
        assertEquals(BuildConfigPanel.SAVE_BUTTON_TEXT, panel.saveButton.getText());
        assertTrue(panel.textArea.getText().contains("./mvnw test-compile"));
        assertTrue(panel.unconfirmedBanner.isVisible());
        assertEquals(UiComponents.formatWrappingHtml(BuildConfigPanel.UNCONFIRMED_BUILD_NOTICE_TEXT),
                panel.unconfirmedBanner.messageLabel.getText());

        // Tab 2: AI Model Override header & controls
        assertEquals(BuildConfigPanel.MODEL_TITLE_TEXT, panel.modelTitleLabel.getText());
        assertEquals(UiComponents.formatWrappingHtml(BuildConfigPanel.MODEL_SUBTITLE_TEXT),
                panel.modelSubtitleLabel.getText());
        assertEquals(BuildConfigPanel.MODEL_SAVE_BUTTON_TEXT, panel.modelSaveButton.getText());
        assertSame(panel.modelSaveButton.getParent(), panel.modelStatusLabel.getParent());
        assertSame(panel.modelTitleLabel.getParent().getParent(), panel.modelSaveButton.getParent().getParent());
        assertNotNull(panel.profileInfoIcon.getToolTipText());
        assertNull(panel.profileInfoIcon.getClientProperty(FlatClientProperties.STYLE));

        JComboBox<AgentProfileOption> profileCombo = panel.profileCombo;
        assertEquals(1 + AgentProfile.values().length, profileCombo.getItemCount());
        assertEquals("", profileCombo.getItemAt(0).id());
        assertEquals("Use Global Default (Normal)", profileCombo.getItemAt(0).displayName());
        assertEquals(AgentProfile.NORMAL.getBadgeSummary(), panel.modelApiBadgeLabel.getText());

        java.util.concurrent.atomic.AtomicInteger confirmCallbackCount = new java.util.concurrent.atomic.AtomicInteger();
        panel.setOnConfirmSavedCallback(confirmCallbackCount::incrementAndGet);

        // Save Tab 1 (Build Configuration) -> saves build.toml & sets buildConfirmed=true WITHOUT touching profile
        // overrides
        String updatedToml = """
                [commands]
                compile = 'mvn test'
                """;
        SwingTestUtils.runOnEdt(() -> {
            UiComponents.selectAgentProfileOptionById(profileCombo, "MAX");
            assertEquals(AgentProfile.MAX.getBadgeSummary(), panel.modelApiBadgeLabel.getText());
            assertEquals(AgentProfile.MAX.getDescription(), panel.modelDescriptionArea.getText());
            assertTrue(panel.hasUnsavedModelChanges());
            assertFalse(panel.hasUnsavedChanges(), "Profile dropdown changes must not mark build.toml dirty");

            panel.textArea.setText(updatedToml);
            assertTrue(panel.hasUnsavedChanges());
            panel.saveButton.doClick();
        });
        SwingTestUtils.awaitFuture(panel.lastAsyncFuture);

        assertFalse(saveCalledOnEdt.get());
        assertTrue(panel.saveButton.isEnabled());
        assertEquals(BuildConfigPanel.SAVED_FEEDBACK_BUTTON_TEXT, panel.saveButton.getText());
        assertFalse(panel.unconfirmedBanner.isVisible());
        assertEquals("Configuration saved successfully!", panel.statusLabel.getText());
        assertTrue(panel.statusLabel.isVisible());
        assertFalse(panel.buildConfigHintLabel.isVisible());
        assertEquals(1, confirmCallbackCount.get());
        verify(projectService).setBuildConfirmed(true);
        verify(buildToolService).saveConfigContent(updatedToml);
        verify(projectService, never()).setProjectAgentProfile(anyString());
        assertTrue(panel.hasUnsavedModelChanges(), "Unsaved profile changes remain until modelSaveButton is clicked");

        // Save Tab 2 (AI Model Override) -> saves project AgentProfile WITHOUT touching build.toml
        clearInvocations(buildToolService);
        SwingTestUtils.runOnEdt(() -> {
            panel.selectAiModelTab();
            assertEquals(BuildConfigPanel.TAB_INDEX_AI_MODEL, panel.settingsTabs.getSelectedIndex());
            panel.modelSaveButton.doClick();
        });
        SwingTestUtils.awaitFuture(panel.lastAsyncFuture);

        assertFalse(modelSaveCalledOnEdt.get());
        assertTrue(panel.modelSaveButton.isEnabled());
        assertEquals(BuildConfigPanel.MODEL_SAVED_FEEDBACK_BUTTON_TEXT, panel.modelSaveButton.getText());
        assertEquals(BuildConfigPanel.MODEL_SAVED_STATUS_TEXT, panel.modelStatusLabel.getText());
        assertFalse(panel.hasUnsavedModelChanges());
        verify(projectService).setProjectAgentProfile("MAX");
        verify(buildToolService, never()).saveConfigContent(anyString());

        // Firing modelSaveFeedbackTimer restores "Save Model Settings" button text
        SwingTestUtils.runOnEdt(() -> {
            for (java.awt.event.ActionListener listener : panel.modelSaveFeedbackTimer.getActionListeners()) {
                listener.actionPerformed(null);
            }
        });
        assertEquals(BuildConfigPanel.MODEL_SAVE_BUTTON_TEXT, panel.modelSaveButton.getText());

        SwingTestUtils.clickOnEdt(panel.saveButton);
        SwingTestUtils.awaitFuture(panel.lastAsyncFuture);
        assertEquals(1, confirmCallbackCount.get(), "Confirm callback should only fire on first-time confirmation");

        when(projectService.getProjectAgentProfile()).thenReturn("SPEED");
        SwingTestUtils.runOnEdt(() -> panel.onProjectChanged(new ProjectChangedEvent(null)));
        SwingTestUtils.awaitFuture(panel.lastAsyncFuture);
        assertEquals("SPEED", ((AgentProfileOption) profileCombo.getSelectedItem()).id());

        clearInvocations(projectService);
        when(projectService.isBuildConfirmed()).thenReturn(false);
        String unsupportedSandboxMsg = "Unsupported [sandbox] mode 'docker': Docker is not installed or the Docker daemon is not running.";
        doThrow(new IOException(unsupportedSandboxMsg)).when(buildToolService).saveConfigContent(anyString());

        SwingTestUtils.clickOnEdt(panel.saveButton);
        SwingTestUtils.awaitFuture(panel.lastAsyncFuture);

        assertTrue(panel.saveButton.isEnabled());
        assertEquals(BuildConfigPanel.SAVE_BUTTON_TEXT, panel.saveButton.getText());
        assertEquals("Error saving config: " + unsupportedSandboxMsg, panel.statusLabel.getText());
        assertEquals("Error saving config: " + unsupportedSandboxMsg, panel.statusLabel.getToolTipText());
        assertTrue(panel.statusLabel.isVisible());
        assertFalse(panel.buildConfigHintLabel.isVisible());
        verify(projectService, never()).setBuildConfirmed(true);

        // Editing TOML clears the error status and restores the hint label
        SwingTestUtils.runOnEdt(() -> panel.textArea.setText(updatedToml + "# fixed"));
        assertFalse(panel.statusLabel.isVisible());
        assertEquals("", panel.statusLabel.getText());
        assertTrue(panel.buildConfigHintLabel.isVisible());
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
        assertFalse(panel.hasUnsavedModelChanges());

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

        // Editing TOML marks build tab dirty and transitions button back to "Confirm & Save Settings"
        String originalToml = SwingTestUtils.callOnEdt(panel.textArea::getText);
        SwingTestUtils.runOnEdt(() -> panel.textArea.setText(originalToml + "# dirty edit"));
        assertTrue(panel.hasUnsavedChanges());
        assertEquals(BuildConfigPanel.SAVE_BUTTON_TEXT, panel.saveButton.getText());

        // Reverting TOML to saved baseline restores "Settings Confirmed"
        SwingTestUtils.runOnEdt(() -> panel.textArea.setText(originalToml));
        assertFalse(panel.hasUnsavedChanges());
        assertEquals(BuildConfigPanel.CONFIRMED_BUTTON_TEXT, panel.saveButton.getText());

        // Changing profileCombo marks model tab dirty without affecting build tab confirmed state
        SwingTestUtils.runOnEdt(() -> UiComponents.selectAgentProfileOptionById(panel.profileCombo, "MAX"));
        assertTrue(panel.hasUnsavedModelChanges());
        assertFalse(panel.hasUnsavedChanges());
        assertEquals(BuildConfigPanel.CONFIRMED_BUTTON_TEXT, panel.saveButton.getText());

        SwingTestUtils.runOnEdt(() -> UiComponents.selectAgentProfileOptionById(panel.profileCombo, ""));
        assertFalse(panel.hasUnsavedModelChanges());
    }

    @Test
    void testConfigureWithRoxyShowsProgressIndicatorPopulatesEditorAndPromptsOnUnsavedEdits() throws Exception {
        when(buildToolService.detectTemplate()).thenReturn("maven");
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

        BuildConfigPanel panel = SwingTestUtils
                .callOnEdt(() -> new BuildConfigPanel(buildToolService, settingsService, projectService));
        SwingTestUtils.awaitFuture(panel.lastAsyncFuture);

        // When callback is not wired yet, clicking Configure with Roxy shows an unavailable status
        SwingTestUtils.clickOnEdt(panel.configureWithRoxyButton);
        assertEquals("Roxy AI service is not available.", panel.statusLabel.getText());

        // Verify interactive callback routing and error handling
        String[] interactivePromptCaptured = new String[1];
        when(buildToolService.buildInteractiveConfigurationPrompt(eq("maven"), anyString()))
                .thenReturn("Interactive prompt for Ask Roxy");
        panel.setOnConfigureWithRoxyCallback(p -> interactivePromptCaptured[0] = p);
        SwingTestUtils.clickOnEdt(panel.configureWithRoxyButton);
        SwingTestUtils.awaitFuture(panel.lastAsyncFuture);
        assertEquals("Interactive prompt for Ask Roxy", interactivePromptCaptured[0]);

        when(buildToolService.buildInteractiveConfigurationPrompt(eq("maven"), anyString()))
                .thenThrow(new IllegalStateException("Prompt preparation error"));
        SwingTestUtils.clickOnEdt(panel.configureWithRoxyButton);
        SwingTestUtils.awaitFuture(panel.lastAsyncFuture);
        assertEquals("Roxy configuration failed: Prompt preparation error", panel.statusLabel.getText());

        // Verify applyGeneratedBuildToml switches back to Build Configuration tab
        SwingTestUtils.runOnEdt(() -> {
            panel.selectAiModelTab();
            assertEquals(BuildConfigPanel.TAB_INDEX_AI_MODEL, panel.settingsTabs.getSelectedIndex());
            panel.applyGeneratedBuildToml(tailoredToml);
        });
        assertEquals(BuildConfigPanel.TAB_INDEX_BUILD_CONFIG, panel.settingsTabs.getSelectedIndex());
        assertEquals(tailoredToml, panel.textArea.getText());
        assertTrue(panel.hasUnsavedChanges());
        assertEquals(BuildConfigPanel.APPLIED_FROM_ASK_ROXY_STATUS, panel.statusLabel.getText());
    }
}
