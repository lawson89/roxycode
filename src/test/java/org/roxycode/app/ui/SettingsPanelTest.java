package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.*;
import java.awt.event.ActionListener;
import javax.swing.*;
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

        JButton saveButton = findButton(settingsPanel, "Save Settings");
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
    void testThemeSelectionUpdatesSettingsButDoesNotSave() {
        JComboBox<String> themeCombo = findCombo(settingsPanel);
        assertNotNull(themeCombo, "Theme combo should exist");

        themeCombo.setSelectedItem("FlatLaf Dark");

        assertEquals("FlatLaf Dark", settings.getTheme());
        verify(settingsService, never()).saveSettings();
    }

    @Test
    void testRestartRequiredSettingsShowRestartWarningOnSave() {
        JPasswordField apiKeyField = findPasswordField(settingsPanel);
        assertNotNull(apiKeyField, "API key field should exist");

        // Case 1: Changing Gemini API Key triggers restart warning
        apiKeyField.setText("new-secret-key");

        JButton saveButton = findButton(settingsPanel, "Save Settings");
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

    private JButton findButton(Container container, String text) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JButton && text.equals(((JButton) comp).getText())) {
                return (JButton) comp;
            }
            if (comp instanceof Container) {
                JButton res = findButton((Container) comp, text);
                if (res != null) {
                    return res;
                }
            }
        }
        return null;
    }

    private JPasswordField findPasswordField(Container container) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JPasswordField) {
                return (JPasswordField) comp;
            }
            if (comp instanceof Container) {
                JPasswordField res = findPasswordField((Container) comp);
                if (res != null) {
                    return res;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private JComboBox<String> findCombo(Container container) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JComboBox) {
                return (JComboBox<String>) comp;
            }
            if (comp instanceof Container) {
                JComboBox<String> res = findCombo((Container) comp);
                if (res != null) {
                    return res;
                }
            }
        }
        return null;
    }
}
