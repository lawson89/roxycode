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
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.GeminiModelConfig;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.util.Edt;
import org.roxycode.app.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Component
@Lazy
public final class SettingsPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(SettingsPanel.class);

    static final String SUBTITLE_TEXT = "Configure appearance, Gemini model credentials, agent execution limits, and diagnostics.";
    static final String SAVED_APPLIED_TEXT = "Settings saved and applied.";
    static final String ONBOARDING_API_KEY_NOTICE_TEXT = "Welcome to RoxyCode! Enter your Gemini API Key below and click 'Save Settings' to get started.";
    static final String GET_API_KEY_BUTTON_TEXT = "Get a Gemini API Key";
    static final String GEMINI_API_KEY_URL = "https://aistudio.google.com/apikey";
    static final String TEST_CONNECTION_TEXT = "Test Connection";
    static final String TESTING_CONNECTION_TEXT = "Testing...";
    static final String CONNECTION_SUCCESS_TEXT = "Connection verified!";
    static final String CONNECTION_FAILED_PREFIX = "Connection failed: ";
    static final String SECTION_APPEARANCE_TITLE = "Appearance";
    static final String SECTION_AI_MODEL_TITLE = "AI Model & Credentials";
    static final String SECTION_AGENT_EXECUTION_TITLE = "Agent Execution & Diagnostics";
    private static final String GROW_FILL = "[grow, fill]";
    private static final String GROWX_WMIN_0 = "growx, wmin 0";
    private static final String SEPARATOR_CONSTRAINTS = "growx, gaptop 4, gapbottom 4";
    private static final String CARD_LAYOUT_CONSTRAINTS = "fillx, wrap 1, insets 16 18 16 18, gapy 10";
    private static final String HEADER_FONT_STYLE = "font: bold +2";
    private static final String HEADER_CONSTRAINTS = "growx, wmin 0, gapbottom 2";
    private static final String CONTROL_WIDTH_CONSTRAINTS = "w 220:250:280";
    static final int MIN_AGENT_TOOL_TURNS = 1;
    static final int MAX_AGENT_TOOL_TURNS = 100;
    static final int MIN_CHAT_MEMORY_MESSAGES = 1;
    static final int MAX_CHAT_MEMORY_MESSAGES = 500;

    private final transient SettingsService settingsService;
    private final transient AiService aiService;
    private final JLabel titleLabel;
    private final JLabel subtitleLabel;
    final JComboBox<String> themeCombo;
    final JComboBox<GeminiModelConfig> modelCombo;
    final JLabel modelApiBadgeLabel;
    final JTextArea modelDescriptionArea;
    final JSpinner maxMessagesSpinner;
    final JPasswordField geminiApiKeyField;
    final JButton getApiKeyLinkButton;
    final JButton testConnectionButton;
    final JSpinner maxAgentToolTurnsSpinner;
    final JCheckBox logToolCallsCheckBox;
    final JPanel onboardingBannerPanel;
    final JLabel onboardingBannerIconLabel;
    final JLabel onboardingBannerMessageLabel;
    final JButton saveButton;
    final JLabel statusLabel;
    final Timer statusTimer;
    private JLabel appearanceHeaderLabel;
    private JLabel aiModelHeaderLabel;
    private JLabel agentExecutionHeaderLabel;
    private transient Consumer<String> urlOpener = UiComponents::openUrlOrCopyToClipboard;
    private transient Runnable onInitialApiKeyConfiguredCallback;
    transient CompletableFuture<?> lastAsyncFuture = CompletableFuture.completedFuture(null);

    private boolean updatingFromSettings = false;

    @Autowired
    public SettingsPanel(SettingsService settingsService, @Nullable AiService aiService) {
        this.settingsService = settingsService;
        this.aiService = aiService;
        setLayout(new MigLayout("fill, insets 20, hidemode 3", GROW_FILL, "[][]12" + GROW_FILL));
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

        onboardingBannerPanel = new JPanel(new MigLayout("fillx, insets 0", "[][grow]", "[]"));
        onboardingBannerIconLabel = new JLabel();
        onboardingBannerMessageLabel = new JLabel(ONBOARDING_API_KEY_NOTICE_TEXT);
        onboardingBannerPanel.add(onboardingBannerIconLabel, "gapright 8");
        onboardingBannerPanel.add(onboardingBannerMessageLabel, "growx, wmin 100");
        onboardingBannerPanel.setVisible(false);
        add(onboardingBannerPanel, "growx, wmin 0, wrap, gaptop 8, hidemode 3");

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

        getApiKeyLinkButton = new JButton(GET_API_KEY_BUTTON_TEXT);
        getApiKeyLinkButton.putClientProperty(FlatClientProperties.BUTTON_TYPE,
                FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        getApiKeyLinkButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        getApiKeyLinkButton.setFocusable(false);
        getApiKeyLinkButton.setHorizontalTextPosition(SwingConstants.LEFT);
        getApiKeyLinkButton.setIconTextGap(5);
        getApiKeyLinkButton.setToolTipText(GEMINI_API_KEY_URL);
        getApiKeyLinkButton.addActionListener(e -> urlOpener.accept(GEMINI_API_KEY_URL));

        testConnectionButton = new JButton(TEST_CONNECTION_TEXT);
        testConnectionButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        testConnectionButton.setIconTextGap(6);
        testConnectionButton.addActionListener(e -> testConnection());

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

        JPanel cardsContainer = UiComponents
                .createScrollableCardsPanel(new MigLayout("fillx, wrap 1, insets 4 2 16 6, gapy 14", GROW_FILL, ""));
        cardsContainer.add(buildAppearanceCard(), GROWX_WMIN_0);
        cardsContainer.add(buildAiModelCard(), GROWX_WMIN_0);
        cardsContainer.add(buildAgentExecutionCard(), GROWX_WMIN_0);

        add(UiComponents.createBorderlessScrollPane(cardsContainer), "grow, wmin 0");

        statusTimer = new Timer(3000, e -> statusLabel.setText(""));
        statusTimer.setRepeats(false);

        applyThemeStyling();
        updateOnboardingBannerState();
    }

    private JPanel buildAppearanceCard() {
        JPanel card = new JPanel(new MigLayout(CARD_LAYOUT_CONSTRAINTS, GROW_FILL, ""));
        UiComponents.styleCardPanel(card);

        appearanceHeaderLabel = UiComponents.createSectionHeader(SECTION_APPEARANCE_TITLE, Codicons.SYMBOL_COLOR,
                HEADER_FONT_STYLE);
        card.add(appearanceHeaderLabel, HEADER_CONSTRAINTS);

        card.add(UiComponents.createFieldRow(
                UiComponents.createFieldTextBlock("Theme",
                        "Choose between light and dark color schemes. Changes preview immediately.", null),
                themeCombo, CONTROL_WIDTH_CONSTRAINTS), GROWX_WMIN_0);

        return card;
    }

    private JPanel buildAiModelCard() {
        JPanel card = new JPanel(new MigLayout(CARD_LAYOUT_CONSTRAINTS, GROW_FILL, ""));
        UiComponents.styleCardPanel(card);

        aiModelHeaderLabel = UiComponents.createSectionHeader(SECTION_AI_MODEL_TITLE, Codicons.HUBOT,
                HEADER_FONT_STYLE);
        card.add(aiModelHeaderLabel, HEADER_CONSTRAINTS);

        JPanel modelControlBox = UiComponents.createModelControlBox(modelApiBadgeLabel, modelCombo,
                CONTROL_WIDTH_CONSTRAINTS);

        card.add(UiComponents.createFieldRow(
                UiComponents.createFieldTextBlock("Gemini Model",
                        "Default Gemini model used for Codebase Q&A and Feature Mode turns.", null),
                modelControlBox, ""), GROWX_WMIN_0);

        card.add(modelDescriptionArea, "growx, wmin 0, gaptop 2, hidemode 3");
        card.add(createRowSeparator(), SEPARATOR_CONSTRAINTS);

        JPanel apiKeyRow = new JPanel(new MigLayout("fillx, insets 0, wrap 1, gapy 8", GROW_FILL, ""));
        apiKeyRow.setOpaque(false);
        apiKeyRow.add(UiComponents.createFieldRow(UiComponents.createFieldTextBlock("Gemini API Key",
                "Google GenAI API key used to authenticate model requests (applies immediately on save).", null),
                getApiKeyLinkButton, ""), GROWX_WMIN_0);

        JPanel apiKeyInputRow = new JPanel(new MigLayout("insets 0, gapx 10", "[][]", "[center]"));
        apiKeyInputRow.setOpaque(false);
        apiKeyInputRow.add(geminiApiKeyField, "w 280:420:520");
        apiKeyInputRow.add(testConnectionButton);
        apiKeyRow.add(apiKeyInputRow, GROWX_WMIN_0);

        card.add(apiKeyRow, GROWX_WMIN_0);
        return card;
    }

    private JPanel buildAgentExecutionCard() {
        JPanel card = new JPanel(new MigLayout(CARD_LAYOUT_CONSTRAINTS, GROW_FILL, ""));
        UiComponents.styleCardPanel(card);

        agentExecutionHeaderLabel = UiComponents.createSectionHeader(SECTION_AGENT_EXECUTION_TITLE, Codicons.PULSE,
                HEADER_FONT_STYLE);
        card.add(agentExecutionHeaderLabel, HEADER_CONSTRAINTS);

        card.add(UiComponents.createFieldRow(
                UiComponents.createFieldTextBlock("Max Agent Tool Turns",
                        "Maximum consecutive JEXL tool execution turns allowed per prompt (1–100).", null),
                maxAgentToolTurnsSpinner, "w 100:120:140"), GROWX_WMIN_0);

        card.add(createRowSeparator(), SEPARATOR_CONSTRAINTS);

        card.add(UiComponents.createFieldRow(
                UiComponents.createFieldTextBlock("Max Chat History Messages",
                        "Maximum number of conversation messages retained in active chat memory (1–500).", null),
                maxMessagesSpinner, "w 100:120:140"), GROWX_WMIN_0);

        card.add(createRowSeparator(), SEPARATOR_CONSTRAINTS);

        JPanel logRow = new JPanel(new MigLayout("fillx, insets 0, wrap 1, gapy 6", GROW_FILL, ""));
        logRow.setOpaque(false);
        logRow.add(UiComponents.createFieldTextBlock("Log Tool Calls & Results",
                "Record every JEXL script execution and its output for troubleshooting.", null), GROWX_WMIN_0);
        logRow.add(logToolCallsCheckBox, GROWX_WMIN_0);
        card.add(logRow, GROWX_WMIN_0);

        return card;
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
        Color accentColor = SwingHtmlUtils.getAccentColor();
        Color greenColor = SwingHtmlUtils.getUiColor(new Color(0x28, 0xa7, 0x45), "Actions.Green");

        statusLabel.setForeground(greenColor);
        if (appearanceHeaderLabel != null) {
            appearanceHeaderLabel.setIcon(FontIcon.of(Codicons.SYMBOL_COLOR, 18, accentColor));
        }
        if (aiModelHeaderLabel != null) {
            aiModelHeaderLabel.setIcon(FontIcon.of(Codicons.HUBOT, 18, accentColor));
        }
        if (agentExecutionHeaderLabel != null) {
            agentExecutionHeaderLabel.setIcon(FontIcon.of(Codicons.PULSE, 18, accentColor));
        }
        UiComponents.styleCodePillBadge(modelApiBadgeLabel);

        String accentHex = SwingHtmlUtils.toHex(accentColor);
        saveButton.setIcon(FontIcon.of(Codicons.SAVE, 14, Color.WHITE));
        saveButton.setForeground(Color.WHITE);
        saveButton.putClientProperty(FlatClientProperties.STYLE,
                "font: bold; arc: 8; margin: 6,14,6,14; borderWidth: 0; focusWidth: 0; background: " + accentHex
                        + "; hoverBackground: darken(" + accentHex + ", 8%); pressedBackground: darken(" + accentHex
                        + ", 14%)");

        geminiApiKeyField.putClientProperty(FlatClientProperties.STYLE, "showRevealButton: true; arc: 8");

        getApiKeyLinkButton.putClientProperty(FlatClientProperties.STYLE,
                "font: -1; arc: 8; margin: 2,8,2,8; borderWidth: 0; background: null; hoverBackground: fade("
                        + accentHex + ", 14%); pressedBackground: fade(" + accentHex + ", 22%)");
        getApiKeyLinkButton.setForeground(accentColor);
        getApiKeyLinkButton.setIcon(FontIcon.of(Codicons.LINK_EXTERNAL, 13, accentColor));

        testConnectionButton.setIcon(FontIcon.of(Codicons.PLUG, 14, accentColor));
        testConnectionButton.putClientProperty(FlatClientProperties.STYLE, "arc: 8; margin: 5,12,5,12");
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (subtitleLabel != null) {
            applyThemeStyling();
        }
        if (onboardingBannerPanel != null) {
            updateOnboardingBannerState();
        }
    }

    boolean isApiKeyConfigured() {
        if (settingsService == null) {
            return false;
        }
        if (settingsService.hasConfiguredApiKey()) {
            return true;
        }
        AppSettings current = settingsService.getSettings();
        return current != null && current.getGeminiApiKey() != null && !current.getGeminiApiKey().isBlank();
    }

    void updateOnboardingBannerState() {
        boolean showOnboarding = !isApiKeyConfigured();
        if (onboardingBannerPanel != null) {
            if (showOnboarding) {
                Color warnColor = SwingHtmlUtils.getUiColor(new Color(0xd9, 0x77, 0x06),
                        "Component.warning.borderColor", "Actions.Yellow");
                onboardingBannerIconLabel.setIcon(FontIcon.of(Codicons.WARNING, 18, warnColor));
                onboardingBannerMessageLabel.setText(ONBOARDING_API_KEY_NOTICE_TEXT);
                SwingHtmlUtils.applyTintedBannerStyle(onboardingBannerPanel, warnColor, 10, 14, 14);
            }
            onboardingBannerPanel.setVisible(showOnboarding);
            onboardingBannerPanel.revalidate();
            onboardingBannerPanel.repaint();
        }
    }

    @EventListener
    @SuppressWarnings("PMD.UnusedAssignment")
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
            String apiKey = event.settings().getGeminiApiKey();
            if (apiKey != null) {
                geminiApiKeyField.setText(apiKey);
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
            updateOnboardingBannerState();
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

    CompletableFuture<String> testConnection() {
        String apiKey = new String(geminiApiKeyField.getPassword()).trim();
        if (apiKey.isEmpty()) {
            showStatusMessage(CONNECTION_FAILED_PREFIX + "Please enter a Gemini API Key first.", false);
            return CompletableFuture.completedFuture(null);
        }
        GeminiModelConfig selectedModel = (GeminiModelConfig) modelCombo.getSelectedItem();
        String modelName;
        if (selectedModel != null) {
            modelName = selectedModel.apiName();
        } else {
            modelName = settingsService.getSettings().getGeminiModel();
        }

        testConnectionButton.setEnabled(false);
        testConnectionButton.setText(TESTING_CONNECTION_TEXT);

        CompletableFuture<String> future = Edt.supplyAsync(() -> {
            if (aiService == null) {
                throw new IllegalStateException("AI service is unavailable");
            }
            return aiService.testConnection(apiKey, modelName);
        }, response -> {
            testConnectionButton.setEnabled(true);
            testConnectionButton.setText(TEST_CONNECTION_TEXT);
            showStatusMessage(CONNECTION_SUCCESS_TEXT, true);
        }, error -> {
            testConnectionButton.setEnabled(true);
            testConnectionButton.setText(TEST_CONNECTION_TEXT);
            showStatusMessage(CONNECTION_FAILED_PREFIX + ExceptionUtils.getRootCauseMessage(error), false);
        });
        lastAsyncFuture = future;
        return future;
    }

    private void showStatusMessage(String message, boolean success) {
        Color statusColor;
        if (success) {
            statusColor = SwingHtmlUtils.getUiColor(new Color(0x28, 0xa7, 0x45), "Actions.Green");
        } else {
            statusColor = SwingHtmlUtils.getUiColor(new Color(0xdc, 0x35, 0x45), "Actions.Red");
        }
        statusLabel.setForeground(statusColor);
        statusLabel.setText(message);
        statusTimer.restart();
    }

    private void saveSettings() {
        commitSpinnerEdit(maxAgentToolTurnsSpinner);
        commitSpinnerEdit(maxMessagesSpinner);

        boolean wasApiKeyConfigured = isApiKeyConfigured();
        AppSettings settings = settingsService.getSettings();
        String newApiKey = new String(geminiApiKeyField.getPassword());
        int newMaxMessages = (Integer) maxMessagesSpinner.getValue();

        settings.setGeminiApiKey(newApiKey);
        GeminiModelConfig selectedModel = (GeminiModelConfig) modelCombo.getSelectedItem();
        if (selectedModel != null) {
            settings.setGeminiModel(selectedModel.apiName());
        }
        settings.setMaxAgentToolTurns((Integer) maxAgentToolTurnsSpinner.getValue());
        settings.setMaxChatMemoryMessages(newMaxMessages);
        settings.setLogToolCalls(logToolCallsCheckBox.isSelected());
        settingsService.saveSettings();
        updateOnboardingBannerState();

        showStatusMessage(SAVED_APPLIED_TEXT, true);
        if (!wasApiKeyConfigured && !newApiKey.isBlank() && onInitialApiKeyConfiguredCallback != null) {
            onInitialApiKeyConfiguredCallback.run();
        }
    }

    void setOnInitialApiKeyConfiguredCallback(Runnable callback) {
        this.onInitialApiKeyConfiguredCallback = callback;
    }

    void setUrlOpener(Consumer<String> urlOpener) {
        this.urlOpener = Objects.requireNonNull(urlOpener, "urlOpener must not be null");
    }

    public static void applyTheme(String themeName) {
        Edt.runAndWait(() -> {
            try {
                UIManager.put("TabbedPane.tabType", "card");
                UIManager.put("TabbedPane.showTabSeparators", true);
                String themeToApply = Objects.requireNonNullElse(themeName, "");
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
