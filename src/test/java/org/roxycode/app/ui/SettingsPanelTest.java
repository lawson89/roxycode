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
import org.roxycode.app.model.ThinkingLevel;
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

        assertTrue(settingsPanel.onboardingBannerPanel.isVisible());
        assertEquals(SettingsPanel.ONBOARDING_API_KEY_NOTICE_TEXT,
                settingsPanel.onboardingBannerMessageLabel.getText());
        java.util.concurrent.atomic.AtomicInteger initialCallbackCount = new java.util.concurrent.atomic.AtomicInteger();
        settingsPanel.setOnInitialApiKeyConfiguredCallback(initialCallbackCount::incrementAndGet);

        JSpinner.DefaultEditor turnsEditor = (JSpinner.DefaultEditor) settingsPanel.maxAgentToolTurnsSpinner
                .getEditor();

        assertEquals(ThinkingLevel.DEFAULT, settingsPanel.thinkingLevelCombo.getSelectedItem());
        settingsPanel.thinkingLevelCombo.setSelectedItem(ThinkingLevel.HIGH);
        turnsEditor.getTextField().setText("85");
        settingsPanel.logToolCallsCheckBox.setSelected(true);
        settingsPanel.geminiApiKeyField.setText("new-secret-key");
        settingsPanel.saveButton.doClick();

        verify(settingsService, times(1)).saveSettings();
        assertEquals("new-secret-key", settings.getGeminiApiKey());
        assertEquals("HIGH", settings.getThinkingLevel());
        assertEquals(85, settings.getMaxAgentToolTurns());
        assertTrue(settings.isLogToolCalls());
        assertFalse(settingsPanel.onboardingBannerPanel.isVisible());
        assertEquals(1, initialCallbackCount.get());

        AppSettings copied = settings.copy();
        assertEquals("new-secret-key", copied.getGeminiApiKey());
        assertEquals(85, copied.getMaxAgentToolTurns());
        assertTrue(copied.isLogToolCalls());
        assertEquals(SettingsPanel.SAVED_APPLIED_TEXT, settingsPanel.statusLabel.getText());

        settingsPanel.maxMessagesSpinner.setValue(120);
        settingsPanel.saveButton.doClick();
        assertEquals(120, settings.getMaxChatMemoryMessages());
        assertEquals(SettingsPanel.SAVED_APPLIED_TEXT, settingsPanel.statusLabel.getText());
        assertEquals(1, initialCallbackCount.get(),
                "Callback should not fire again when API key was already configured");

        settings.setGeminiApiKey("synced-from-event-key");
        settings.setGeminiModel(modelCombo.getItemAt(1).apiName());
        settings.setThinkingLevel("LOW");
        settings.setMaxAgentToolTurns(100);
        settings.setMaxChatMemoryMessages(250);
        settings.setLogToolCalls(false);
        settingsPanel.onSettingsChanged(new SettingsChangedEvent(settings));
        SwingTestUtils.flushEdt();
        assertEquals("synced-from-event-key", new String(settingsPanel.geminiApiKeyField.getPassword()));
        assertEquals(modelCombo.getItemAt(1).apiName(), ((GeminiModelConfig) modelCombo.getSelectedItem()).apiName());
        assertEquals(ThinkingLevel.LOW, settingsPanel.thinkingLevelCombo.getSelectedItem());
        assertEquals(100, settingsPanel.maxAgentToolTurnsSpinner.getValue());
        assertEquals(250, settingsPanel.maxMessagesSpinner.getValue());
        assertFalse(settingsPanel.logToolCallsCheckBox.isSelected());

        for (ActionListener listener : settingsPanel.statusTimer.getActionListeners()) {
            listener.actionPerformed(null);
        }
        assertEquals("", settingsPanel.statusLabel.getText());
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
