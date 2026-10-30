package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.event.ActionListener;
import javax.swing.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.GeminiModelConfig;
import org.roxycode.app.service.SettingsService;

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
    void testModelSelectionUpdatesApiBadgeAndDescription() {
        JComboBox<GeminiModelConfig> modelCombo = settingsPanel.getModelCombo();
        assertTrue(modelCombo.getItemCount() > 1);

        GeminiModelConfig initial = (GeminiModelConfig) modelCombo.getSelectedItem();
        assertNotNull(initial);
        assertEquals(initial.apiName(), settingsPanel.getModelApiBadgeLabel().getText());
        assertEquals(initial.description(), settingsPanel.getModelDescriptionArea().getText());

        GeminiModelConfig firstModel = modelCombo.getItemAt(0);
        modelCombo.setSelectedIndex(0);
        assertEquals(firstModel.apiName(), settingsPanel.getModelApiBadgeLabel().getText());
        assertEquals(firstModel.description(), settingsPanel.getModelDescriptionArea().getText());
        assertTrue(settingsPanel.getModelApiBadgeLabel().isVisible());
        assertTrue(settingsPanel.getModelDescriptionArea().isVisible());

        settings.setGeminiModel(modelCombo.getItemAt(1).apiName());
        settingsPanel.onSettingsChanged(new SettingsChangedEvent(settings));
        SwingTestUtils.flushEdt();
        assertEquals(modelCombo.getItemAt(1).apiName(), ((GeminiModelConfig) modelCombo.getSelectedItem()).apiName());
    }

    @Test
    void testSaveLiveSettingsShowsAppliedAndPreservesRestartLegendAfterTimer() {
        assertEquals(SettingsPanel.RESTART_LEGEND_TEXT, settingsPanel.getLegendLabel().getText());
        assertEquals("", settingsPanel.getStatusLabel().getText());
        assertFalse(settingsPanel.getLogToolCallsCheckBox().isSelected());

        SpinnerNumberModel turnsModel = (SpinnerNumberModel) settingsPanel.getMaxAgentToolTurnsSpinner().getModel();
        assertEquals(1, turnsModel.getMinimum());
        assertEquals(100, turnsModel.getMaximum());

        JSpinner.DefaultEditor turnsEditor = (JSpinner.DefaultEditor) settingsPanel.getMaxAgentToolTurnsSpinner()
                .getEditor();
        turnsEditor.getTextField().setText("85");
        settingsPanel.getLogToolCallsCheckBox().setSelected(true);
        JButton saveButton = SwingTestUtils.findButton(settingsPanel, "Save Settings");
        assertNotNull(saveButton);
        saveButton.doClick();

        verify(settingsService, times(1)).saveSettings();
        assertEquals(85, settings.getMaxAgentToolTurns());
        assertEquals(85, settingsPanel.getMaxAgentToolTurnsSpinner().getValue());
        assertTrue(settings.isLogToolCalls());
        assertEquals(SettingsPanel.SAVED_APPLIED_TEXT, settingsPanel.getStatusLabel().getText());

        settings.setMaxAgentToolTurns(100);
        settings.setMaxChatMemoryMessages(250);
        settings.setLogToolCalls(false);
        settingsPanel.onSettingsChanged(new SettingsChangedEvent(settings));
        SwingTestUtils.flushEdt();
        assertEquals(100, settingsPanel.getMaxAgentToolTurnsSpinner().getValue());
        assertEquals(250, settingsPanel.getMaxMessagesSpinner().getValue());
        assertFalse(settingsPanel.getLogToolCallsCheckBox().isSelected());

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
}
