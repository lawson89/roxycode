package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.services.BuildContainerService;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.events.SystemToolsUpdatedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.SystemTool;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.service.SystemToolService;

class BuildConfigPanelTest {

    private GenericBuildToolService buildToolService;
    private SettingsService settingsService;
    private SystemToolService systemToolService;

    @BeforeEach
    void setUp() throws Exception {
        buildToolService = mock(GenericBuildToolService.class);
        settingsService = mock(SettingsService.class);
        systemToolService = mock(SystemToolService.class);

        when(settingsService.getSettings()).thenReturn(new AppSettings());
        when(buildToolService.getConfigContent()).thenReturn(
                "[build]\ncompile = './mvnw compile'\n\n[container]\nenabled = false\nimage = 'eclipse-temurin:25-jdk'\nworkdir = '/workspace'\n");
    }

    private BuildConfigPanel createPanelOnEdt() {
        BuildConfigPanel panel = SwingTestUtils
                .callOnEdt(() -> new BuildConfigPanel(buildToolService, settingsService, systemToolService));
        SwingTestUtils.awaitWorker(panel.loadConfigFuture);
        return panel;
    }

    @Test
    void testLoadAndSaveConfigRunOffEdtAndDisableSaveButtonDuringSave() throws Exception {
        AtomicBoolean loadCalledOnEdt = new AtomicBoolean(false);
        AtomicBoolean saveCalledOnEdt = new AtomicBoolean(false);
        when(buildToolService.getConfigContent()).thenAnswer(inv -> {
            loadCalledOnEdt.set(SwingUtilities.isEventDispatchThread());
            return "[build]\ncompile = './mvnw compile'\n";
        });
        doAnswer(inv -> {
            saveCalledOnEdt.set(SwingUtilities.isEventDispatchThread());
            return null;
        }).when(buildToolService).saveConfigContent(anyString());
        when(systemToolService.isDockerAvailable()).thenReturn(CompletableFuture.completedFuture(false));

        BuildConfigPanel panel = createPanelOnEdt();
        assertFalse(loadCalledOnEdt.get());
        assertTrue(panel.textArea.getText().contains("./mvnw compile"));

        SwingTestUtils.runOnEdt(() -> {
            panel.textArea.setText("[build]\ncompile = 'mvn test'\n");
            panel.saveButton.doClick();
        });
        SwingTestUtils.awaitWorker(panel.saveConfigFuture);

        assertFalse(saveCalledOnEdt.get());
        assertTrue(panel.saveButton.isEnabled());
        assertEquals("Configuration saved successfully!", panel.statusLabel.getText());
        verify(buildToolService).saveConfigContent("[build]\ncompile = 'mvn test'\n");

        AtomicReference<String> errorMsg = new AtomicReference<>();
        panel.setErrorDialogPresenter(errorMsg::set);
        doThrow(new IOException("Disk full")).when(buildToolService).saveConfigContent(anyString());

        SwingTestUtils.clickOnEdt(panel.saveButton);
        assertThrows(Exception.class, () -> panel.saveConfigFuture.get(5, TimeUnit.SECONDS));
        SwingTestUtils.flushEdt();

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
        when(buildToolService.getConfigContent()).thenReturn("[build]\ncompile = 'mvn'");
        when(systemToolService.isDockerAvailable()).thenReturn(CompletableFuture.completedFuture(false));

        BuildConfigPanel panel = createPanelOnEdt();
        assertFalse(panel.containerToggleCheckBox.isEnabled());

        CompletableFuture<Boolean> pendingRecheck = new CompletableFuture<>();
        when(systemToolService.refreshDockerAvailable()).thenReturn(pendingRecheck);

        SwingTestUtils.clickOnEdt(panel.recheckDockerButton);
        assertFalse(panel.recheckDockerButton.isEnabled());
        assertEquals(BuildConfigPanel.CHECKING_DOCKER_TEXT, panel.recheckDockerButton.getText());

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
