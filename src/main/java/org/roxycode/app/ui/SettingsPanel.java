package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.formdev.flatlaf.util.SystemFileChooser;
import java.awt.*;
import java.io.File;
import java.text.ParseException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.AiConfig.GenAiConnectionConfig;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.GeminiModelConfig;
import org.roxycode.app.model.ThinkingLevel;
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
    static final String ONBOARDING_API_KEY_NOTICE_TEXT = "Welcome to RoxyCode! Configure your Gemini API Key or Google Cloud Vertex AI credentials below and click 'Save Settings' to get started.";
    static final String AUTH_PROVIDER_API_KEY = "Gemini API Key (Google AI Studio)";
    static final String AUTH_PROVIDER_VERTEX_AI = "Google Cloud Vertex AI";
    static final String GET_API_KEY_BUTTON_TEXT = "Get a Gemini API Key";
    static final String GEMINI_API_KEY_URL = "https://aistudio.google.com/apikey";
    static final String BROWSE_CREDENTIALS_TEXT = "Browse...";
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
    final JComboBox<ThinkingLevel> thinkingLevelCombo;
    final JLabel thinkingLevelInfoIcon = UiComponents.createInfoIcon(UiComponents.THINKING_LEVEL_TOOLTIP_HTML);
    final JLabel modelApiBadgeLabel;
    final JTextArea modelDescriptionArea;
    final JSpinner maxMessagesSpinner;
    final JComboBox<String> authProviderCombo;
    final JPanel apiKeyPanel;
    final JPanel vertexAiPanel;
    final JPasswordField geminiApiKeyField;
    final JButton getApiKeyLinkButton;
    final JTextField vertexProjectIdField;
    final JTextField vertexLocationField;
    final JTextField vertexCredentialsPathField;
    final JButton browseCredentialsButton;
    final JButton testConnectionButton;
    final JProgressBar testConnectionProgressBar;
    final JLabel testConnectionStatusLabel;
    final Timer testConnectionStatusTimer;
    final JSpinner maxAgentToolTurnsSpinner;
    final JCheckBox logToolCallsCheckBox;
    final JCheckBox googleGroundingCheckBox;
    final UiComponents.WarningBanner onboardingBanner;
    final JButton saveButton;
    final JLabel statusLabel;
    final Timer statusTimer;
    private JLabel appearanceHeaderLabel;
    private JLabel aiModelHeaderLabel;
    private JLabel agentExecutionHeaderLabel;
    private transient Consumer<String> urlOpener = UiComponents::openUrlOrCopyToClipboard;
    private transient Supplier<File> credentialsFileChooser = this::chooseServiceAccountJsonFile;
    private transient Runnable onInitialApiKeyConfiguredCallback;
    transient CompletableFuture<?> lastAsyncFuture = CompletableFuture.completedFuture(null);

    private final transient UiComponents.UiGuard settingsUpdateGuard = new UiComponents.UiGuard();

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

        saveButton = UiComponents.createActionButton("Save Settings", Codicons.SAVE, new Insets(6, 14, 6, 14), false,
                "arc: 8; font: bold; " + UiComponents.ACCENT_BUTTON_STYLE, e -> saveSettings());
        saveButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        saveButton.setIconTextGap(6);

        JPanel headerActions = new JPanel(new MigLayout("insets 0, gapx 12", "[][]", "[center]"));
        headerActions.setOpaque(false);
        headerActions.add(statusLabel);
        headerActions.add(saveButton);

        add(UiComponents.createPageHeader(titleLabel, subtitleLabel, headerActions), "growx, wmin 0, wrap");

        onboardingBanner = new UiComponents.WarningBanner(ONBOARDING_API_KEY_NOTICE_TEXT);
        add(onboardingBanner, "growx, wmin 0, wrap, gaptop 8, hidemode 3");

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

        thinkingLevelCombo = new JComboBox<>(ThinkingLevel.values());
        thinkingLevelCombo.setSelectedItem(SettingsService.resolveGlobalThinkingLevel(settingsService));

        authProviderCombo = new JComboBox<>(new String[]{AUTH_PROVIDER_API_KEY, AUTH_PROVIDER_VERTEX_AI});
        if (settingsService.getSettings().isVertexAiEnabled()) {
            authProviderCombo.setSelectedItem(AUTH_PROVIDER_VERTEX_AI);
        } else {
            authProviderCombo.setSelectedItem(AUTH_PROVIDER_API_KEY);
        }
        authProviderCombo.addActionListener(e -> updateAuthProviderVisibility());

        apiKeyPanel = new JPanel(new MigLayout("fillx, insets 0, wrap 1, gapy 8, hidemode 3", GROW_FILL, ""));
        apiKeyPanel.setOpaque(false);

        vertexAiPanel = new JPanel(new MigLayout("fillx, insets 0, wrap 1, gapy 10, hidemode 3", GROW_FILL, ""));
        vertexAiPanel.setOpaque(false);

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

        vertexProjectIdField = new JTextField(24);
        vertexProjectIdField.setText(settingsService.getSettings().getVertexProjectId());
        vertexProjectIdField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "e.g. my-gcp-project");
        SwingHtmlUtils.installTextContextMenu(vertexProjectIdField);

        vertexLocationField = new JTextField(18);
        vertexLocationField.setText(settingsService.getSettings().getVertexLocation());
        vertexLocationField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "e.g. global or us-central1");
        SwingHtmlUtils.installTextContextMenu(vertexLocationField);

        vertexCredentialsPathField = new JTextField(24);
        vertexCredentialsPathField.setText(settingsService.getSettings().getVertexCredentialsPath());
        vertexCredentialsPathField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                "Leave blank to use Google Cloud Application Default Credentials (ADC)");
        SwingHtmlUtils.installTextContextMenu(vertexCredentialsPathField);

        browseCredentialsButton = new JButton(BROWSE_CREDENTIALS_TEXT);
        browseCredentialsButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        browseCredentialsButton.setIconTextGap(6);
        browseCredentialsButton.addActionListener(e -> browseServiceAccountCredentials());

        testConnectionButton = new JButton(TEST_CONNECTION_TEXT);
        testConnectionButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        testConnectionButton.setIconTextGap(6);
        testConnectionButton.addActionListener(e -> testConnection());

        testConnectionProgressBar = new JProgressBar();
        testConnectionProgressBar.setVisible(false);

        testConnectionStatusLabel = new JLabel("");
        testConnectionStatusLabel.setFont(testConnectionStatusLabel.getFont().deriveFont(Font.BOLD, 11.5f));
        testConnectionStatusLabel.setVisible(false);

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

        googleGroundingCheckBox = new JCheckBox("Enable Google Search Grounding for real-time information retrieval");
        googleGroundingCheckBox.setOpaque(false);
        googleGroundingCheckBox.setSelected(settingsService.getSettings().isGoogleGroundingEnabled());

        JPanel cardsContainer = UiComponents
                .createScrollableCardsPanel(new MigLayout("fillx, wrap 1, insets 4 2 16 6, gapy 14", GROW_FILL, ""));
        cardsContainer.add(buildAppearanceCard(), GROWX_WMIN_0);
        cardsContainer.add(buildAiModelCard(), GROWX_WMIN_0);
        cardsContainer.add(buildAgentExecutionCard(), GROWX_WMIN_0);

        add(UiComponents.createBorderlessScrollPane(cardsContainer), "grow, wmin 0");

        statusTimer = UiComponents.createStatusTimer(statusLabel, 3000);
        testConnectionStatusTimer = new Timer(5000, e -> clearTestConnectionStatus());
        testConnectionStatusTimer.setRepeats(false);

        updateAuthProviderVisibility();
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

        card.add(
                UiComponents.createFieldRow(
                        UiComponents.createFieldTextBlock("Gemini Model",
                                "Default Gemini model used for Ask Roxy and Build Feature turns.", null),
                        modelControlBox, ""),
                GROWX_WMIN_0);

        card.add(modelDescriptionArea, "growx, wmin 0, gaptop 2, hidemode 3");
        card.add(createRowSeparator(), SEPARATOR_CONSTRAINTS);

        card.add(UiComponents.createFieldRow(UiComponents.createFieldTextBlock("Thinking Level",
                "Default reasoning effort used by Gemini models when generating responses.", thinkingLevelInfoIcon),
                thinkingLevelCombo, CONTROL_WIDTH_CONSTRAINTS), GROWX_WMIN_0);

        card.add(createRowSeparator(), SEPARATOR_CONSTRAINTS);

        card.add(UiComponents.createFieldRow(UiComponents.createFieldTextBlock("Google Search Grounding",
                "Allow Gemini to use Google Search to ground its responses with up-to-date web information.", null),
                googleGroundingCheckBox, ""), GROWX_WMIN_0);

        card.add(createRowSeparator(), SEPARATOR_CONSTRAINTS);

        card.add(UiComponents.createFieldRow(
                UiComponents.createFieldTextBlock("Authentication Provider",
                        "Choose between Google AI Studio (Gemini API Key) and Google Cloud Vertex AI.", null),
                authProviderCombo, "w 260:280:300"), GROWX_WMIN_0);

        apiKeyPanel.add(UiComponents.createFieldRow(UiComponents.createFieldTextBlock("Gemini API Key",
                "Google GenAI API key used to authenticate model requests (applies immediately on save).", null),
                getApiKeyLinkButton, ""), GROWX_WMIN_0);
        apiKeyPanel.add(geminiApiKeyField, "w 280:420:520");

        vertexAiPanel.add(UiComponents.createFieldRow(
                UiComponents.createFieldTextBlock("GCP Project ID",
                        "Google Cloud project ID with the Vertex AI API enabled.", null),
                vertexProjectIdField, "w 240:280:320"), GROWX_WMIN_0);
        vertexAiPanel.add(UiComponents.createFieldRow(
                UiComponents.createFieldTextBlock("Vertex Location",
                        "Google Cloud region or 'global' endpoint for Vertex AI model calls.", null),
                vertexLocationField, "w 240:280:320"), GROWX_WMIN_0);

        JPanel credentialsRow = new JPanel(new MigLayout("fillx, insets 0, wrap 1, gapy 6", GROW_FILL, ""));
        credentialsRow.setOpaque(false);
        credentialsRow.add(UiComponents.createFieldTextBlock("Service Account JSON (Optional)",
                "Path to a GCP Service Account JSON key file. Leave blank to use Application Default Credentials (gcloud auth application-default login).",
                null), GROWX_WMIN_0);
        JPanel credentialsInputRow = new JPanel(new MigLayout("insets 0, gapx 8", "[grow, fill][]", "[center]"));
        credentialsInputRow.setOpaque(false);
        credentialsInputRow.add(vertexCredentialsPathField, "w 260:400:520");
        credentialsInputRow.add(browseCredentialsButton);
        credentialsRow.add(credentialsInputRow, GROWX_WMIN_0);
        vertexAiPanel.add(credentialsRow, GROWX_WMIN_0);

        card.add(apiKeyPanel, "growx, wmin 0, hidemode 3");
        card.add(vertexAiPanel, "growx, wmin 0, hidemode 3");

        JPanel testActionRow = new JPanel(
                new MigLayout("insets 2 0 0 0, gapx 10, hidemode 3", "[][140:180:220][grow, fill]", "[center]"));
        testActionRow.setOpaque(false);
        testActionRow.add(testConnectionButton);
        testActionRow.add(testConnectionProgressBar, "growx, hidemode 3");
        testActionRow.add(testConnectionStatusLabel, "growx, wmin 0, hidemode 3");
        card.add(testActionRow, GROWX_WMIN_0);
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
        UiComponents.updateInfoIconTheme(thinkingLevelInfoIcon);

        String accentHex = SwingHtmlUtils.toHex(accentColor);
        UiComponents.refreshActionButtonIcons(this);

        geminiApiKeyField.putClientProperty(FlatClientProperties.STYLE, "showRevealButton: true; arc: 8");
        vertexProjectIdField.putClientProperty(FlatClientProperties.STYLE, "arc: 8");
        vertexLocationField.putClientProperty(FlatClientProperties.STYLE, "arc: 8");
        vertexCredentialsPathField.putClientProperty(FlatClientProperties.STYLE, "arc: 8");

        getApiKeyLinkButton.putClientProperty(FlatClientProperties.STYLE,
                "font: -1; arc: 8; margin: 2,8,2,8; borderWidth: 0; background: null; hoverBackground: fade("
                        + accentHex + ", 14%); pressedBackground: fade(" + accentHex + ", 22%)");
        getApiKeyLinkButton.setForeground(accentColor);
        getApiKeyLinkButton.setIcon(FontIcon.of(Codicons.LINK_EXTERNAL, 13, accentColor));

        browseCredentialsButton.setIcon(FontIcon.of(Codicons.FOLDER_OPENED, 14, accentColor));
        browseCredentialsButton.putClientProperty(FlatClientProperties.STYLE, "arc: 8; margin: 5,10,5,10");

        testConnectionButton.setIcon(FontIcon.of(Codicons.PLUG, 14, accentColor));
        testConnectionButton.putClientProperty(FlatClientProperties.STYLE, "arc: 8; margin: 5,12,5,12");
        testConnectionProgressBar.putClientProperty(FlatClientProperties.STYLE,
                "arc: 6; foreground: $Component.accentColor");
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (subtitleLabel != null) {
            applyThemeStyling();
        }
        if (onboardingBanner != null) {
            updateOnboardingBannerState();
        }
    }

    boolean isVertexAiSelected() {
        return AUTH_PROVIDER_VERTEX_AI.equals(authProviderCombo.getSelectedItem());
    }

    void updateAuthProviderVisibility() {
        boolean vertexSelected = isVertexAiSelected();
        apiKeyPanel.setVisible(!vertexSelected);
        vertexAiPanel.setVisible(vertexSelected);
        if (testConnectionStatusLabel != null) {
            clearTestConnectionStatus();
        }
        revalidate();
        repaint();
    }

    private File chooseServiceAccountJsonFile() {
        SystemFileChooser.FileNameExtensionFilter jsonFilter = new SystemFileChooser.FileNameExtensionFilter(
                "Service Account JSON (*.json)", "json");
        return UiComponents.chooseOpenFile(this, "Select GCP Service Account JSON Key", jsonFilter);
    }

    void browseServiceAccountCredentials() {
        File selected = credentialsFileChooser.get();
        if (selected != null) {
            vertexCredentialsPathField.setText(selected.getAbsolutePath());
        }
    }

    boolean hasConfiguredCredentials() {
        if (settingsService == null) {
            return false;
        }
        if (settingsService.hasConfiguredCredentials()) {
            return true;
        }
        return SettingsService.hasConfiguredCredentials(settingsService.getSettings());
    }

    void updateOnboardingBannerState() {
        onboardingBanner.setVisibleState(!hasConfiguredCredentials(), ONBOARDING_API_KEY_NOTICE_TEXT);
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        if (event == null || event.settings() == null) {
            return;
        }
        Edt.run(() -> {
            String theme = event.settings().getTheme();
            if (theme != null && !theme.equals(themeCombo.getSelectedItem())) {
                settingsUpdateGuard.run(() -> themeCombo.setSelectedItem(theme));
            }
            String targetProvider;
            if (event.settings().isVertexAiEnabled()) {
                targetProvider = AUTH_PROVIDER_VERTEX_AI;
            } else {
                targetProvider = AUTH_PROVIDER_API_KEY;
            }
            if (!targetProvider.equals(authProviderCombo.getSelectedItem())) {
                authProviderCombo.setSelectedItem(targetProvider);
            }
            updateAuthProviderVisibility();
            String apiKey = event.settings().getGeminiApiKey();
            if (apiKey != null) {
                geminiApiKeyField.setText(apiKey);
            }
            vertexProjectIdField.setText(event.settings().getVertexProjectId());
            vertexLocationField.setText(event.settings().getVertexLocation());
            vertexCredentialsPathField.setText(event.settings().getVertexCredentialsPath());
            String modelId = event.settings().getGeminiModel();
            if (modelId != null) {
                GeminiModelConfig selected = (GeminiModelConfig) modelCombo.getSelectedItem();
                if (selected == null || !modelId.equals(selected.apiName())) {
                    UiComponents.selectModelByApiName(modelCombo, modelId);
                }
            }
            ThinkingLevel targetThinking = ThinkingLevel.fromString(event.settings().getThinkingLevel());
            if (targetThinking != thinkingLevelCombo.getSelectedItem()) {
                thinkingLevelCombo.setSelectedItem(targetThinking);
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
            boolean groundingEnabled = event.settings().isGoogleGroundingEnabled();
            if (groundingEnabled != googleGroundingCheckBox.isSelected()) {
                googleGroundingCheckBox.setSelected(groundingEnabled);
            }
            updateOnboardingBannerState();
        });
    }

    private void updateTheme() {
        if (settingsUpdateGuard.isActive()) {
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
        boolean vertexSelected = isVertexAiSelected();
        String apiKey = new String(geminiApiKeyField.getPassword()).trim();
        String projectId = vertexProjectIdField.getText().trim();
        String location = vertexLocationField.getText().trim();
        String credentialsPath = vertexCredentialsPathField.getText().trim();

        if (vertexSelected) {
            if (projectId.isEmpty()) {
                showTestConnectionStatus(CONNECTION_FAILED_PREFIX + "Please enter a GCP Project ID first.", false);
                return CompletableFuture.completedFuture(null);
            }
            if (location.isEmpty()) {
                showTestConnectionStatus(CONNECTION_FAILED_PREFIX + "Please enter a Vertex Location first.", false);
                return CompletableFuture.completedFuture(null);
            }
        } else if (apiKey.isEmpty()) {
            showTestConnectionStatus(CONNECTION_FAILED_PREFIX + "Please enter a Gemini API Key first.", false);
            return CompletableFuture.completedFuture(null);
        }

        GeminiModelConfig selectedModel = (GeminiModelConfig) modelCombo.getSelectedItem();
        String modelName;
        if (selectedModel != null) {
            modelName = selectedModel.apiName();
        } else {
            modelName = settingsService.getSettings().getGeminiModel();
        }

        GenAiConnectionConfig connectionConfig;
        if (vertexSelected) {
            connectionConfig = GenAiConnectionConfig.forVertexAi(projectId, location, credentialsPath);
        } else {
            connectionConfig = GenAiConnectionConfig.forApiKey(apiKey);
        }

        clearTestConnectionStatus();
        testConnectionButton.setEnabled(false);
        testConnectionButton.setText(TESTING_CONNECTION_TEXT);
        testConnectionProgressBar.setIndeterminate(true);
        testConnectionProgressBar.setVisible(true);

        CompletableFuture<String> future = Edt.supplyAsync(() -> {
            if (aiService == null) {
                throw new IllegalStateException("AI service is unavailable");
            }
            return aiService.testConnection(connectionConfig, modelName);
        }, response -> {
            restoreTestConnectionButtonState();
            showTestConnectionStatus(CONNECTION_SUCCESS_TEXT, true);
        }, error -> {
            restoreTestConnectionButtonState();
            showTestConnectionStatus(CONNECTION_FAILED_PREFIX + ExceptionUtils.getRootCauseMessage(error), false);
        });
        lastAsyncFuture = future;
        return future;
    }

    private void restoreTestConnectionButtonState() {
        testConnectionButton.setEnabled(true);
        testConnectionButton.setText(TEST_CONNECTION_TEXT);
        testConnectionProgressBar.setIndeterminate(false);
        testConnectionProgressBar.setVisible(false);
    }

    void showTestConnectionStatus(String message, boolean success) {
        testConnectionStatusLabel.setVisible(true);
        UiComponents.showStatusMessage(testConnectionStatusLabel, testConnectionStatusTimer, message, success);
    }

    void clearTestConnectionStatus() {
        UiComponents.clearStatusMessage(testConnectionStatusLabel, testConnectionStatusTimer, true);
    }

    private void showStatusMessage(String message, boolean success) {
        UiComponents.showStatusMessage(statusLabel, statusTimer, message, success);
    }

    private void saveSettings() {
        commitSpinnerEdit(maxAgentToolTurnsSpinner);
        commitSpinnerEdit(maxMessagesSpinner);

        boolean hadConfiguredCredentials = hasConfiguredCredentials();
        AppSettings settings = settingsService.getSettings();
        String newApiKey = new String(geminiApiKeyField.getPassword());
        boolean vertexEnabled = isVertexAiSelected();
        String newProjectId = vertexProjectIdField.getText().trim();
        String newLocation = vertexLocationField.getText().trim();
        String newCredentialsPath = vertexCredentialsPathField.getText().trim();
        int newMaxMessages = (Integer) maxMessagesSpinner.getValue();

        settings.setGeminiApiKey(newApiKey);
        settings.setVertexAiEnabled(vertexEnabled);
        settings.setVertexProjectId(newProjectId);
        settings.setVertexLocation(newLocation);
        settings.setVertexCredentialsPath(newCredentialsPath);
        GeminiModelConfig selectedModel = (GeminiModelConfig) modelCombo.getSelectedItem();
        if (selectedModel != null) {
            settings.setGeminiModel(selectedModel.apiName());
        }
        ThinkingLevel selectedThinkingLevel = (ThinkingLevel) thinkingLevelCombo.getSelectedItem();
        if (selectedThinkingLevel != null) {
            settings.setThinkingLevel(selectedThinkingLevel.getId());
        }
        settings.setMaxAgentToolTurns((Integer) maxAgentToolTurnsSpinner.getValue());
        settings.setMaxChatMemoryMessages(newMaxMessages);
        settings.setLogToolCalls(logToolCallsCheckBox.isSelected());
        settings.setGoogleGroundingEnabled(googleGroundingCheckBox.isSelected());
        settingsService.saveSettings();
        updateOnboardingBannerState();

        showStatusMessage(SAVED_APPLIED_TEXT, true);
        if (!hadConfiguredCredentials && hasConfiguredCredentials() && onInitialApiKeyConfiguredCallback != null) {
            onInitialApiKeyConfiguredCallback.run();
        }
    }

    void setOnInitialApiKeyConfiguredCallback(Runnable callback) {
        this.onInitialApiKeyConfiguredCallback = callback;
    }

    void setUrlOpener(Consumer<String> urlOpener) {
        this.urlOpener = Objects.requireNonNull(urlOpener, "urlOpener must not be null");
    }

    void setCredentialsFileChooser(Supplier<File> credentialsFileChooser) {
        this.credentialsFileChooser = Objects.requireNonNull(credentialsFileChooser,
                "credentialsFileChooser must not be null");
    }

    public static void applyTheme(String themeName) {
        Edt.runAndWait(() -> {
            try {
                UIManager.put("TabbedPane.tabType", "card");
                UIManager.put("TabbedPane.showTabSeparators", true);
                UIManager.put("SplitPaneDivider.style", "grip");
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
