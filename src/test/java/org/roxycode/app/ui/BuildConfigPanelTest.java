package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.model.AgentProfile;
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
        SwingTestUtils.awaitFuture(panel.summaryParseFuture);
        return panel;
    }

    private static void flushSummaryDebounce(BuildConfigPanel panel) {
        SwingTestUtils.runOnEdt(() -> {
            panel.summaryDebounceTimer.stop();
            for (java.awt.event.ActionListener listener : panel.summaryDebounceTimer.getActionListeners()) {
                listener.actionPerformed(null);
            }
        });
        SwingTestUtils.awaitFuture(panel.summaryParseFuture);
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

        // Verify three-tab layout structure
        assertEquals(3, panel.settingsTabs.getTabCount());
        assertEquals(BuildConfigPanel.TAB_BUILD_CONFIG_TITLE,
                panel.settingsTabs.getTitleAt(BuildConfigPanel.TAB_INDEX_BUILD_CONFIG));
        assertEquals(BuildConfigPanel.TAB_AI_MODEL_TITLE + " (Global)",
                panel.settingsTabs.getTitleAt(BuildConfigPanel.TAB_INDEX_AI_MODEL));
        assertEquals(BuildConfigPanel.TAB_DATABASES_TITLE,
                panel.settingsTabs.getTitleAt(BuildConfigPanel.TAB_INDEX_DATABASES));
        assertEquals("Global (Normal)", panel.modelOverrideStatusPill.getText());
        assertEquals(BuildConfigPanel.TAB_INDEX_BUILD_CONFIG, panel.settingsTabs.getSelectedIndex());
        assertSame(panel.buildTabPanel, panel.settingsTabs.getComponentAt(BuildConfigPanel.TAB_INDEX_BUILD_CONFIG));
        assertSame(panel.modelTabPanel, panel.settingsTabs.getComponentAt(BuildConfigPanel.TAB_INDEX_AI_MODEL));
        assertSame(panel.databasesTabPanel, panel.settingsTabs.getComponentAt(BuildConfigPanel.TAB_INDEX_DATABASES));

        // Tab 1: Build Configuration state
        assertFalse(panel.statusLabel.isVisible());
        assertTrue(panel.buildConfigHintLabel.isVisible());
        assertTrue(panel.textArea.getText().contains("./mvnw test-compile"));
        assertTrue(panel.unconfirmedBanner.isVisible());

        // Tab 2: AI Model Override controls
        assertNotNull(panel.profileInfoIcon.getToolTipText());

        assertTrue(panel.globalDefaultProfileCard.isSelected());
        assertTrue(panel.globalDefaultProfileCard.currentGlobalBadge.getText().contains("Normal"));
        assertEquals(AgentProfile.values().length, panel.profileCards.size());
        for (UiComponents.AgentProfileCard card : panel.profileCards.values()) {
            assertFalse(card.isSelected());
        }

        java.util.concurrent.atomic.AtomicInteger confirmCallbackCount = new java.util.concurrent.atomic.AtomicInteger();
        panel.setOnConfirmSavedCallback(confirmCallbackCount::incrementAndGet);

        // Save Tab 1 (Build Configuration) -> saves build.toml & sets buildConfirmed=true WITHOUT touching profile
        // overrides
        String updatedToml = """
                [commands]
                compile = 'mvn test'
                """;
        SwingTestUtils.runOnEdt(() -> {
            panel.selectProfileOverride("MAX");
            assertFalse(panel.globalDefaultProfileCard.isSelected());
            assertTrue(panel.profileCards.get(AgentProfile.MAX).isSelected());
            assertEquals("Override: Max", panel.modelOverrideStatusPill.getText());
            assertEquals(BuildConfigPanel.TAB_AI_MODEL_TITLE + " (Max)",
                    panel.settingsTabs.getTitleAt(BuildConfigPanel.TAB_INDEX_AI_MODEL));
            assertTrue(panel.hasUnsavedModelChanges());
            assertFalse(panel.hasUnsavedChanges(), "Profile card changes must not mark build.toml dirty");

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
        assertEquals("SPEED", panel.selectedProfileId);
        assertTrue(panel.profileCards.get(AgentProfile.SPEED).isSelected());
        assertEquals("Override: Speed", panel.modelOverrideStatusPill.getText());

        clearInvocations(projectService);
        when(projectService.isBuildConfirmed()).thenReturn(false);
        String unsupportedSandboxMsg = "Unsupported [sandbox] mode 'docker': Docker is not installed or the Docker daemon is not running.";
        doThrow(new IOException(unsupportedSandboxMsg)).when(buildToolService).saveConfigContent(anyString());

        SwingTestUtils.clickOnEdt(panel.saveButton);
        SwingTestUtils.awaitFuture(panel.lastAsyncFuture);

        assertTrue(panel.saveButton.isEnabled());
        assertEquals(BuildConfigPanel.SAVE_BUTTON_TEXT, panel.saveButton.getText());
        assertEquals(UiComponents.formatWrappingHtml("Error saving config: " + unsupportedSandboxMsg),
                panel.statusLabel.getText());
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

        // Changing interactive profile card marks model tab dirty without affecting build tab confirmed state
        SwingTestUtils.runOnEdt(() -> panel.selectProfileOverride("MAX"));
        assertTrue(panel.hasUnsavedModelChanges());
        assertTrue(panel.profileCards.get(AgentProfile.MAX).isSelected());
        assertFalse(panel.hasUnsavedChanges());
        assertEquals(BuildConfigPanel.CONFIRMED_BUTTON_TEXT, panel.saveButton.getText());

        SwingTestUtils.runOnEdt(() -> panel.selectProfileOverride(""));
        assertFalse(panel.hasUnsavedModelChanges());
        assertTrue(panel.globalDefaultProfileCard.isSelected());
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

    @Test
    void testLiveSummaryStripUpdatesOnLoadAndEdit() {
        BuildConfigPanel panel = createPanelOnEdt();

        assertEquals("1 / 8 configured", panel.pipelineSummaryCard.valueLabel.getText());
        assertEquals("compile", panel.pipelineSummaryCard.subtitleLabel.getText());
        assertEquals("none (Host execution)", panel.sandboxSummaryCard.valueLabel.getText());
        assertEquals("Default log tail", panel.parserSummaryCard.valueLabel.getText());
        assertEquals("600s • Valid TOML", panel.syntaxSummaryCard.valueLabel.getText());

        String fullToml = """
                timeout_seconds = 120

                [commands]
                install = "./mvnw dependency:resolve"
                clean = "./mvnw clean"
                compile = "./mvnw test-compile"
                build = "./mvnw package"
                test_all = "./mvnw test"
                test_single = "./mvnw test -Dtest={testName}"
                lint = "./mvnw spotless:check"
                format = "./mvnw spotless:apply"

                [env]
                JAVA_HOME = "/opt/jdk"

                [parse]
                error_regex = "^ERROR"
                test_failure_regex = "Failures:"
                context_lines = 5
                junit_xml_paths = ["target/surefire-reports"]

                [sandbox]
                mode = "native"
                readable_paths = ["~/.sdkman"]
                writable_paths = ["~/.m2"]
                """;
        SwingTestUtils.runOnEdt(() -> panel.textArea.setText(fullToml));
        flushSummaryDebounce(panel);
        assertEquals("8 / 8 configured", panel.pipelineSummaryCard.valueLabel.getText());
        assertEquals("native (OS sandbox)", panel.sandboxSummaryCard.valueLabel.getText());
        assertEquals("1 read, 1 write paths", panel.sandboxSummaryCard.subtitleLabel.getText());
        assertEquals("Error & Test regexes", panel.parserSummaryCard.valueLabel.getText());
        assertEquals("5 lines • 1 JUnit path(s)", panel.parserSummaryCard.subtitleLabel.getText());
        assertEquals("120s • Valid TOML", panel.syntaxSummaryCard.valueLabel.getText());
        assertEquals("1 env var(s)", panel.syntaxSummaryCard.subtitleLabel.getText());

        String dockerToml = """
                [commands]
                compile = "mvn compile"

                [sandbox]
                mode = "docker"
                image = "eclipse-temurin:21-jdk"
                workdir = "/app"
                """;
        try {
            when(buildToolService.validateConfig(anyString()))
                    .thenThrow(new IOException("Unsupported [sandbox] mode 'docker': Docker daemon is not running."));
        } catch (IOException ignored) {
            // mockito stubbing
        }
        SwingTestUtils.runOnEdt(() -> panel.textArea.setText(dockerToml));
        flushSummaryDebounce(panel);
        assertEquals("docker (eclipse-temurin:21-jdk)", panel.sandboxSummaryCard.valueLabel.getText());
        assertEquals("workdir: /app", panel.sandboxSummaryCard.subtitleLabel.getText());
        assertEquals("600s • Valid TOML", panel.syntaxSummaryCard.valueLabel.getText());

        SwingTestUtils.runOnEdt(() -> panel.textArea.setText("[[broken"));
        flushSummaryDebounce(panel);
        assertEquals("Invalid TOML Syntax", panel.syntaxSummaryCard.valueLabel.getText());
        assertEquals("— / 8 configured", panel.pipelineSummaryCard.valueLabel.getText());

        // Saving before the 200ms debounce timer fires must still refresh the live summary cards
        SwingTestUtils.runOnEdt(() -> panel.textArea.setText(fullToml));
        assertTrue(panel.summaryDebounceTimer.isRunning());
        SwingTestUtils.clickOnEdt(panel.saveButton);
        SwingTestUtils.awaitFuture(panel.lastAsyncFuture);
        SwingTestUtils.awaitFuture(panel.summaryParseFuture);
        assertFalse(panel.summaryDebounceTimer.isRunning());
        assertEquals("8 / 8 configured", panel.pipelineSummaryCard.valueLabel.getText());
        assertEquals("native (OS sandbox)", panel.sandboxSummaryCard.valueLabel.getText());
    }

    @Test
    void testInteractiveProfileCardMouseClicks() {
        BuildConfigPanel panel = createPanelOnEdt();
        assertTrue(panel.globalDefaultProfileCard.isSelected());
        assertFalse(panel.hasUnsavedModelChanges());

        SwingTestUtils.runOnEdt(() -> {
            UiComponents.AgentProfileCard maxCard = panel.profileCards.get(AgentProfile.MAX);
            maxCard.dispatchEvent(
                    new MouseEvent(maxCard, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 10, 10, 1, false));
        });
        assertFalse(panel.globalDefaultProfileCard.isSelected());
        assertTrue(panel.profileCards.get(AgentProfile.MAX).isSelected());
        assertEquals("Override: Max", panel.modelOverrideStatusPill.getText());
        assertEquals(BuildConfigPanel.TAB_AI_MODEL_TITLE + " (Max)",
                panel.settingsTabs.getTitleAt(BuildConfigPanel.TAB_INDEX_AI_MODEL));
        assertTrue(panel.hasUnsavedModelChanges());

        SwingTestUtils.runOnEdt(() -> {
            UiComponents.AgentProfileCard speedCard = panel.profileCards.get(AgentProfile.SPEED);
            speedCard.dispatchEvent(new MouseEvent(speedCard, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0,
                    10, 10, 1, false));
        });
        assertFalse(panel.profileCards.get(AgentProfile.MAX).isSelected());
        assertTrue(panel.profileCards.get(AgentProfile.SPEED).isSelected());
        assertEquals("Override: Speed", panel.modelOverrideStatusPill.getText());

        SwingTestUtils.runOnEdt(
                () -> panel.globalDefaultProfileCard.dispatchEvent(new MouseEvent(panel.globalDefaultProfileCard,
                        MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 10, 10, 1, false)));
        assertTrue(panel.globalDefaultProfileCard.isSelected());
        assertFalse(panel.profileCards.get(AgentProfile.SPEED).isSelected());
        assertEquals("Global (Normal)", panel.modelOverrideStatusPill.getText());
        assertEquals(BuildConfigPanel.TAB_AI_MODEL_TITLE + " (Global)",
                panel.settingsTabs.getTitleAt(BuildConfigPanel.TAB_INDEX_AI_MODEL));
        assertFalse(panel.hasUnsavedModelChanges());
    }

    @Test
    void testSectionJumpPillsAndToolbarButtons() {
        BuildConfigPanel panel = createPanelOnEdt();
        String multiSectionToml = """
                [commands]
                compile = "./mvnw test-compile"

                [env]
                JAVA_HOME = "/opt/jdk"

                [parse]
                error_regex = "ERROR"

                [sandbox]
                mode = "none"
                """;
        SwingTestUtils.runOnEdt(() -> panel.textArea.setText(multiSectionToml));

        SwingTestUtils.clickOnEdt(panel.jumpEnvButton);
        assertEquals(multiSectionToml.indexOf("[env]"), panel.textArea.getCaretPosition());

        SwingTestUtils.clickOnEdt(panel.jumpParseButton);
        assertEquals(multiSectionToml.indexOf("[parse]"), panel.textArea.getCaretPosition());

        SwingTestUtils.clickOnEdt(panel.jumpSandboxButton);
        assertEquals(multiSectionToml.indexOf("[sandbox]"), panel.textArea.getCaretPosition());

        SwingTestUtils.clickOnEdt(panel.jumpCommandsButton);
        assertEquals(multiSectionToml.indexOf("[commands]"), panel.textArea.getCaretPosition());

        assertNotNull(panel.configureWithRoxyButton.getIcon());
        assertEquals(org.kordamp.ikonli.codicons.Codicons.LIGHTBULB_AUTOFIX,
                ((org.kordamp.ikonli.swing.FontIcon) panel.configureWithRoxyButton.getIcon()).getIkon());
        assertTrue(String.valueOf(panel.configureWithRoxyButton.getClientProperty(FlatClientProperties.STYLE))
                .contains("#f5f3ff"));

        SwingTestUtils.clickOnEdt(panel.copyTomlButton);
        assertEquals("Copied!", panel.copyTomlButton.getText());
    }

    @Test
    void testDatabaseGrantCardsDisplayGlobalDescriptionAndVisibleSaveStatus() throws Exception {
        org.roxycode.app.service.DatabaseConfigService dbConfigService = mock(
                org.roxycode.app.service.DatabaseConfigService.class);
        org.roxycode.app.model.DatabaseConnectionConfig cfg = new org.roxycode.app.model.DatabaseConnectionConfig(
                "qa-billing", "QA Billing", "Core billing and invoice tables", "postgresql",
                "jdbc:postgresql://localhost:5432/billing", "ro_user", "keyring", "", java.util.List.of(), "", "public",
                15, 200);
        when(dbConfigService.listGlobalConnections()).thenReturn(java.util.List.of(cfg));
        when(dbConfigService.listProjectGrants()).thenReturn(java.util.List.of());
        when(dbConfigService.saveProjectGrants(anyList())).thenAnswer(inv -> inv.getArgument(0));

        BuildConfigPanel panel = SwingTestUtils.callOnEdt(
                () -> new BuildConfigPanel(buildToolService, settingsService, projectService, dbConfigService));
        SwingTestUtils.awaitFuture(panel.lastAsyncFuture);
        SwingTestUtils.flushEdt();

        assertEquals(1, panel.grantRowControls.size());
        assertTrue(containsLabelWithText(panel.grantRowsContainer, "Core billing and invoice tables"));

        SwingTestUtils.runOnEdt(() -> panel.grantRowControls.get(0).grantCheckBox.setSelected(true));
        SwingTestUtils.clickOnEdt(panel.saveGrantsButton);
        SwingTestUtils.awaitFuture(panel.lastAsyncFuture);
        SwingTestUtils.flushEdt();

        assertTrue(panel.grantsStatusLabel.isVisible());
        assertTrue(panel.grantsStatusLabel.getText().contains("Saved 1 database grant(s)"));
    }

    private static boolean containsLabelWithText(java.awt.Container root, String expectedSubstring) {
        for (java.awt.Component comp : root.getComponents()) {
            if (comp instanceof javax.swing.JLabel label && label.getText() != null
                    && label.getText().contains(expectedSubstring)) {
                return true;
            }
            if (comp instanceof java.awt.Container nested && containsLabelWithText(nested, expectedSubstring)) {
                return true;
            }
        }
        return false;
    }
}
