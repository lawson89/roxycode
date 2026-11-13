package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.event.ActionListener;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.ai.AiConfig.GenAiConnectionConfig;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AgentProfile;
import org.roxycode.app.model.AppSettings;
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
        JComboBox<AgentProfile> profileCombo = settingsPanel.profileCombo;
        assertEquals(3, profileCombo.getItemCount());
        assertEquals(AgentProfile.NORMAL, profileCombo.getSelectedItem());
        assertEquals(AgentProfile.NORMAL.getBadgeSummary(), settingsPanel.modelApiBadgeLabel.getText());
        assertEquals(AgentProfile.NORMAL.getDescription(), settingsPanel.modelDescriptionArea.getText());

        profileCombo.setSelectedItem(AgentProfile.MAX);
        assertEquals(AgentProfile.MAX.getBadgeSummary(), settingsPanel.modelApiBadgeLabel.getText());
        assertEquals(AgentProfile.MAX.getDescription(), settingsPanel.modelDescriptionArea.getText());

        try {
            settingsPanel.themeCombo.setSelectedItem("FlatLaf Dark");
            assertEquals("FlatLaf Dark", settings.getTheme());
            verify(settingsService, never()).saveSettings();
        } finally {
            SettingsPanel.applyTheme(AppSettings.DEFAULT_THEME);
        }

        assertTrue(settingsPanel.onboardingBanner.isVisible());
        assertEquals(UiComponents.formatWrappingHtml(SettingsPanel.ONBOARDING_API_KEY_NOTICE_TEXT),
                settingsPanel.onboardingBanner.messageLabel.getText());
        java.util.concurrent.atomic.AtomicInteger initialCallbackCount = new java.util.concurrent.atomic.AtomicInteger();
        settingsPanel.setOnInitialApiKeyConfiguredCallback(initialCallbackCount::incrementAndGet);

        JSpinner.DefaultEditor turnsEditor = (JSpinner.DefaultEditor) settingsPanel.maxAgentToolTurnsSpinner
                .getEditor();

        assertNotNull(settingsPanel.profileInfoIcon.getToolTipText());
        turnsEditor.getTextField().setText("85");
        settingsPanel.logToolCallsCheckBox.setSelected(true);
        settingsPanel.geminiApiKeyField.setText("new-secret-key");
        settingsPanel.saveButton.doClick();

        verify(settingsService, times(1)).saveSettings();
        assertEquals("new-secret-key", settings.getGeminiApiKey());
        assertEquals("MAX", settings.getAgentProfile());
        assertEquals("ADAPTIVE", settings.getThinkingLevel());
        assertEquals(85, settings.getMaxAgentToolTurns());
        assertTrue(settings.isLogToolCalls());
        assertFalse(settingsPanel.onboardingBanner.isVisible());
        assertEquals(1, initialCallbackCount.get());

        AppSettings copied = settings.copy();
        assertEquals("new-secret-key", copied.getGeminiApiKey());
        assertEquals("MAX", copied.getAgentProfile());
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
        settings.setAgentProfile("SPEED");
        settings.setMaxAgentToolTurns(100);
        settings.setMaxChatMemoryMessages(250);
        settings.setLogToolCalls(false);
        settingsPanel.onSettingsChanged(new SettingsChangedEvent(settings));
        SwingTestUtils.flushEdt();
        assertEquals("synced-from-event-key", new String(settingsPanel.geminiApiKeyField.getPassword()));
        assertEquals(AgentProfile.SPEED, profileCombo.getSelectedItem());
        assertEquals(AgentProfile.SPEED.getBadgeSummary(), settingsPanel.modelApiBadgeLabel.getText());
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

        assertFalse(settingsPanel.testConnectionProgressBar.isVisible());
        assertFalse(settingsPanel.testConnectionStatusLabel.isVisible());

        settingsPanel.geminiApiKeyField.setText("   ");
        SwingTestUtils.clickOnEdt(settingsPanel.testConnectionButton);
        assertTrue(settingsPanel.testConnectionStatusLabel.isVisible());
        assertTrue(settingsPanel.testConnectionStatusLabel.getText().contains("Please enter a Gemini API Key first."));
        verifyNoInteractions(aiService);

        CompletableFuture<String> gate = new CompletableFuture<>();
        when(aiService.testConnection(eq(GenAiConnectionConfig.forApiKey("valid-key")), anyString()))
                .thenAnswer(inv -> gate.join());
        settingsPanel.geminiApiKeyField.setText("valid-key");
        SwingTestUtils.runOnEdt(settingsPanel.testConnectionButton::doClick);
        SwingTestUtils.runOnEdt(() -> {
            assertFalse(settingsPanel.testConnectionButton.isEnabled());
            assertEquals(SettingsPanel.TESTING_CONNECTION_TEXT, settingsPanel.testConnectionButton.getText());
            assertTrue(settingsPanel.testConnectionProgressBar.isVisible());
            assertTrue(settingsPanel.testConnectionProgressBar.isIndeterminate());
            assertFalse(settingsPanel.testConnectionStatusLabel.isVisible());
        });

        gate.complete("OK");
        SwingTestUtils.awaitFuture(settingsPanel.lastAsyncFuture);
        assertTrue(settingsPanel.testConnectionButton.isEnabled());
        assertFalse(settingsPanel.testConnectionProgressBar.isVisible());
        assertFalse(settingsPanel.testConnectionProgressBar.isIndeterminate());
        assertTrue(settingsPanel.testConnectionStatusLabel.isVisible());
        assertEquals(SettingsPanel.CONNECTION_SUCCESS_TEXT, settingsPanel.testConnectionStatusLabel.getText());

        when(aiService.testConnection(eq(GenAiConnectionConfig.forApiKey("invalid-key")), anyString()))
                .thenThrow(new IllegalStateException("401 UNAUTHORIZED: API key not valid"));
        settingsPanel.geminiApiKeyField.setText("invalid-key");
        SwingTestUtils.clickOnEdt(settingsPanel.testConnectionButton);
        SwingTestUtils.awaitFuture(settingsPanel.lastAsyncFuture);
        assertFalse(settingsPanel.testConnectionProgressBar.isVisible());
        assertTrue(settingsPanel.testConnectionStatusLabel.isVisible());
        assertEquals(SettingsPanel.CONNECTION_FAILED_PREFIX + "401 UNAUTHORIZED: API key not valid",
                settingsPanel.testConnectionStatusLabel.getText());
    }

    @Test
    void testVertexAiProviderSelectionValidationBrowseAndSaveOnboarding() {
        assertEquals(SettingsPanel.AUTH_PROVIDER_API_KEY, settingsPanel.authProviderCombo.getSelectedItem());
        assertTrue(settingsPanel.apiKeyPanel.isVisible());
        assertFalse(settingsPanel.vertexAiPanel.isVisible());

        SwingTestUtils
                .runOnEdt(() -> settingsPanel.authProviderCombo.setSelectedItem(SettingsPanel.AUTH_PROVIDER_VERTEX_AI));
        assertFalse(settingsPanel.apiKeyPanel.isVisible());
        assertTrue(settingsPanel.vertexAiPanel.isVisible());

        settingsPanel.vertexProjectIdField.setText("   ");
        settingsPanel.vertexLocationField.setText("us-central1");
        SwingTestUtils.clickOnEdt(settingsPanel.testConnectionButton);
        assertTrue(settingsPanel.testConnectionStatusLabel.isVisible());
        assertTrue(settingsPanel.testConnectionStatusLabel.getText().contains("Please enter a GCP Project ID first."));
        verifyNoInteractions(aiService);

        settingsPanel.vertexProjectIdField.setText("my-gcp-project");
        settingsPanel.vertexLocationField.setText("   ");
        SwingTestUtils.clickOnEdt(settingsPanel.testConnectionButton);
        assertTrue(settingsPanel.testConnectionStatusLabel.isVisible());
        assertTrue(settingsPanel.testConnectionStatusLabel.getText().contains("Please enter a Vertex Location first."));
        verifyNoInteractions(aiService);

        settingsPanel.setCredentialsFileChooser(() -> new File("/tmp/service-account.json"));
        SwingTestUtils.clickOnEdt(settingsPanel.browseCredentialsButton);
        assertEquals(new File("/tmp/service-account.json").getAbsolutePath(),
                settingsPanel.vertexCredentialsPathField.getText());

        settingsPanel.vertexLocationField.setText("us-central1");
        GenAiConnectionConfig expectedConfig = GenAiConnectionConfig.forVertexAi("my-gcp-project", "us-central1",
                new File("/tmp/service-account.json").getAbsolutePath());
        when(aiService.testConnection(eq(expectedConfig), anyString())).thenReturn("OK");
        SwingTestUtils.clickOnEdt(settingsPanel.testConnectionButton);
        SwingTestUtils.awaitFuture(settingsPanel.lastAsyncFuture);
        assertTrue(settingsPanel.testConnectionStatusLabel.isVisible());
        assertEquals(SettingsPanel.CONNECTION_SUCCESS_TEXT, settingsPanel.testConnectionStatusLabel.getText());

        java.util.concurrent.atomic.AtomicInteger initialCallbackCount = new java.util.concurrent.atomic.AtomicInteger();
        settingsPanel.setOnInitialApiKeyConfiguredCallback(initialCallbackCount::incrementAndGet);
        SwingTestUtils.clickOnEdt(settingsPanel.saveButton);

        assertTrue(settings.isVertexAiEnabled());
        assertEquals("my-gcp-project", settings.getVertexProjectId());
        assertEquals("us-central1", settings.getVertexLocation());
        assertEquals(new File("/tmp/service-account.json").getAbsolutePath(), settings.getVertexCredentialsPath());
        assertFalse(settingsPanel.onboardingBanner.isVisible());
        assertEquals(1, initialCallbackCount.get());

        AppSettings eventSettings = new AppSettings();
        eventSettings.setVertexAiEnabled(false);
        eventSettings.setGeminiApiKey("switched-back-key");
        settingsPanel.onSettingsChanged(new SettingsChangedEvent(eventSettings));
        SwingTestUtils.flushEdt();
        assertEquals(SettingsPanel.AUTH_PROVIDER_API_KEY, settingsPanel.authProviderCombo.getSelectedItem());
        assertTrue(settingsPanel.apiKeyPanel.isVisible());
        assertFalse(settingsPanel.vertexAiPanel.isVisible());
    }
}
