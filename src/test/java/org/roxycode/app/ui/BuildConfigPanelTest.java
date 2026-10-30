package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.services.BuildContainerService;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SystemToolsUpdatedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.GeminiModelConfig;
import org.roxycode.app.model.SystemTool;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.service.SystemToolService;

class BuildConfigPanelTest {

    private GenericBuildToolService buildToolService;
    private SettingsService settingsService;
    private SystemToolService systemToolService;
    private ProjectService projectService;

    @BeforeEach
    void setUp() throws Exception {
        buildToolService = mock(GenericBuildToolService.class);
        settingsService = mock(SettingsService.class);
        systemToolService = mock(SystemToolService.class);
        projectService = mock(ProjectService.class);

        when(settingsService.getSettings()).thenReturn(new AppSettings());
        when(settingsService.getAvailableModels())
                .thenReturn(List.of(new GeminiModelConfig("gemini-3.5-flash", "Gemini 3.5 Flash", "Fast default model"),
                        new GeminiModelConfig("gemini-3.5-pro", "Gemini 3.5 Pro", "Advanced reasoning model")));
        when(projectService.hasActiveProject()).thenReturn(true);
        when(projectService.getProjectGeminiModel()).thenReturn("");
        when(buildToolService.getConfigContent()).thenReturn("""
                [build]
                compile = './mvnw compile'

                [container]
                enabled = false
                image = 'eclipse-temurin:25-jdk'
                workdir = '/workspace'
                """);
    }

    private BuildConfigPanel createPanelOnEdt() {
        BuildConfigPanel panel = SwingTestUtils.callOnEdt(
                () -> new BuildConfigPanel(buildToolService, settingsService, systemToolService, projectService));
        SwingTestUtils.flushEdt();
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
                    [build]
                    compile = './mvnw compile'
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
        when(systemToolService.isDockerAvailable()).thenReturn(CompletableFuture.completedFuture(false));

        BuildConfigPanel panel = createPanelOnEdt();
        assertFalse(loadCalledOnEdt.get());
        assertEquals(BuildConfigPanel.TITLE_TEXT, panel.titleLabel.getText());
        assertTrue(panel.textArea.getText().contains("./mvnw compile"));

        JComboBox<GeminiModelConfig> modelCombo = panel.getModelCombo();
        assertEquals(3, modelCombo.getItemCount());
        assertEquals("", modelCombo.getItemAt(0).apiName());
        assertEquals("gemini-3.5-flash", panel.getModelApiBadgeLabel().getText());

        String updatedToml = """
                [build]
                compile = 'mvn test'
                """;
        SwingTestUtils.runOnEdt(() -> {
            modelCombo.setSelectedIndex(2);
            assertEquals("gemini-3.5-pro", panel.getModelApiBadgeLabel().getText());
            assertEquals("Advanced reasoning model", panel.getModelDescriptionArea().getText());
            panel.textArea.setText(updatedToml);
            panel.saveButton.doClick();
        });
        SwingTestUtils.flushEdt();

        assertFalse(saveCalledOnEdt.get());
        assertFalse(modelSaveCalledOnEdt.get());
        assertTrue(panel.saveButton.isEnabled());
        assertEquals("Configuration saved successfully!", panel.statusLabel.getText());
        verify(projectService).setProjectGeminiModel("gemini-3.5-pro");
        verify(buildToolService).saveConfigContent(updatedToml);

        when(projectService.getProjectGeminiModel()).thenReturn("gemini-3.5-pro");
        SwingTestUtils.runOnEdt(() -> panel.onProjectChanged(new ProjectChangedEvent(null)));
        SwingTestUtils.flushEdt();
        assertEquals("gemini-3.5-pro", ((GeminiModelConfig) modelCombo.getSelectedItem()).apiName());

        AtomicReference<String> errorMsg = new AtomicReference<>();
        panel.setErrorDialogPresenter(errorMsg::set);
        doThrow(new IOException("Disk full")).when(buildToolService).saveConfigContent(anyString());

        SwingTestUtils.clickOnEdt(panel.saveButton);

        assertTrue(panel.saveButton.isEnabled());
        assertEquals("Error saving config: Disk full", errorMsg.get());
    }

    @Test
    void testDockerAvailableAndToggleCheckboxUpdatesTomlAndBanner() {
        when(systemToolService.isDockerAvailable()).thenReturn(CompletableFuture.completedFuture(true));

        BuildConfigPanel panel = createPanelOnEdt();
        assertTrue(panel.dockerAvailable);
        assertEquals(BuildConfigPanel.HOST_EXECUTION_ACTIVE_TEXT, panel.bannerMessageLabel.getText());
        assertTrue(panel.containerToggleCheckBox.isEnabled());
        assertFalse(panel.containerToggleCheckBox.isSelected());
        verify(systemToolService, never()).refreshDockerAvailable();
        verify(systemToolService, never()).refreshTools();
        verify(systemToolService, never()).checkDockerAvailable();
        verify(systemToolService, never()).hasCachedTools();

        SwingTestUtils.clickOnEdt(panel.containerToggleCheckBox);
        assertTrue(panel.containerToggleCheckBox.isSelected());
        assertTrue(BuildContainerService.isContainerEnabledInToml(panel.textArea.getText()));
        assertEquals(BuildConfigPanel.CONTAINER_ACTIVE_TEXT, panel.bannerMessageLabel.getText());

        SwingTestUtils.clickOnEdt(panel.containerToggleCheckBox);
        assertFalse(panel.containerToggleCheckBox.isSelected());
        assertFalse(BuildContainerService.isContainerEnabledInToml(panel.textArea.getText()));
        assertEquals(BuildConfigPanel.HOST_EXECUTION_ACTIVE_TEXT, panel.bannerMessageLabel.getText());

        SwingTestUtils.runOnEdt(() -> panel.textArea.setText("""
                [build]
                compile = 'mvn'

                [container] # inline comment
                enabled = true # keep enabled
                """));
        assertTrue(panel.containerToggleCheckBox.isSelected());
        assertEquals(BuildConfigPanel.CONTAINER_ACTIVE_TEXT, panel.bannerMessageLabel.getText());
    }

    @Test
    void testRecheckDockerButtonUpdatesAvailabilityAndAppendsContainerSectionIfMissing() throws Exception {
        when(buildToolService.getConfigContent()).thenReturn("""
                [build]
                compile = 'mvn'
                """);
        when(systemToolService.isDockerAvailable()).thenReturn(CompletableFuture.completedFuture(false));

        BuildConfigPanel panel = createPanelOnEdt();
        assertFalse(panel.containerToggleCheckBox.isEnabled());

        CompletableFuture<Boolean> pendingRecheck = new CompletableFuture<>();
        when(systemToolService.refreshDockerAvailable()).thenReturn(pendingRecheck);

        SwingTestUtils.runOnEdt(() -> panel.recheckDockerButton.doClick());
        SwingTestUtils.runOnEdt(() -> {
            assertFalse(panel.recheckDockerButton.isEnabled());
            assertEquals(BuildConfigPanel.CHECKING_DOCKER_TEXT, panel.recheckDockerButton.getText());
        });

        pendingRecheck.complete(true);
        SwingTestUtils.flushEdt();

        assertTrue(panel.recheckDockerButton.isEnabled());
        assertEquals(BuildConfigPanel.RECHECK_DOCKER_TEXT, panel.recheckDockerButton.getText());
        assertTrue(panel.containerToggleCheckBox.isEnabled());

        SwingTestUtils.clickOnEdt(panel.containerToggleCheckBox);
        assertTrue(panel.textArea.getText().contains("[container]"));
        assertTrue(BuildContainerService.isContainerEnabledInToml(panel.textArea.getText()));
    }

    @Test
    void testOnSystemToolsUpdatedEventSynchronizesDockerAvailability() {
        when(systemToolService.isDockerAvailable()).thenReturn(CompletableFuture.completedFuture(false));

        BuildConfigPanel panel = createPanelOnEdt();
        assertFalse(panel.dockerAvailable);
        assertEquals(BuildConfigPanel.DOCKER_MISSING_WARNING_TEXT, panel.bannerMessageLabel.getText());
        assertFalse(panel.containerToggleCheckBox.isEnabled());
        assertFalse(panel.containerToggleCheckBox.isSelected());

        panel.onSystemToolsUpdated(
                new SystemToolsUpdatedEvent(List.of(new SystemTool("Docker", "28.0", "Detected", "Desc")), true));
        SwingTestUtils.flushEdt();

        assertTrue(panel.dockerAvailable);
        assertEquals(BuildConfigPanel.HOST_EXECUTION_ACTIVE_TEXT, panel.bannerMessageLabel.getText());
        assertTrue(panel.containerToggleCheckBox.isEnabled());
    }
}
