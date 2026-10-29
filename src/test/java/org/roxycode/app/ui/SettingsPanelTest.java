package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.event.ActionListener;
import javax.swing.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.roxycode.app.RoxyCode;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.GeminiModelConfig;
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
    void testSectionCardsBadgesAndPasswordFieldStyling() {
        assertEquals(SettingsPanel.SUBTITLE_TEXT, settingsPanel.getSubtitleLabel().getText());
        assertEquals(3, settingsPanel.getSectionCards().size());
        assertEquals(2, settingsPanel.getRestartBadges().size());
        for (JLabel badge : settingsPanel.getRestartBadges()) {
            assertEquals(SettingsPanel.RESTART_BADGE_TEXT, badge.getText());
            assertNotNull(badge.getIcon());
        }

        JPasswordField apiKeyField = settingsPanel.getGeminiApiKeyField();
        assertNotNull(apiKeyField);
        String style = (String) apiKeyField.getClientProperty(FlatClientProperties.STYLE);
        assertNotNull(style);
        assertTrue(style.contains("showRevealButton: true"));
        assertNotNull(settingsPanel.getSaveButton().getIcon());
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

        settingsPanel.getMaxAgentToolTurnsSpinner().setValue(12);
        settingsPanel.getLogToolCallsCheckBox().setSelected(true);
        JButton saveButton = SwingTestUtils.findButton(settingsPanel, "Save Settings");
        assertNotNull(saveButton);
        saveButton.doClick();

        verify(settingsService, times(1)).saveSettings();
        assertEquals(12, settings.getMaxAgentToolTurns());
        assertTrue(settings.isLogToolCalls());
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
