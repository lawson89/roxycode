package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.List;
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
        assertTrue(panel.textArea.getText().contains("./mvnw test-compile"));
        assertTrue(panel.unconfirmedBanner.isVisible());
        assertEquals(BuildConfigPanel.UNCONFIRMED_BUILD_NOTICE_TEXT, panel.unconfirmedBanner.messageLabel.getText());

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
        assertEquals("Error saving config: Disk full", panel.statusLabel.getText());
        assertEquals("Error saving config: Disk full", panel.statusLabel.getToolTipText());
        verify(projectService, never()).setBuildConfirmed(true);
    }

    @Test
    void testTemplateSelectorAndApplyTemplate() throws Exception {
        when(buildToolService.getAvailableTemplates()).thenReturn(GenericBuildToolService.AVAILABLE_TEMPLATES);
        when(buildToolService.detectTemplate()).thenReturn("maven");
        when(buildToolService.loadTemplateContent("rust")).thenReturn("""
                [commands]
                compile = 'cargo check'
                """);

        BuildConfigPanel panel = createPanelOnEdt();

        GenericBuildToolService.BuildTemplateInfo selected = (GenericBuildToolService.BuildTemplateInfo) panel.templateCombo
                .getSelectedItem();
        assertNotNull(selected);
        assertEquals("maven", selected.id());
        assertEquals("Maven (auto-detected)", panel.formatTemplateDisplayText(selected));

        SwingTestUtils.runOnEdt(() -> {
            for (int i = 0; i < panel.templateCombo.getItemCount(); i++) {
                GenericBuildToolService.BuildTemplateInfo item = panel.templateCombo.getItemAt(i);
                if ("rust".equals(item.id())) {
                    panel.templateCombo.setSelectedIndex(i);
                    break;
                }
            }
            panel.applyTemplateButton.doClick();
        });
        assertTrue(panel.textArea.getText().contains("cargo check"));

        when(buildToolService.loadTemplateContent("maven")).thenReturn("""
                [commands]
                compile = './mvnw test-compile'
                """);
        SwingTestUtils.runOnEdt(() -> {
            panel.textArea.setText("# User edited build.toml");
            panel.templateCombo.setSelectedIndex(0);
            panel.applyTemplateButton.doClick();
        });

        UiComponents.InlineConfirmBar confirmBar = panel.templateConfirmBar;
        assertTrue(confirmBar.isPromptVisible());
        assertEquals("Replace unsaved build.toml edits with 'Maven'?", confirmBar.getMessage());
        SwingTestUtils.clickOnEdt(confirmBar.cancelButton);
        assertFalse(confirmBar.isPromptVisible());
        assertEquals("# User edited build.toml", panel.textArea.getText());

        SwingTestUtils.clickOnEdt(panel.applyTemplateButton);
        assertTrue(confirmBar.isPromptVisible());
        SwingTestUtils.runOnEdt(() -> panel.textArea.setText("# User edited build.toml again"));
        assertFalse(confirmBar.isPromptVisible());

        SwingTestUtils.clickOnEdt(panel.applyTemplateButton);
        assertTrue(confirmBar.isPromptVisible());
        SwingTestUtils.clickOnEdt(confirmBar.confirmButton);
        assertFalse(confirmBar.isPromptVisible());
        assertTrue(panel.textArea.getText().contains("./mvnw test-compile"));
        assertEquals("Applied 'Maven' template.", panel.statusLabel.getText());
    }
}
