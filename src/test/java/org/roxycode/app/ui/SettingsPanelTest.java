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
    void testModelThemeAndLiveSettingsPersistence() {
        JComboBox<GeminiModelConfig> modelCombo = settingsPanel.modelCombo;
        assertTrue(modelCombo.getItemCount() > 1);

        GeminiModelConfig firstModel = modelCombo.getItemAt(0);
        modelCombo.setSelectedIndex(0);
        assertEquals(firstModel.apiName(), settingsPanel.modelApiBadgeLabel.getText());
        assertEquals(firstModel.description(), settingsPanel.modelDescriptionArea.getText());

        try {
            settingsPanel.themeCombo.setSelectedItem("FlatLaf Dark");
            assertEquals("FlatLaf Dark", settings.getTheme());
            verify(settingsService, never()).saveSettings();
        } finally {
            SettingsPanel.applyTheme(AppSettings.DEFAULT_THEME);
        }

        JSpinner.DefaultEditor turnsEditor = (JSpinner.DefaultEditor) settingsPanel.maxAgentToolTurnsSpinner
                .getEditor();
        assertEquals(AppSettings.DEFAULT_STT_MODEL, settingsPanel.sttModelField.getText());
        assertEquals(AppSettings.DEFAULT_TTS_MODEL, settingsPanel.ttsModelField.getText());

        turnsEditor.getTextField().setText("85");
        settingsPanel.logToolCallsCheckBox.setSelected(true);
        settingsPanel.sttModelField.setText(" custom-stt-model ");
        settingsPanel.ttsModelField.setText(" custom-tts-model ");
        settingsPanel.ttsVoiceCombo.setSelectedItem("Aoede");
        settingsPanel.autoSpeakCheckBox.setSelected(true);
        settingsPanel.geminiApiKeyField.setText("new-secret-key");
        settingsPanel.saveButton.doClick();

        verify(settingsService, times(1)).saveSettings();
        assertEquals("new-secret-key", settings.getGeminiApiKey());
        assertEquals(85, settings.getMaxAgentToolTurns());
        assertTrue(settings.isLogToolCalls());
        assertEquals("custom-stt-model", settings.getSttModel());
        assertEquals("custom-tts-model", settings.getTtsModel());
        assertEquals("Aoede", settings.getTtsVoice());
        assertTrue(settings.isAutoSpeakResponses());

        AppSettings copied = settings.copy();
        assertEquals("custom-stt-model", copied.getSttModel());
        assertEquals("custom-tts-model", copied.getTtsModel());
        assertEquals("Aoede", copied.getTtsVoice());
        assertTrue(copied.isAutoSpeakResponses());
        assertEquals(SettingsPanel.SAVED_APPLIED_TEXT, settingsPanel.statusLabel.getText());

        settingsPanel.maxMessagesSpinner.setValue(120);
        settingsPanel.saveButton.doClick();
        assertEquals(120, settings.getMaxChatMemoryMessages());
        assertEquals(SettingsPanel.SAVED_RESTART_REQUIRED_TEXT, settingsPanel.statusLabel.getText());

        settings.setGeminiModel(modelCombo.getItemAt(1).apiName());
        settings.setMaxAgentToolTurns(100);
        settings.setMaxChatMemoryMessages(250);
        settings.setLogToolCalls(false);
        settings.setSttModel("gemini-3.5-transcribe-v2");
        settings.setTtsModel("gemini-3.8-flash-tts");
        settings.setTtsVoice("Fenrir");
        settings.setAutoSpeakResponses(false);
        settingsPanel.onSettingsChanged(new SettingsChangedEvent(settings));
        SwingTestUtils.flushEdt();
        assertEquals(modelCombo.getItemAt(1).apiName(), ((GeminiModelConfig) modelCombo.getSelectedItem()).apiName());
        assertEquals(100, settingsPanel.maxAgentToolTurnsSpinner.getValue());
        assertEquals(250, settingsPanel.maxMessagesSpinner.getValue());
        assertFalse(settingsPanel.logToolCallsCheckBox.isSelected());
        assertEquals("gemini-3.5-transcribe-v2", settingsPanel.sttModelField.getText());
        assertEquals("gemini-3.8-flash-tts", settingsPanel.ttsModelField.getText());
        assertEquals("Fenrir", settingsPanel.ttsVoiceCombo.getSelectedItem());
        assertFalse(settingsPanel.autoSpeakCheckBox.isSelected());

        for (ActionListener listener : settingsPanel.statusTimer.getActionListeners()) {
            listener.actionPerformed(null);
        }
        assertEquals("", settingsPanel.statusLabel.getText());
        assertEquals(SettingsPanel.RESTART_LEGEND_TEXT, settingsPanel.legendLabel.getText());
    }

    @Test
    void testGetApiKeyLinkAndTestConnectionButtons() {
        AtomicReference<String> openedUrl = new AtomicReference<>();
        settingsPanel.setUrlOpener(openedUrl::set);
        SwingTestUtils.clickOnEdt(settingsPanel.getApiKeyLinkButton);
        assertEquals(SettingsPanel.GEMINI_API_KEY_URL, openedUrl.get());

        settingsPanel.geminiApiKeyField.setText("   ");
        SwingTestUtils.clickOnEdt(settingsPanel.testConnectionButton);
        assertTrue(settingsPanel.statusLabel.getText().contains("Please enter a Gemini API Key first."));
        verifyNoInteractions(aiService);

        CompletableFuture<String> gate = new CompletableFuture<>();
        when(aiService.testConnection(eq("valid-key"), anyString())).thenAnswer(inv -> gate.join());
        settingsPanel.geminiApiKeyField.setText("valid-key");
        SwingTestUtils.runOnEdt(settingsPanel.testConnectionButton::doClick);
        SwingTestUtils.runOnEdt(() -> {
            assertFalse(settingsPanel.testConnectionButton.isEnabled());
            assertEquals(SettingsPanel.TESTING_CONNECTION_TEXT, settingsPanel.testConnectionButton.getText());
        });

        gate.complete("OK");
        SwingTestUtils.awaitFuture(settingsPanel.lastAsyncFuture);
        assertTrue(settingsPanel.testConnectionButton.isEnabled());
        assertEquals(SettingsPanel.CONNECTION_SUCCESS_TEXT, settingsPanel.statusLabel.getText());

        when(aiService.testConnection(eq("invalid-key"), anyString()))
                .thenThrow(new IllegalStateException("401 UNAUTHORIZED: API key not valid"));
        settingsPanel.geminiApiKeyField.setText("invalid-key");
        SwingTestUtils.clickOnEdt(settingsPanel.testConnectionButton);
        SwingTestUtils.awaitFuture(settingsPanel.lastAsyncFuture);
        assertEquals(SettingsPanel.CONNECTION_FAILED_PREFIX + "401 UNAUTHORIZED: API key not valid",
                settingsPanel.statusLabel.getText());
    }
}
