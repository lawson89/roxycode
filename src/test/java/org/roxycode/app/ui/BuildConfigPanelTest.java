package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.formdev.flatlaf.FlatLightLaf;
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
        when(buildToolService.getConfigContent())
                .thenReturn(
                        "[build]\ncompile = './mvnw compile'\n\n[container]\nenabled = false\nimage = 'eclipse-temurin:25-jdk'\nworkdir = '/workspace'\n");
    }

    private BuildConfigPanel createPanelOnEdt() {
        BuildConfigPanel panel =
                SwingTestUtils.callOnEdt(
                        () ->
                                new BuildConfigPanel(
                                        buildToolService, settingsService, systemToolService));
        SwingTestUtils.awaitWorker(panel.getLoadConfigFuture());
        return panel;
    }

    @Test
    void testCachedDockerAvailabilityOnInitializationWithoutReprobe() {
        when(systemToolService.isDockerAvailable())
                .thenReturn(CompletableFuture.completedFuture(true));

        BuildConfigPanel panel = createPanelOnEdt();
        assertTrue(panel.isDockerAvailableState());
        assertEquals(
                BuildConfigPanel.HOST_EXECUTION_ACTIVE_TEXT,
                panel.getBannerMessageLabel().getText());
        verify(systemToolService, never()).refreshDockerAvailable();
        verify(systemToolService, never()).refreshTools();
        verify(systemToolService, never()).checkDockerAvailable();
        verify(systemToolService, never()).hasCachedTools();
    }

    @Test
    void testLoadAndSaveConfigRunOffEdtAndDisableSaveButtonDuringSave() throws Exception {
        AtomicBoolean loadCalledOnEdt = new AtomicBoolean(false);
        AtomicBoolean saveCalledOnEdt = new AtomicBoolean(false);
        when(buildToolService.getConfigContent())
                .thenAnswer(
                        inv -> {
                            loadCalledOnEdt.set(SwingUtilities.isEventDispatchThread());
                            return "[build]\ncompile = './mvnw compile'\n";
                        });
        doAnswer(
                        inv -> {
                            saveCalledOnEdt.set(SwingUtilities.isEventDispatchThread());
                            return null;
                        })
                .when(buildToolService)
                .saveConfigContent(anyString());
        when(systemToolService.isDockerAvailable())
                .thenReturn(CompletableFuture.completedFuture(false));

        BuildConfigPanel panel = createPanelOnEdt();
        assertFalse(loadCalledOnEdt.get());
        assertTrue(panel.getTextArea().getText().contains("./mvnw compile"));

        SwingTestUtils.runOnEdt(
                () -> {
                    panel.getTextArea().setText("[build]\ncompile = 'mvn test'\n");
                    panel.getSaveButton().doClick();
                });
        SwingTestUtils.awaitWorker(panel.getSaveConfigFuture());

        assertFalse(saveCalledOnEdt.get());
        assertTrue(panel.getSaveButton().isEnabled());
        assertEquals("Configuration saved successfully!", panel.getStatusLabel().getText());
        verify(buildToolService).saveConfigContent("[build]\ncompile = 'mvn test'\n");

        AtomicReference<String> errorMsg = new AtomicReference<>();
        panel.setErrorDialogPresenter(errorMsg::set);
        doThrow(new IOException("Disk full")).when(buildToolService).saveConfigContent(anyString());

        SwingTestUtils.clickOnEdt(panel.getSaveButton());
        assertThrows(Exception.class, () -> panel.getSaveConfigFuture().get(5, TimeUnit.SECONDS));
        SwingTestUtils.flushEdt();

        assertTrue(panel.getSaveButton().isEnabled());
        assertEquals("Error saving config: Disk full", errorMsg.get());
    }

    @Test
    void testWarningBannerWhenDockerUnavailable() {
        when(systemToolService.isDockerAvailable())
                .thenReturn(CompletableFuture.completedFuture(false));

        BuildConfigPanel panel = createPanelOnEdt();
        assertFalse(panel.isDockerAvailableState());
        assertEquals(
                BuildConfigPanel.DOCKER_MISSING_WARNING_TEXT,
                panel.getBannerMessageLabel().getText());
        assertFalse(panel.getContainerToggleCheckBox().isEnabled());
        assertFalse(panel.getContainerToggleCheckBox().isSelected());
    }

    @Test
    void testDockerAvailableAndToggleCheckboxUpdatesTomlAndBanner() {
        when(systemToolService.isDockerAvailable())
                .thenReturn(CompletableFuture.completedFuture(true));

        BuildConfigPanel panel = createPanelOnEdt();
        assertTrue(panel.isDockerAvailableState());
        assertEquals(
                BuildConfigPanel.HOST_EXECUTION_ACTIVE_TEXT,
                panel.getBannerMessageLabel().getText());
        assertTrue(panel.getContainerToggleCheckBox().isEnabled());
        assertFalse(panel.getContainerToggleCheckBox().isSelected());

        SwingTestUtils.clickOnEdt(panel.getContainerToggleCheckBox());
        assertTrue(panel.getContainerToggleCheckBox().isSelected());
        assertTrue(BuildContainerService.isContainerEnabledInToml(panel.getTextArea().getText()));
        assertEquals(
                BuildConfigPanel.CONTAINER_ACTIVE_TEXT, panel.getBannerMessageLabel().getText());

        SwingTestUtils.clickOnEdt(panel.getContainerToggleCheckBox());
        assertFalse(panel.getContainerToggleCheckBox().isSelected());
        assertFalse(BuildContainerService.isContainerEnabledInToml(panel.getTextArea().getText()));
        assertEquals(
                BuildConfigPanel.HOST_EXECUTION_ACTIVE_TEXT,
                panel.getBannerMessageLabel().getText());
    }

    @Test
    void testRecheckDockerButtonUpdatesAvailabilityAndAppendsContainerSectionIfMissing()
            throws Exception {
        when(buildToolService.getConfigContent()).thenReturn("[build]\ncompile = 'mvn'");
        when(systemToolService.isDockerAvailable())
                .thenReturn(CompletableFuture.completedFuture(false));

        BuildConfigPanel panel = createPanelOnEdt();
        assertFalse(panel.getContainerToggleCheckBox().isEnabled());

        CompletableFuture<Boolean> pendingRecheck = new CompletableFuture<>();
        when(systemToolService.refreshDockerAvailable()).thenReturn(pendingRecheck);

        SwingTestUtils.clickOnEdt(panel.getRecheckDockerButton());
        assertFalse(panel.getRecheckDockerButton().isEnabled());
        assertEquals(
                BuildConfigPanel.CHECKING_DOCKER_TEXT, panel.getRecheckDockerButton().getText());

        pendingRecheck.complete(true);
        SwingTestUtils.flushEdt();

        assertTrue(panel.getRecheckDockerButton().isEnabled());
        assertEquals(
                BuildConfigPanel.RECHECK_DOCKER_TEXT, panel.getRecheckDockerButton().getText());
        assertTrue(panel.getContainerToggleCheckBox().isEnabled());

        SwingTestUtils.clickOnEdt(panel.getContainerToggleCheckBox());
        assertTrue(panel.getTextArea().getText().contains("[container]"));
        assertTrue(BuildContainerService.isContainerEnabledInToml(panel.getTextArea().getText()));
    }

    @Test
    void testOnSystemToolsUpdatedEventSynchronizesDockerAvailability() {
        when(systemToolService.isDockerAvailable())
                .thenReturn(CompletableFuture.completedFuture(false));

        BuildConfigPanel panel = createPanelOnEdt();
        assertFalse(panel.isDockerAvailableState());

        panel.onSystemToolsUpdated(
                new SystemToolsUpdatedEvent(
                        List.of(new SystemTool("Docker", "28.0", "Detected", "Desc")), true));
        SwingTestUtils.flushEdt();

        assertTrue(panel.isDockerAvailableState());
        assertTrue(panel.getContainerToggleCheckBox().isEnabled());
    }

    @Test
    void testFlatLafStyleParsingAcrossAllBannerStatesAndThemes() {
        FlatLightLaf.setup();
        when(systemToolService.isDockerAvailable())
                .thenReturn(CompletableFuture.completedFuture(false));

        BuildConfigPanel panel = assertDoesNotThrow(this::createPanelOnEdt);
        assertFalse(panel.getBannerPanel().isOpaque());
        assertNotNull(panel.getBannerPanel().getBorder());

        panel.setDockerAvailable(true);
        assertNotNull(panel.getBannerPanel().getBorder());

        panel.setContainerEnabledInToml(true);
        assertNotNull(panel.getBannerPanel().getBorder());

        SwingTestUtils.withDarkTheme(
                panel,
                () -> {
                    assertNotNull(panel.getBannerPanel().getBorder());
                    panel.setDockerAvailable(false);
                    assertNotNull(panel.getBannerPanel().getBorder());
                });
    }
}
