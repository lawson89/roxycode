package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.*;
import java.awt.event.ActionListener;
import javax.swing.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.RoxyCode;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.SettingsService;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

@ExtendWith(MockitoExtension.class)
class SettingsPanelTest {

    @Mock private SettingsService settingsService;

    private SettingsPanel settingsPanel;
    private AppSettings settings;

    @BeforeEach
    void setUp() {
        settings = new AppSettings();
        lenient().when(settingsService.getSettings()).thenReturn(settings);
        lenient()
                .when(settingsService.getAvailableModels())
                .thenReturn(new SettingsService(null, null).getAvailableModels());

        settingsPanel = new SettingsPanel(settingsService);
    }

    @Test
    void testSaveLiveSettingsShowsAppliedAndPreservesRestartLegendAfterTimer() {
        assertEquals(
                SettingsPanel.RESTART_LEGEND_TEXT,
                settingsPanel.getLegendLabel().getText(),
                "Permanent restart legend should be displayed initially");
        assertEquals(
                "",
                settingsPanel.getStatusLabel().getText(),
                "Transient status label should be empty initially");

        // Change a live setting (maxAgentToolTurns) without changing API key or max chat history
        settingsPanel.getMaxAgentToolTurnsSpinner().setValue(12);

        JButton saveButton = SwingTestUtils.findButton(settingsPanel, "Save Settings");
        assertNotNull(saveButton, "Save button should exist");
        saveButton.doClick();

        verify(settingsService, times(1)).saveSettings();
        assertEquals(12, settings.getMaxAgentToolTurns());
        assertEquals(
                SettingsPanel.SAVED_APPLIED_TEXT,
                settingsPanel.getStatusLabel().getText(),
                "Saving only live settings should show 'Settings saved and applied.'");

        // Simulate statusTimer firing after 3 seconds
        for (ActionListener listener : settingsPanel.getStatusTimer().getActionListeners()) {
            listener.actionPerformed(null);
        }

        assertEquals(
                "",
                settingsPanel.getStatusLabel().getText(),
                "Transient status label should clear after timer expires");
        assertEquals(
                SettingsPanel.RESTART_LEGEND_TEXT,
                settingsPanel.getLegendLabel().getText(),
                "Permanent (*) restart legend must remain visible after status timer expires");
    }

    @Test
    void testThemeSelectionUpdatesSettingsButDoesNotSave() throws Exception {
        try {
            @SuppressWarnings("unchecked")
            JComboBox<String> themeCombo =
                    SwingTestUtils.findComponent(settingsPanel, JComboBox.class);
            assertNotNull(themeCombo, "Theme combo should exist");

            themeCombo.setSelectedItem("FlatLaf Dark");

            assertEquals("FlatLaf Dark", settings.getTheme());
            verify(settingsService, never()).saveSettings();

            // Verify SettingsChangedEvent synchronizes themeCombo when changed externally (e.g.
            // HeaderPanel)
            settings.setTheme("FlatLaf Light");
            settingsPanel.onSettingsChanged(
                    new org.roxycode.app.events.SettingsChangedEvent(settings));
            SwingUtilities.invokeAndWait(() -> {});
            assertEquals("FlatLaf Light", themeCombo.getSelectedItem());
        } finally {
            SettingsPanel.applyTheme(AppSettings.DEFAULT_THEME);
        }
    }

    @Test
    void testApplyThemeOffEdtWithIndeterminateProgressBarDoesNotLogSevereError() throws Exception {
        Assumptions.assumeFalse(
                GraphicsEnvironment.isHeadless(), "Skipping JDialog test in headless environment");
        java.util.logging.Logger flatLafLogger =
                java.util.logging.Logger.getLogger("com.formdev.flatlaf");
        java.util.List<java.util.logging.LogRecord> severeRecords =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Handler handler =
                new java.util.logging.Handler() {
                    @Override
                    public void publish(java.util.logging.LogRecord record) {
                        if (record != null
                                && record.getLevel().intValue()
                                        >= java.util.logging.Level.SEVERE.intValue()) {
                            severeRecords.add(record);
                        }
                    }

                    @Override
                    public void flush() {}

                    @Override
                    public void close() {}
                };

        flatLafLogger.addHandler(handler);
        final JDialog[] dialogHolder = new JDialog[1];
        try {
            SwingUtilities.invokeAndWait(
                    () -> {
                        JDialog dialog = new JDialog();
                        JProgressBar progressBar = new JProgressBar();
                        progressBar.setIndeterminate(true);
                        dialog.add(progressBar);
                        dialogHolder[0] = dialog;
                    });

            assertFalse(SwingUtilities.isEventDispatchThread());
            SettingsPanel.applyTheme("FlatLaf Dark");
            SettingsPanel.applyTheme(AppSettings.DEFAULT_THEME);

            assertTrue(
                    severeRecords.isEmpty(),
                    "Applying theme off EDT must execute on EDT without FlatProgressBarUI SEVERE logs");
        } finally {
            flatLafLogger.removeHandler(handler);
            if (dialogHolder[0] != null) {
                SwingUtilities.invokeAndWait(
                        () -> {
                            for (Component c : dialogHolder[0].getContentPane().getComponents()) {
                                if (c instanceof JProgressBar pb) {
                                    pb.setIndeterminate(false);
                                }
                            }
                            dialogHolder[0].dispose();
                        });
            }
            SettingsPanel.applyTheme(AppSettings.DEFAULT_THEME);
        }
    }

    @Test
    void testRestartRequiredSettingsShowRestartWarningOnSave() {
        JPasswordField apiKeyField =
                SwingTestUtils.findComponent(settingsPanel, JPasswordField.class);
        assertNotNull(apiKeyField, "API key field should exist");

        // Case 1: Changing Gemini API Key triggers restart warning
        apiKeyField.setText("new-secret-key");

        JButton saveButton = SwingTestUtils.findButton(settingsPanel, "Save Settings");
        saveButton.doClick();

        assertEquals("new-secret-key", settings.getGeminiApiKey());
        assertEquals(
                SettingsPanel.SAVED_RESTART_REQUIRED_TEXT,
                settingsPanel.getStatusLabel().getText());
        verify(settingsService, times(1)).saveSettings();

        // Saving again without further changes to restart-required settings shows live applied text
        saveButton.doClick();
        assertEquals(SettingsPanel.SAVED_APPLIED_TEXT, settingsPanel.getStatusLabel().getText());

        // Case 2: Changing Max Chat History Messages triggers restart warning
        settingsPanel.getMaxMessagesSpinner().setValue(120);
        saveButton.doClick();

        assertEquals(120, settings.getMaxChatMemoryMessages());
        assertEquals(
                SettingsPanel.SAVED_RESTART_REQUIRED_TEXT,
                settingsPanel.getStatusLabel().getText());
    }

    @Test
    void testRoxyCodeSettingsInitializerPopulatesEnvironmentWithoutSystemProperties() {
        AppSettings customSettings = new AppSettings();
        customSettings.setGeminiApiKey("custom-api-key-999");
        customSettings.setGeminiModel("gemini-3.8-flash");

        try (GenericApplicationContext ctx = new GenericApplicationContext()) {
            ApplicationContextInitializer<ConfigurableApplicationContext> initializer =
                    RoxyCode.createSettingsInitializer(customSettings);
            initializer.initialize(ctx);

            ConfigurableEnvironment env = ctx.getEnvironment();
            assertEquals("custom-api-key-999", env.getProperty("spring.ai.google.genai.api-key"));
            assertEquals(
                    "gemini-3.8-flash",
                    env.getProperty("spring.ai.google.genai.chat.options.model"));
        }

        // Verify fallback when settings or model are empty
        AppSettings emptySettings = new AppSettings();
        emptySettings.setGeminiApiKey("");
        emptySettings.setGeminiModel("");

        try (GenericApplicationContext ctx = new GenericApplicationContext()) {
            RoxyCode.createSettingsInitializer(emptySettings).initialize(ctx);
            ConfigurableEnvironment env = ctx.getEnvironment();
            assertNull(
                    env.getPropertySources()
                            .get("roxycodeSettingsProperties")
                            .getProperty("spring.ai.google.genai.api-key"));
            assertEquals(
                    "gemini-3.5-flash",
                    env.getProperty("spring.ai.google.genai.chat.options.model"));
        }
    }
}
