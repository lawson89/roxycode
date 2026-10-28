package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.*;
import java.awt.event.ActionListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.*;
import javax.swing.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.RoxyCode;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.SettingsService;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

@ExtendWith(MockitoExtension.class)
class SettingsPanelTest {

    @Mock
    private SettingsService settingsService;
    private SettingsPanel settingsPanel;
    private AppSettings settings;

    @BeforeEach
    void setUp() {
        settings = new AppSettings();
        lenient().when(settingsService.getSettings()).thenReturn(settings);
        lenient().when(settingsService.getAvailableModels())
                .thenReturn(new SettingsService(null, null).getAvailableModels());
        settingsPanel = new SettingsPanel(settingsService);
    }

    @Test
    void testSaveLiveSettingsShowsAppliedAndPreservesRestartLegendAfterTimer() {
        assertEquals(SettingsPanel.RESTART_LEGEND_TEXT, settingsPanel.getLegendLabel().getText());
        assertEquals("", settingsPanel.getStatusLabel().getText());

        settingsPanel.getMaxAgentToolTurnsSpinner().setValue(12);
        JButton saveButton = SwingTestUtils.findButton(settingsPanel, "Save Settings");
        assertNotNull(saveButton);
        saveButton.doClick();

        verify(settingsService, times(1)).saveSettings();
        assertEquals(12, settings.getMaxAgentToolTurns());
        assertEquals(SettingsPanel.SAVED_APPLIED_TEXT, settingsPanel.getStatusLabel().getText());

        for (ActionListener listener : settingsPanel.getStatusTimer().getActionListeners()) {
            listener.actionPerformed(null);
        }
        assertEquals("", settingsPanel.getStatusLabel().getText());
        assertEquals(SettingsPanel.RESTART_LEGEND_TEXT, settingsPanel.getLegendLabel().getText());
    }

    @Test
    void testThemeSelectionUpdatesSettingsButDoesNotSave() {
        try {
            @SuppressWarnings("unchecked")
            JComboBox<String> themeCombo = SwingTestUtils.findComponent(settingsPanel, JComboBox.class);
            assertNotNull(themeCombo);

            themeCombo.setSelectedItem("FlatLaf Dark");
            assertEquals("FlatLaf Dark", settings.getTheme());
            verify(settingsService, never()).saveSettings();

            settings.setTheme("FlatLaf Light");
            settingsPanel.onSettingsChanged(new SettingsChangedEvent(settings));
            SwingTestUtils.flushEdt();
            assertEquals("FlatLaf Light", themeCombo.getSelectedItem());
        } finally {
            SettingsPanel.applyTheme(AppSettings.DEFAULT_THEME);
        }
    }

    @Test
    void testApplyThemeOffEdtWithIndeterminateProgressBarDoesNotLogSevereError() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Skipping JDialog test in headless environment");
        Logger flatLafLogger = Logger.getLogger("com.formdev.flatlaf");
        List<LogRecord> severeRecords = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record != null && record.getLevel().intValue() >= Level.SEVERE.intValue()) {
                    severeRecords.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };

        flatLafLogger.addHandler(handler);
        final JDialog[] dialogHolder = new JDialog[1];
        try {
            SwingTestUtils.runOnEdt(() -> {
                JDialog dialog = new JDialog();
                JProgressBar progressBar = new JProgressBar();
                progressBar.setIndeterminate(true);
                dialog.add(progressBar);
                dialogHolder[0] = dialog;
            });

            assertFalse(SwingUtilities.isEventDispatchThread());
            SettingsPanel.applyTheme("FlatLaf Dark");
            SettingsPanel.applyTheme(AppSettings.DEFAULT_THEME);
            assertTrue(severeRecords.isEmpty());
        } finally {
            flatLafLogger.removeHandler(handler);
            if (dialogHolder[0] != null) {
                SwingTestUtils.runOnEdt(() -> {
                    for (Component c : dialogHolder[0].getContentPane().getComponents()) {
                        if (c instanceof JProgressBar pb)
                            pb.setIndeterminate(false);
                    }
                    dialogHolder[0].dispose();
                });
            }
            SettingsPanel.applyTheme(AppSettings.DEFAULT_THEME);
        }
    }

    @Test
    void testRestartRequiredSettingsShowRestartWarningOnSave() {
        JPasswordField apiKeyField = SwingTestUtils.findComponent(settingsPanel, JPasswordField.class);
        assertNotNull(apiKeyField);
        apiKeyField.setText("new-secret-key");

        JButton saveButton = SwingTestUtils.findButton(settingsPanel, "Save Settings");
        saveButton.doClick();

        assertEquals("new-secret-key", settings.getGeminiApiKey());
        assertEquals(SettingsPanel.SAVED_RESTART_REQUIRED_TEXT, settingsPanel.getStatusLabel().getText());
        verify(settingsService, times(1)).saveSettings();

        saveButton.doClick();
        assertEquals(SettingsPanel.SAVED_APPLIED_TEXT, settingsPanel.getStatusLabel().getText());

        settingsPanel.getMaxMessagesSpinner().setValue(120);
        saveButton.doClick();
        assertEquals(120, settings.getMaxChatMemoryMessages());
        assertEquals(SettingsPanel.SAVED_RESTART_REQUIRED_TEXT, settingsPanel.getStatusLabel().getText());
    }

    @Test
    void testRoxyCodeSettingsInitializerPopulatesEnvironmentWithoutSystemProperties() {
        AppSettings customSettings = new AppSettings();
        customSettings.setGeminiApiKey("custom-api-key-999");
        customSettings.setGeminiModel("gemini-3.8-flash");

        try (GenericApplicationContext ctx = new GenericApplicationContext()) {
            RoxyCode.createSettingsInitializer(customSettings).initialize(ctx);
            ConfigurableEnvironment env = ctx.getEnvironment();
            assertEquals("custom-api-key-999", env.getProperty("spring.ai.google.genai.api-key"));
            assertEquals("gemini-3.8-flash", env.getProperty("spring.ai.google.genai.chat.options.model"));
        }

        AppSettings emptySettings = new AppSettings();
        emptySettings.setGeminiApiKey("");
        emptySettings.setGeminiModel("");

        try (GenericApplicationContext ctx = new GenericApplicationContext()) {
            RoxyCode.createSettingsInitializer(emptySettings).initialize(ctx);
            ConfigurableEnvironment env = ctx.getEnvironment();
            assertNull(env.getPropertySources().get("roxycodeSettingsProperties")
                    .getProperty("spring.ai.google.genai.api-key"));
            assertEquals("gemini-3.5-flash", env.getProperty("spring.ai.google.genai.chat.options.model"));
        }
    }

    @Test
    void testGetRSyntaxThemeCachesLightAndDarkInstances() throws Exception {
        var light1 = SettingsPanel.getRSyntaxTheme(false);
        var dark1 = SettingsPanel.getRSyntaxTheme(true);
        assertNotNull(light1);
        assertNotNull(dark1);
        assertSame(light1, SettingsPanel.getRSyntaxTheme(false));
        assertSame(dark1, SettingsPanel.getRSyntaxTheme(true));
        assertNotSame(light1, dark1);
    }
}
