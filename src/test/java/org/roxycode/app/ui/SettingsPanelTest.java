package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.event.ActionListener;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.GeminiModelConfig;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.SettingsService;

@ExtendWith(MockitoExtension.class)
class SettingsPanelTest {

    @Mock
    private SettingsService settingsService;
    @Mock
    private AiService aiService;
    private SettingsPanel settingsPanel;
    private AppSettings settings;

    @BeforeEach
    void setUp() {
        settings = new AppSettings();
        lenient().when(settingsService.getSettings()).thenReturn(settings);
        lenient().when(settingsService.getAvailableModels())
                .thenReturn(new SettingsService(null, null).getAvailableModels());
        settingsPanel = new SettingsPanel(settingsService, aiService);
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
        assertEquals(50, settings.getMaxAgentToolTurns());
        assertEquals(50, settingsPanel.getMaxAgentToolTurnsSpinner().getValue());
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
    void testApiKeyHotReloadShowsAppliedWhileMaxMessagesShowsRestartWarning() {
        JPasswordField apiKeyField = SwingTestUtils.findComponent(settingsPanel, JPasswordField.class);
        assertNotNull(apiKeyField);
        apiKeyField.setText("new-secret-key");

        JButton saveButton = SwingTestUtils.findButton(settingsPanel, "Save Settings");
        saveButton.doClick();

        assertEquals("new-secret-key", settings.getGeminiApiKey());
        assertEquals(SettingsPanel.SAVED_APPLIED_TEXT, settingsPanel.getStatusLabel().getText());
        verify(settingsService, times(1)).saveSettings();

        settingsPanel.getMaxMessagesSpinner().setValue(120);
        saveButton.doClick();
        assertEquals(120, settings.getMaxChatMemoryMessages());
        assertEquals(SettingsPanel.SAVED_RESTART_REQUIRED_TEXT, settingsPanel.getStatusLabel().getText());

        saveButton.doClick();
        assertEquals(SettingsPanel.SAVED_APPLIED_TEXT, settingsPanel.getStatusLabel().getText());
    }

    @Test
    void testGetApiKeyLinkAndTestConnectionButtons() {
        AtomicReference<String> openedUrl = new AtomicReference<>();
        settingsPanel.setUrlOpener(openedUrl::set);
        SwingTestUtils.clickOnEdt(settingsPanel.getGetApiKeyLinkButton());
        assertEquals(SettingsPanel.GEMINI_API_KEY_URL, openedUrl.get());

        // Blank API key fails validation immediately without calling AiService
        JPasswordField apiKeyField = SwingTestUtils.findComponent(settingsPanel, JPasswordField.class);
        apiKeyField.setText("   ");
        SwingTestUtils.clickOnEdt(settingsPanel.getTestConnectionButton());
        assertTrue(settingsPanel.getStatusLabel().getText().contains("Please enter a Gemini API Key first."));
        verifyNoInteractions(aiService);

        // Successful connection test
        CompletableFuture<String> gate = new CompletableFuture<>();
        when(aiService.testConnection(eq("valid-key"), anyString())).thenAnswer(inv -> gate.join());
        apiKeyField.setText("valid-key");
        SwingTestUtils.runOnEdt(() -> settingsPanel.getTestConnectionButton().doClick());
        SwingTestUtils.runOnEdt(() -> {
            assertFalse(settingsPanel.getTestConnectionButton().isEnabled());
            assertEquals(SettingsPanel.TESTING_CONNECTION_TEXT, settingsPanel.getTestConnectionButton().getText());
        });

        gate.complete("OK");
        SwingTestUtils.awaitFuture(settingsPanel.lastAsyncFuture);
        assertTrue(settingsPanel.getTestConnectionButton().isEnabled());
        assertEquals(SettingsPanel.TEST_CONNECTION_TEXT, settingsPanel.getTestConnectionButton().getText());
        assertEquals(SettingsPanel.CONNECTION_SUCCESS_TEXT, settingsPanel.getStatusLabel().getText());

        // Failed connection test shows root cause error
        when(aiService.testConnection(eq("invalid-key"), anyString()))
                .thenThrow(new IllegalStateException("401 UNAUTHORIZED: API key not valid"));
        apiKeyField.setText("invalid-key");
        SwingTestUtils.clickOnEdt(settingsPanel.getTestConnectionButton());
        SwingTestUtils.awaitFuture(settingsPanel.lastAsyncFuture);
        assertTrue(settingsPanel.getTestConnectionButton().isEnabled());
        assertEquals(SettingsPanel.CONNECTION_FAILED_PREFIX + "401 UNAUTHORIZED: API key not valid",
                settingsPanel.getStatusLabel().getText());
    }
}
