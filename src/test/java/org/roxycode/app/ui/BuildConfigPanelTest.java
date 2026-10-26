package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
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
                        """
                        [build]
                        compile = './mvnw compile'

                        [container]
                        enabled = false
                        image = 'eclipse-temurin:25-jdk'
                        workdir = '/workspace'
                        """);
    }

    @Test
    void testCachedDockerAvailabilityOnInitializationWithoutReprobe() throws Exception {
        when(systemToolService.hasCachedTools()).thenReturn(true);
        when(systemToolService.checkDockerAvailable()).thenReturn(true);

        BuildConfigPanel panel =
                new BuildConfigPanel(buildToolService, settingsService, systemToolService);
        SwingUtilities.invokeAndWait(() -> {});

        assertTrue(panel.isDockerAvailableState());
        assertEquals(
                BuildConfigPanel.HOST_EXECUTION_ACTIVE_TEXT,
                panel.getBannerMessageLabel().getText());
        verify(systemToolService, never()).refreshDockerAvailable();
        verify(systemToolService, never()).refreshTools();
    }

    @Test
    void testWarningBannerWhenDockerUnavailable() throws Exception {
        when(systemToolService.isDockerAvailable())
                .thenReturn(CompletableFuture.completedFuture(false));

        BuildConfigPanel panel =
                new BuildConfigPanel(buildToolService, settingsService, systemToolService);
        SwingUtilities.invokeAndWait(() -> {});

        assertFalse(panel.isDockerAvailableState());
        assertEquals(
                BuildConfigPanel.DOCKER_MISSING_WARNING_TEXT,
                panel.getBannerMessageLabel().getText());
        assertFalse(
                panel.getContainerToggleCheckBox().isEnabled(),
                "Container toggle should be disabled when Docker is unavailable");
        assertFalse(panel.getContainerToggleCheckBox().isSelected());
    }

    @Test
    void testDockerAvailableAndToggleCheckboxUpdatesTomlAndBanner() throws Exception {
        when(systemToolService.isDockerAvailable())
                .thenReturn(CompletableFuture.completedFuture(true));

        BuildConfigPanel panel =
                new BuildConfigPanel(buildToolService, settingsService, systemToolService);
        SwingUtilities.invokeAndWait(() -> {});

        assertTrue(panel.isDockerAvailableState());
        assertEquals(
                BuildConfigPanel.HOST_EXECUTION_ACTIVE_TEXT,
                panel.getBannerMessageLabel().getText());
        assertTrue(panel.getContainerToggleCheckBox().isEnabled());
        assertFalse(panel.getContainerToggleCheckBox().isSelected());

        // Toggle checkbox on -> updates TOML and banner to CONTAINER_ACTIVE_TEXT
        panel.getContainerToggleCheckBox().doClick();
        SwingUtilities.invokeAndWait(() -> {});

        assertTrue(panel.getContainerToggleCheckBox().isSelected());
        assertTrue(BuildContainerService.isContainerEnabledInToml(panel.getTextArea().getText()));
        assertEquals(
                BuildConfigPanel.CONTAINER_ACTIVE_TEXT, panel.getBannerMessageLabel().getText());

        // Toggle checkbox off -> updates TOML and banner back to HOST_EXECUTION_ACTIVE_TEXT
        panel.getContainerToggleCheckBox().doClick();
        SwingUtilities.invokeAndWait(() -> {});

        assertFalse(panel.getContainerToggleCheckBox().isSelected());
        assertFalse(BuildContainerService.isContainerEnabledInToml(panel.getTextArea().getText()));
        assertEquals(
                BuildConfigPanel.HOST_EXECUTION_ACTIVE_TEXT,
                panel.getBannerMessageLabel().getText());
    }

    @Test
    void testRecheckDockerButtonUpdatesAvailabilityAndAppendsContainerSectionIfMissing()
            throws Exception {
        when(buildToolService.getConfigContent())
                .thenReturn(
                        """
                        [build]
                        compile = 'mvn'""");
        when(systemToolService.hasCachedTools()).thenReturn(true);
        when(systemToolService.checkDockerAvailable()).thenReturn(false);

        BuildConfigPanel panel =
                new BuildConfigPanel(buildToolService, settingsService, systemToolService);
        SwingUtilities.invokeAndWait(() -> {});

        assertFalse(panel.getContainerToggleCheckBox().isEnabled());

        // Simulate user starting Docker Desktop and clicking "Re-check Docker" with async future
        CompletableFuture<Boolean> pendingRecheck = new CompletableFuture<>();
        when(systemToolService.refreshDockerAvailable()).thenReturn(pendingRecheck);

        SwingUtilities.invokeAndWait(() -> panel.getRecheckDockerButton().doClick());

        assertFalse(
                panel.getRecheckDockerButton().isEnabled(),
                "Re-check Docker button should be disabled while check is in flight");
        assertEquals(
                BuildConfigPanel.CHECKING_DOCKER_TEXT, panel.getRecheckDockerButton().getText());

        pendingRecheck.complete(true);
        SwingUtilities.invokeAndWait(() -> {});

        assertTrue(panel.getRecheckDockerButton().isEnabled());
        assertEquals(
                BuildConfigPanel.RECHECK_DOCKER_TEXT, panel.getRecheckDockerButton().getText());
        assertTrue(panel.getContainerToggleCheckBox().isEnabled());

        // Clicking toggle appends [container] section when missing
        panel.getContainerToggleCheckBox().doClick();
        assertTrue(panel.getTextArea().getText().contains("[container]"));
        assertTrue(BuildContainerService.isContainerEnabledInToml(panel.getTextArea().getText()));
    }

    @Test
    void testOnSystemToolsUpdatedEventSynchronizesDockerAvailability() throws Exception {
        when(systemToolService.hasCachedTools()).thenReturn(true);
        when(systemToolService.checkDockerAvailable()).thenReturn(false);

        BuildConfigPanel panel =
                new BuildConfigPanel(buildToolService, settingsService, systemToolService);
        SwingUtilities.invokeAndWait(() -> {});
        assertFalse(panel.isDockerAvailableState());

        panel.onSystemToolsUpdated(
                new SystemToolsUpdatedEvent(
                        List.of(new SystemTool("Docker", "28.0", "Detected", "Desc")), true));
        SwingUtilities.invokeAndWait(() -> {});

        assertTrue(panel.isDockerAvailableState());
        assertTrue(panel.getContainerToggleCheckBox().isEnabled());
    }

    @Test
    void testFlatLafStyleParsingAcrossAllBannerStatesAndThemes() throws Exception {
        javax.swing.LookAndFeel previousLaf = javax.swing.UIManager.getLookAndFeel();
        try {
            com.formdev.flatlaf.FlatLightLaf.setup();
            when(systemToolService.isDockerAvailable())
                    .thenReturn(CompletableFuture.completedFuture(false));

            BuildConfigPanel panel =
                    assertDoesNotThrow(
                            () ->
                                    new BuildConfigPanel(
                                            buildToolService, settingsService, systemToolService));
            SwingUtilities.invokeAndWait(() -> {});
            assertNotNull(panel.getBannerPanel().getBorder());

            // Transition to Docker available (host execution)
            panel.setDockerAvailable(true);
            assertNotNull(panel.getBannerPanel().getBorder());

            // Transition to container enabled
            panel.setContainerEnabledInToml(true);
            assertNotNull(panel.getBannerPanel().getBorder());

            // Switch to FlatDarkLaf and verify updateUI() and all states succeed
            SwingUtilities.invokeAndWait(
                    () -> {
                        com.formdev.flatlaf.FlatDarkLaf.setup();
                        SwingUtilities.updateComponentTreeUI(panel);
                    });
            assertNotNull(panel.getBannerPanel().getBorder());

            panel.setDockerAvailable(false);
            assertNotNull(panel.getBannerPanel().getBorder());
        } finally {
            if (previousLaf != null) {
                SwingUtilities.invokeAndWait(
                        () -> {
                            try {
                                javax.swing.UIManager.setLookAndFeel(previousLaf);
                            } catch (Exception ignored) {
                            }
                        });
            }
        }
    }
}
