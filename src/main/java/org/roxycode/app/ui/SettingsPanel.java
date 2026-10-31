package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import java.awt.*;
import java.text.ParseException;
import java.util.List;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.GeminiModelConfig;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.util.Edt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Lazy
public final class SettingsPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(SettingsPanel.class);

    static final String SUBTITLE_TEXT = "Configure appearance, Gemini model credentials, agent execution limits, and diagnostics.";
    static final String RESTART_BADGE_TEXT = "Restart Required";
    static final String RESTART_LEGEND_TEXT = "(*) Restart required for Gemini API Key and Max Chat History Messages";
    static final String SAVED_RESTART_REQUIRED_TEXT = "Settings saved. Please restart RoxyCode for API Key / Chat History changes to take effect.";
    static final String SAVED_APPLIED_TEXT = "Settings saved and applied.";
    static final String SECTION_APPEARANCE_TITLE = "Appearance";
    static final String SECTION_AI_MODEL_TITLE = "AI Model & Credentials";
    static final String SECTION_AGENT_EXECUTION_TITLE = "Agent Execution & Diagnostics";
    static final int MIN_AGENT_TOOL_TURNS = 1;
    static final int MAX_AGENT_TOOL_TURNS = 100;
    static final int MIN_CHAT_MEMORY_MESSAGES = 1;
    static final int MAX_CHAT_MEMORY_MESSAGES = 500;

    private final transient SettingsService settingsService;
    private final JLabel titleLabel;
    private final JLabel subtitleLabel;
    private final JComboBox<String> themeCombo;
    private final JComboBox<GeminiModelConfig> modelCombo;
    private final JLabel modelApiBadgeLabel;
    private final JTextArea modelDescriptionArea;
    private final JSpinner maxMessagesSpinner;
    private final JPasswordField geminiApiKeyField;
    private final JSpinner maxAgentToolTurnsSpinner;
    private final JCheckBox logToolCallsCheckBox;
    private final JButton saveButton;
    private final JPanel restartCalloutPanel;
    private final JLabel legendIconLabel;
    private final JLabel legendLabel;
    private final JLabel statusLabel;
    private final Timer statusTimer;

    private boolean updatingFromSettings = false;

    public SettingsPanel(SettingsService settingsService) {
        this.settingsService = settingsService;
        setLayout(new MigLayout("fill, insets 20, hidemode 3", "[grow, fill]", "[]12[grow, fill]"));
        setOpaque(true);

        titleLabel = new JLabel("Global Settings");
        subtitleLabel = new JLabel(SUBTITLE_TEXT);
        statusLabel = new JLabel("");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 11.5f));

        saveButton = new JButton("Save Settings");
        saveButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        saveButton.setIconTextGap(6);
        saveButton.addActionListener(e -> saveSettings());

        JPanel headerActions = new JPanel(new MigLayout("insets 0, gapx 12", "[][]", "[center]"));
        headerActions.setOpaque(false);
        headerActions.add(statusLabel);
        headerActions.add(saveButton);

        add(UiComponents.createPageHeader(titleLabel, subtitleLabel, headerActions), "growx, wmin 0, wrap");

        themeCombo = new JComboBox<>(
                new String[]{"FlatLaf Light", "FlatLaf Dark", "IntelliJ", "Darcula", "macOS Light", "macOS Dark"});
        themeCombo.setSelectedItem(settingsService.getSettings().getTheme());
        themeCombo.addActionListener(e -> updateTheme());

        modelCombo = new JComboBox<>();
        modelApiBadgeLabel = new JLabel();
        UiComponents.styleCodePillBadge(modelApiBadgeLabel);

        modelDescriptionArea = UiComponents.createReadOnlyTextArea();
        loadModels();
        modelCombo.addActionListener(e -> updateSelectedModelDetails());
        updateSelectedModelDetails();

        geminiApiKeyField = new JPasswordField(24);
        geminiApiKeyField.setText(settingsService.getSettings().getGeminiApiKey());
        geminiApiKeyField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Enter your Gemini API key...");
        SwingHtmlUtils.installTextContextMenu(geminiApiKeyField);

        int initialToolTurns = Math.clamp(settingsService.getSettings().getMaxAgentToolTurns(), MIN_AGENT_TOOL_TURNS,
                MAX_AGENT_TOOL_TURNS);
        maxAgentToolTurnsSpinner = new JSpinner(
                new SpinnerNumberModel(initialToolTurns, MIN_AGENT_TOOL_TURNS, MAX_AGENT_TOOL_TURNS, 1));

        int initialMaxMessages = Math.clamp(settingsService.getSettings().getMaxChatMemoryMessages(),
                MIN_CHAT_MEMORY_MESSAGES, MAX_CHAT_MEMORY_MESSAGES);
        maxMessagesSpinner = new JSpinner(
                new SpinnerNumberModel(initialMaxMessages, MIN_CHAT_MEMORY_MESSAGES, MAX_CHAT_MEMORY_MESSAGES, 5));

        logToolCallsCheckBox = new JCheckBox("Write tool calls and results to ~/.roxycode/logs/toolcalls.log");
        logToolCallsCheckBox.setOpaque(false);
        logToolCallsCheckBox.setSelected(settingsService.getSettings().isLogToolCalls());

        JPanel cardsContainer = UiComponents.createScrollableCardsPanel(
                new MigLayout("fillx, wrap 1, insets 4 2 16 6, gapy 14", "[grow, fill]", ""));
        cardsContainer.add(buildAppearanceCard(), "growx, wmin 0");
        cardsContainer.add(buildAiModelCard(), "growx, wmin 0");
        cardsContainer.add(buildAgentExecutionCard(), "growx, wmin 0");

        restartCalloutPanel = new JPanel(
                new MigLayout("fillx, insets 10 14 10 14, gapx 10", "[][0:0, grow, fill]", "[center]"));
        restartCalloutPanel.setOpaque(true);
        legendIconLabel = new JLabel();
        legendLabel = new JLabel(RESTART_LEGEND_TEXT);
        legendLabel.setFont(legendLabel.getFont().deriveFont(Font.ITALIC, 11.5f));
        restartCalloutPanel.add(legendIconLabel);
        restartCalloutPanel.add(legendLabel, "growx, wmin 0");
        cardsContainer.add(restartCalloutPanel, "growx, wmin 0");

        add(UiComponents.createBorderlessScrollPane(cardsContainer), "grow, wmin 0");

        statusTimer = new Timer(3000, e -> statusLabel.setText(""));
        statusTimer.setRepeats(false);

        applyThemeStyling();
    }

    private JPanel buildAppearanceCard() {
        JPanel card = new JPanel(new MigLayout("fillx, wrap 1, insets 16 18 16 18, gapy 10", "[grow, fill]", ""));
        UiComponents.styleCardPanel(card);

        JLabel headerLabel = new JLabel(SECTION_APPEARANCE_TITLE);
        headerLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold +2");
        headerLabel.setIconTextGap(8);
        headerLabel.setIcon(FontIcon.of(Codicons.SYMBOL_COLOR, 18, SwingHtmlUtils.getAccentColor()));
        card.add(headerLabel, "growx, wmin 0, gapbottom 2");

        card.add(UiComponents.createFieldRow(
                UiComponents.createFieldTextBlock("Theme",
                        "Choose between light and dark color schemes. Changes preview immediately.", null),
                themeCombo, "w 220:250:280"), "growx, wmin 0");

        return card;
    }

    private JPanel buildAiModelCard() {
        JPanel card = new JPanel(new MigLayout("fillx, wrap 1, insets 16 18 16 18, gapy 10", "[grow, fill]", ""));
        UiComponents.styleCardPanel(card);

        JLabel headerLabel = new JLabel(SECTION_AI_MODEL_TITLE);
        headerLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold +2");
        headerLabel.setIconTextGap(8);
        headerLabel.setIcon(FontIcon.of(Codicons.HUBOT, 18, SwingHtmlUtils.getAccentColor()));
        card.add(headerLabel, "growx, wmin 0, gapbottom 2");

        JPanel modelControlBox = new JPanel(new MigLayout("insets 0, gapx 8", "[][]", "[center]"));
        modelControlBox.setOpaque(false);
        modelControlBox.add(modelApiBadgeLabel, "hidemode 3");
        modelControlBox.add(modelCombo, "w 220:250:280");

        card.add(UiComponents.createFieldRow(
                UiComponents.createFieldTextBlock("Gemini Model",
                        "Default Gemini model used for Codebase Q&A and Feature Mode turns.", null),
                modelControlBox, ""), "growx, wmin 0");

        card.add(modelDescriptionArea, "growx, wmin 0, gaptop 2, hidemode 3");
        card.add(createRowSeparator(), "growx, gaptop 4, gapbottom 4");

        JPanel apiKeyRow = new JPanel(new MigLayout("fillx, insets 0, wrap 1, gapy 8", "[grow, fill]", ""));
        apiKeyRow.setOpaque(false);
        apiKeyRow.add(
                UiComponents.createFieldTextBlock("Gemini API Key",
                        "Google GenAI API key used to authenticate model requests.", createRestartBadge()),
                "growx, wmin 0");
        apiKeyRow.add(geminiApiKeyField, "w 280:420:520");

        card.add(apiKeyRow, "growx, wmin 0");
        return card;
    }

    private JPanel buildAgentExecutionCard() {
        JPanel card = new JPanel(new MigLayout("fillx, wrap 1, insets 16 18 16 18, gapy 10", "[grow, fill]", ""));
        UiComponents.styleCardPanel(card);

        JLabel headerLabel = new JLabel(SECTION_AGENT_EXECUTION_TITLE);
        headerLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold +2");
        headerLabel.setIconTextGap(8);
        headerLabel.setIcon(FontIcon.of(Codicons.PULSE, 18, SwingHtmlUtils.getAccentColor()));
        card.add(headerLabel, "growx, wmin 0, gapbottom 2");

        card.add(UiComponents.createFieldRow(
                UiComponents.createFieldTextBlock("Max Agent Tool Turns",
                        "Maximum consecutive JEXL tool execution turns allowed per prompt (1–100).", null),
                maxAgentToolTurnsSpinner, "w 100:120:140"), "growx, wmin 0");

        card.add(createRowSeparator(), "growx, gaptop 4, gapbottom 4");

        card.add(UiComponents.createFieldRow(UiComponents.createFieldTextBlock("Max Chat History Messages",
                "Maximum number of conversation messages retained in active chat memory (1–500).",
                createRestartBadge()), maxMessagesSpinner, "w 100:120:140"), "growx, wmin 0");

        card.add(createRowSeparator(), "growx, gaptop 4, gapbottom 4");

        JPanel logRow = new JPanel(new MigLayout("fillx, insets 0, wrap 1, gapy 6", "[grow, fill]", ""));
        logRow.setOpaque(false);
        logRow.add(
                UiComponents.createFieldTextBlock("Log Tool Calls & Results",
                        "Record every JEXL script execution and its output for troubleshooting.", null),
                "growx, wmin 0");
        logRow.add(logToolCallsCheckBox, "growx, wmin 0");
        card.add(logRow, "growx, wmin 0");

        return card;
    }

    private JLabel createRestartBadge() {
        JLabel badge = new JLabel(RESTART_BADGE_TEXT);
        badge.setIconTextGap(4);
        return badge;
    }

    private static JSeparator createRowSeparator() {
        JSeparator separator = new JSeparator(SwingConstants.HORIZONTAL);
        separator.setOpaque(false);
        return separator;
    }

    private void loadModels() {
        List<GeminiModelConfig> modelList = settingsService.getAvailableModels();
        if (modelList == null) {
            modelList = List.of();
        }
        modelCombo.setModel(new DefaultComboBoxModel<>(modelList.toArray(new GeminiModelConfig[0])));
        UiComponents.selectModelByApiName(modelCombo, settingsService.getSettings().getGeminiModel());
    }

    void updateSelectedModelDetails() {
        UiComponents.updateModelSelectionDetails(modelCombo, modelApiBadgeLabel, modelDescriptionArea, null);
    }

    void applyThemeStyling() {
        boolean dark = FlatLaf.isLafDark();
        Color accentColor = SwingHtmlUtils.getAccentColor();
        Color borderFallback;
        if (dark) {
            borderFallback = new Color(0x3c, 0x3f, 0x41);
        } else {
            borderFallback = new Color(0xd8, 0xdc, 0xe0);
        }
        Color borderColor = SwingHtmlUtils.getUiColor(borderFallback, "Component.borderColor");
        Color mutedColor = SwingHtmlUtils.getUiColor(new Color(0x6c, 0x75, 0x7d), "Label.disabledForeground",
                "Component.infoForeground");
        Color greenColor = SwingHtmlUtils.getUiColor(new Color(0x28, 0xa7, 0x45), "Actions.Green");

        statusLabel.setForeground(greenColor);

        String accentHex = SwingHtmlUtils.toHex(accentColor);
        saveButton.setIcon(FontIcon.of(Codicons.SAVE, 14, Color.WHITE));
        saveButton.setForeground(Color.WHITE);
        saveButton.putClientProperty(FlatClientProperties.STYLE,
                "font: bold; arc: 8; margin: 6,14,6,14; borderWidth: 0; focusWidth: 0; background: " + accentHex
                        + "; hoverBackground: darken(" + accentHex + ", 8%); pressedBackground: darken(" + accentHex
                        + ", 14%)");

        geminiApiKeyField.putClientProperty(FlatClientProperties.STYLE, "showRevealButton: true; arc: 8");

        String borderHex = SwingHtmlUtils.toHex(borderColor);
        String calloutBgHex = SwingHtmlUtils.toHex(SwingHtmlUtils.getTintedBackground(accentColor, 0.10));
        restartCalloutPanel.putClientProperty(FlatClientProperties.STYLE,
                "arc: 10; border: 1,1,1,1," + borderHex + ",1,10; background: " + calloutBgHex);
        legendIconLabel.setIcon(FontIcon.of(Codicons.INFO, 16, accentColor));
        legendLabel.setForeground(mutedColor);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (subtitleLabel != null) {
            applyThemeStyling();
        }
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        if (event == null || event.settings() == null) {
            return;
        }
        Edt.run(() -> {
            String theme = event.settings().getTheme();
            if (theme != null && !theme.equals(themeCombo.getSelectedItem())) {
                updatingFromSettings = true;
                try {
                    themeCombo.setSelectedItem(theme);
                } finally {
                    updatingFromSettings = false;
                }
            }
            String modelId = event.settings().getGeminiModel();
            if (modelId != null) {
                GeminiModelConfig selected = (GeminiModelConfig) modelCombo.getSelectedItem();
                if (selected == null || !modelId.equals(selected.apiName())) {
                    UiComponents.selectModelByApiName(modelCombo, modelId);
                }
            }
            int clampedToolTurns = Math.clamp(event.settings().getMaxAgentToolTurns(), MIN_AGENT_TOOL_TURNS,
                    MAX_AGENT_TOOL_TURNS);
            if (!Integer.valueOf(clampedToolTurns).equals(maxAgentToolTurnsSpinner.getValue())) {
                maxAgentToolTurnsSpinner.setValue(clampedToolTurns);
            }
            int clampedMaxMessages = Math.clamp(event.settings().getMaxChatMemoryMessages(), MIN_CHAT_MEMORY_MESSAGES,
                    MAX_CHAT_MEMORY_MESSAGES);
            if (!Integer.valueOf(clampedMaxMessages).equals(maxMessagesSpinner.getValue())) {
                maxMessagesSpinner.setValue(clampedMaxMessages);
            }
            boolean logToolCalls = event.settings().isLogToolCalls();
            if (logToolCalls != logToolCallsCheckBox.isSelected()) {
                logToolCallsCheckBox.setSelected(logToolCalls);
            }
        });
    }

    private void updateTheme() {
        if (updatingFromSettings) {
            return;
        }
        String selectedTheme = (String) themeCombo.getSelectedItem();
        if (selectedTheme != null) {
            settingsService.getSettings().setTheme(selectedTheme);
            applyTheme(selectedTheme);
        }
    }

    private static void commitSpinnerEdit(JSpinner spinner) {
        try {
            spinner.commitEdit();
        } catch (ParseException ex) {
            log.debug("Ignoring invalid spinner text edit: {}", ex.getMessage());
        }
    }

    private void saveSettings() {
        commitSpinnerEdit(maxAgentToolTurnsSpinner);
        commitSpinnerEdit(maxMessagesSpinner);

        AppSettings settings = settingsService.getSettings();
        String oldApiKey = "";
        if (settings.getGeminiApiKey() != null) {
            oldApiKey = settings.getGeminiApiKey();
        }
        String newApiKey = new String(geminiApiKeyField.getPassword());
        int oldMaxMessages = settings.getMaxChatMemoryMessages();
        int newMaxMessages = (Integer) maxMessagesSpinner.getValue();
        boolean restartRequiredChanged = !oldApiKey.equals(newApiKey) || oldMaxMessages != newMaxMessages;

        settings.setGeminiApiKey(newApiKey);
        GeminiModelConfig selectedModel = (GeminiModelConfig) modelCombo.getSelectedItem();
        if (selectedModel != null) {
            settings.setGeminiModel(selectedModel.apiName());
        }
        settings.setMaxAgentToolTurns((Integer) maxAgentToolTurnsSpinner.getValue());
        settings.setMaxChatMemoryMessages(newMaxMessages);
        settings.setLogToolCalls(logToolCallsCheckBox.isSelected());
        settingsService.saveSettings();

        if (restartRequiredChanged) {
            statusLabel.setText(SAVED_RESTART_REQUIRED_TEXT);
        } else {
            statusLabel.setText(SAVED_APPLIED_TEXT);
        }
        statusTimer.restart();
    }

    JLabel getLegendLabel() {
        return legendLabel;
    }

    JLabel getStatusLabel() {
        return statusLabel;
    }

    Timer getStatusTimer() {
        return statusTimer;
    }

    JSpinner getMaxMessagesSpinner() {
        return maxMessagesSpinner;
    }

    JSpinner getMaxAgentToolTurnsSpinner() {
        return maxAgentToolTurnsSpinner;
    }

    JComboBox<GeminiModelConfig> getModelCombo() {
        return modelCombo;
    }

    JLabel getModelApiBadgeLabel() {
        return modelApiBadgeLabel;
    }

    JTextArea getModelDescriptionArea() {
        return modelDescriptionArea;
    }

    JCheckBox getLogToolCallsCheckBox() {
        return logToolCallsCheckBox;
    }

    public static void applyTheme(String themeName) {
        Edt.runAndWait(() -> {
            try {
                UIManager.put("TabbedPane.tabType", "card");
                UIManager.put("TabbedPane.showTabSeparators", true);
                String themeToApply;
                if (themeName != null) {
                    themeToApply = themeName;
                } else {
                    themeToApply = "";
                }
                UIManager.setLookAndFeel(switch (themeToApply) {
                    case "FlatLaf Dark" -> new FlatDarkLaf();
                    case "IntelliJ" -> new FlatIntelliJLaf();
                    case "Darcula" -> new FlatDarculaLaf();
                    case "macOS Light" -> new FlatMacLightLaf();
                    case "macOS Dark" -> new FlatMacDarkLaf();
                    default -> new FlatLightLaf();
                });
                FlatLaf.updateUI();
            } catch (Exception ex) {
                log.error("Failed to apply theme {}: {}", themeName, ex.getMessage());
            }
        });
    }
}
