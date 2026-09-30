package org.roxycode.app.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.SettingsService;

import javax.swing.*;
import java.awt.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettingsPanelTest {

    @Mock
    private SettingsService settingsService;

    private SettingsPanel settingsPanel;
    private AppSettings settings;

    @BeforeEach
    void setUp() {
        settings = new AppSettings();
        when(settingsService.getSettings()).thenReturn(settings);
        
        // Create panel in EDT or just normally for logic test
        settingsPanel = new SettingsPanel(settingsService);
    }

    @Test
    void testSaveSettingsCallsService() {
        JButton saveButton = findButton(settingsPanel, "Save Settings");
        assertNotNull(saveButton, "Save button should exist");
        
        saveButton.doClick();

        verify(settingsService, times(1)).saveSettings();
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
    void testGeminiApiKeyFieldUpdatesSettingsOnSave() {
        JPasswordField apiKeyField = findPasswordField(settingsPanel);
        assertNotNull(apiKeyField, "API key field should exist");

        apiKeyField.setText("new-secret-key");
        
        JButton saveButton = findButton(settingsPanel, "Save Settings");
        saveButton.doClick();

        assertEquals("new-secret-key", settings.getGeminiApiKey());
        verify(settingsService, times(1)).saveSettings();
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