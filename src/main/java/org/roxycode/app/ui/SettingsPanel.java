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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.AiConfig.GenAiConnectionConfig;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AgentProfile;
import org.roxycode.app.model.AppSettings;
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

    static final String SUBTITLE_TEXT = "Configure authentication credentials, default phase-aware model profile, and autonomous execution limits.";
    static final String SAVED_APPLIED_TEXT = "Settings saved and applied.";
    static final String SAVE_SETTINGS_TEXT = "Save Settings";
    static final String SAVE_CHANGES_DIRTY_TEXT = "Save Changes";
    static final String ONBOARDING_API_KEY_NOTICE_TEXT = "Welcome to RoxyCode! Configure your Gemini API Key or Google Cloud Vertex AI credentials below and click 'Save Settings' to get started.";
    static final String AUTH_PROVIDER_API_KEY = "Gemini API Key (Google AI Studio)";
    static final String AUTH_PROVIDER_VERTEX_AI = "Google Cloud Vertex AI";
    static final String AUTH_TOGGLE_API_KEY_TEXT = "Google AI Studio (API Key)";
    static final String AUTH_TOGGLE_VERTEX_TEXT = "Google Cloud Vertex AI";
    static final String GET_API_KEY_BUTTON_TEXT = "Get a Gemini API Key";
    static final String GEMINI_API_KEY_URL = "https://aistudio.google.com/apikey";
    static final String BROWSE_CREDENTIALS_TEXT = "Browse...";
    static final String TEST_CONNECTION_TEXT = "Test Connection";
    static final String TESTING_CONNECTION_TEXT = "Testing...";
    static final String CONNECTION_SUCCESS_TEXT = "Connection verified!";
    static final String CONNECTION_FAILED_PREFIX = "Connection failed: ";
    static final String OPEN_LOG_BUTTON_TEXT = "Open Log";
    static final String TOOL_LOG_DISPLAY_PATH = "~/.roxycode/logs/toolcalls.jsonl";
    static final String SECTION_AUTH_TITLE = "Authentication & Provider Connection";
    static final String SECTION_PROFILE_TITLE = "Default Agent Profile (Phase-Aware Model & Adaptive Thinking)";
    static final String SECTION_APPEARANCE_TITLE = "Appearance & Diagnostics";
    static final String SECTION_AGENT_EXECUTION_TITLE = "Agent Execution & Grounding";
    private static final String GROW_FILL = "[grow, fill]";
    private static final String GROWX_WMIN_0 = "growx, wmin 0";
    private static final String GROW_WMIN_0 = "grow, wmin 0";
    private static final String WMIN_0 = "wmin 0";
    private static final String ROW_CENTER = "[center]";
    private static final String SEPARATOR_CONSTRAINTS = "growx, gaptop 4, gapbottom 4";
    private static final String CARD_LAYOUT_CONSTRAINTS = "fillx, wrap 1, insets 14 16 14 16, gapy 8";
    private static final String HEADER_FONT_STYLE = "font: bold +2";
    private static final String HEADER_CONSTRAINTS = "growx, wmin 0, gapbottom 2";
    private static final String SAVE_BUTTON_BASE_STYLE = "arc: 8; font: bold; ";
    static final int MIN_AGENT_TOOL_TURNS = 1;
    static final int MAX_AGENT_TOOL_TURNS = 100;
    static final int MIN_CHAT_MEMORY_MESSAGES = 1;
    static final int MAX_CHAT_MEMORY_MESSAGES = 500;

    private record SettingsSnapshot(String theme, boolean vertexEnabled, String apiKey, String vertexProjectId,
            String vertexLocation, String vertexCredentialsPath, String agentProfileId, int maxAgentToolTurns,
            int maxChatMemoryMessages, boolean googleGroundingEnabled) {
    }

    private final transient SettingsService settingsService;
    private final transient AiService aiService;
    private final JLabel titleLabel;
    private final JLabel subtitleLabel;
    final JComboBox<String> themeCombo;
    final JComboBox<AgentProfile> profileCombo;
    final JLabel profileInfoIcon = UiComponents.createInfoIcon(UiComponents.AGENT_PROFILE_TOOLTIP_HTML);
    final JLabel modelApiBadgeLabel;
    final JTextArea modelDescriptionArea;
    final Map<AgentProfile, ProfileCard> profileCards = new EnumMap<>(AgentProfile.class);
    final JSpinner maxMessagesSpinner;
    final JComboBox<String> authProviderCombo;
    final JToggleButton apiKeyProviderToggleBtn;
    final JToggleButton vertexAiProviderToggleBtn;
    final ButtonGroup authProviderToggleGroup;
    final JPanel segmentedProviderPanel;
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
    final JLabel toolLogPathLabel;
    final JButton openToolLogButton;
    final JCheckBox googleGroundingCheckBox;
    final UiComponents.WarningBanner onboardingBanner;
    final JButton saveButton;
    final JLabel statusLabel;
    final Timer statusTimer;
    private JLabel authHeaderLabel;
    private JLabel profileHeaderLabel;
    private JLabel appearanceHeaderLabel;
    private JLabel agentExecutionHeaderLabel;
    private transient SettingsSnapshot savedBaseline;
    private transient Consumer<String> urlOpener = UiComponents::openUrlOrCopyToClipboard;
    private transient Supplier<File> credentialsFileChooser = this::chooseServiceAccountJsonFile;
    private transient Consumer<Path> logFileOpener = SettingsPanel::openLogFileOnDesktop;
    private transient Runnable onInitialApiKeyConfiguredCallback;
    transient CompletableFuture<?> lastAsyncFuture = CompletableFuture.completedFuture(null);

    private final transient UiComponents.UiGuard settingsUpdateGuard = new UiComponents.UiGuard();

    @Autowired
    public SettingsPanel(SettingsService settingsService, @Nullable AiService aiService) {
        this.settingsService = settingsService;
        this.aiService = aiService;
        setLayout(new MigLayout("fill, insets 18, hidemode 3", GROW_FILL, "[][]10" + GROW_FILL));
        setOpaque(true);

        titleLabel = new JLabel("Global Settings & AI Configuration");
        subtitleLabel = new JLabel(SUBTITLE_TEXT);
        statusLabel = new JLabel("");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 11.5f));

        saveButton = UiComponents.createActionButton(SAVE_SETTINGS_TEXT, Codicons.SAVE, new Insets(6, 14, 6, 14), false,
                SAVE_BUTTON_BASE_STYLE + UiComponents.SUBTLE_GREEN_BUTTON_STYLE, e -> saveSettings());
        saveButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        saveButton.setIconTextGap(6);

        JPanel headerActions = new JPanel(new MigLayout("insets 0, gapx 12", "[][]", ROW_CENTER));
        headerActions.setOpaque(false);
        headerActions.add(statusLabel);
        headerActions.add(saveButton);

        add(UiComponents.createPageHeader(titleLabel, subtitleLabel, headerActions), "growx, wmin 0, wrap");

        onboardingBanner = new UiComponents.WarningBanner(ONBOARDING_API_KEY_NOTICE_TEXT);
        add(onboardingBanner, "growx, wmin 0, wrap, gaptop 6, hidemode 3");

        themeCombo = new JComboBox<>(
                new String[]{"FlatLaf Light", "FlatLaf Dark", "IntelliJ", "Darcula", "macOS Light", "macOS Dark"});
        themeCombo.setSelectedItem(settingsService.getSettings().getTheme());
        themeCombo.addActionListener(e -> {
            updateTheme();
            updateSaveButtonState();
        });

        profileCombo = new JComboBox<>(AgentProfile.values());
        modelApiBadgeLabel = new JLabel();
        UiComponents.styleCodePillBadge(modelApiBadgeLabel);

        modelDescriptionArea = UiComponents.createReadOnlyTextArea();
        loadProfiles();
        profileCombo.addActionListener(e -> {
            updateSelectedProfileDetails();
            updateSaveButtonState();
        });

        authProviderCombo = new JComboBox<>(new String[]{AUTH_PROVIDER_API_KEY, AUTH_PROVIDER_VERTEX_AI});
        if (settingsService.getSettings().isVertexAiEnabled()) {
            authProviderCombo.setSelectedItem(AUTH_PROVIDER_VERTEX_AI);
        } else {
            authProviderCombo.setSelectedItem(AUTH_PROVIDER_API_KEY);
        }
        authProviderCombo.addActionListener(e -> {
            updateAuthProviderVisibility();
            updateSaveButtonState();
        });

        apiKeyProviderToggleBtn = new JToggleButton(AUTH_TOGGLE_API_KEY_TEXT);
        vertexAiProviderToggleBtn = new JToggleButton(AUTH_TOGGLE_VERTEX_TEXT);
        authProviderToggleGroup = new ButtonGroup();
        authProviderToggleGroup.add(apiKeyProviderToggleBtn);
        authProviderToggleGroup.add(vertexAiProviderToggleBtn);
        configureProviderToggleButton(apiKeyProviderToggleBtn, AUTH_PROVIDER_API_KEY);
        configureProviderToggleButton(vertexAiProviderToggleBtn, AUTH_PROVIDER_VERTEX_AI);

        segmentedProviderPanel = new JPanel(new MigLayout("insets 3, gapx 4", "[sg seg][sg seg]", ROW_CENTER));
        segmentedProviderPanel.add(apiKeyProviderToggleBtn);
        segmentedProviderPanel.add(vertexAiProviderToggleBtn);

        apiKeyPanel = new JPanel(new MigLayout("fillx, insets 0, wrap 1, gapy 8, hidemode 3", GROW_FILL, ""));
        apiKeyPanel.setOpaque(false);

        vertexAiPanel = new JPanel(new MigLayout("fillx, insets 0, wrap 1, gapy 8, hidemode 3", GROW_FILL, ""));
        vertexAiPanel.setOpaque(false);

        geminiApiKeyField = new JPasswordField(24);
        geminiApiKeyField.setText(settingsService.getSettings().getGeminiApiKey());
        geminiApiKeyField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Enter your Gemini API key...");
        SwingHtmlUtils.installTextContextMenu(geminiApiKeyField);
        SwingHtmlUtils.addChangeListener(geminiApiKeyField, this::updateSaveButtonState);

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
        SwingHtmlUtils.addChangeListener(vertexProjectIdField, this::updateSaveButtonState);

        vertexLocationField = new JTextField(18);
        vertexLocationField.setText(settingsService.getSettings().getVertexLocation());
        vertexLocationField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "e.g. global or us-central1");
        SwingHtmlUtils.installTextContextMenu(vertexLocationField);
        SwingHtmlUtils.addChangeListener(vertexLocationField, this::updateSaveButtonState);

        vertexCredentialsPathField = new JTextField(24);
        vertexCredentialsPathField.setText(settingsService.getSettings().getVertexCredentialsPath());
        vertexCredentialsPathField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                "Leave blank to use Google Cloud Application Default Credentials (ADC)");
        SwingHtmlUtils.installTextContextMenu(vertexCredentialsPathField);
        SwingHtmlUtils.addChangeListener(vertexCredentialsPathField, this::updateSaveButtonState);

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
        maxAgentToolTurnsSpinner.addChangeListener(e -> updateSaveButtonState());

        int initialMaxMessages = Math.clamp(settingsService.getSettings().getMaxChatMemoryMessages(),
                MIN_CHAT_MEMORY_MESSAGES, MAX_CHAT_MEMORY_MESSAGES);
        maxMessagesSpinner = new JSpinner(
                new SpinnerNumberModel(initialMaxMessages, MIN_CHAT_MEMORY_MESSAGES, MAX_CHAT_MEMORY_MESSAGES, 5));
        maxMessagesSpinner.addChangeListener(e -> updateSaveButtonState());

        toolLogPathLabel = new JLabel(TOOL_LOG_DISPLAY_PATH);
        toolLogPathLabel.putClientProperty(FlatClientProperties.STYLE, "font: $monospaced.font -1");

        openToolLogButton = new JButton(OPEN_LOG_BUTTON_TEXT);
        openToolLogButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        openToolLogButton.setFocusable(false);
        openToolLogButton.setIconTextGap(5);
        openToolLogButton.addActionListener(e -> openToolCallLog());

        googleGroundingCheckBox = new JCheckBox("Enable Google Search Grounding (webSearchService)");
        googleGroundingCheckBox.setOpaque(false);
        googleGroundingCheckBox.setSelected(settingsService.getSettings().isGoogleGroundingEnabled());
        googleGroundingCheckBox.addActionListener(e -> updateSaveButtonState());

        JPanel cardsContainer = UiComponents
                .createScrollableCardsPanel(new MigLayout("fillx, wrap 1, insets 4 2 14 6, gapy 12", GROW_FILL, ""));
        cardsContainer.add(buildAuthConnectionCard(), GROWX_WMIN_0);
        cardsContainer.add(buildAgentProfileCard(), GROWX_WMIN_0);

        JPanel bottomRow = new JPanel(new MigLayout("fillx, insets 0, hidemode 3",
                "[grow, fill, sg bottom]14[grow, fill, sg bottom]", "[grow, fill]"));
        bottomRow.setOpaque(false);
        bottomRow.add(buildAgentExecutionCard(), GROW_WMIN_0);
        bottomRow.add(buildAppearanceCard(), GROW_WMIN_0);
        cardsContainer.add(bottomRow, GROWX_WMIN_0);

        add(UiComponents.createBorderlessScrollPane(cardsContainer), GROW_WMIN_0);

        statusTimer = UiComponents.createStatusTimer(statusLabel, 3000);
        testConnectionStatusTimer = new Timer(5000, e -> clearTestConnectionStatus());
        testConnectionStatusTimer.setRepeats(false);

        updateSelectedProfileDetails();
        updateAuthProviderVisibility();
        applyThemeStyling();
        updateOnboardingBannerState();
        captureSavedBaseline();
        updateSaveButtonState();
    }

    private void configureProviderToggleButton(JToggleButton button, String providerOption) {
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setFocusable(false);
        button.addActionListener(e -> {
            if (!providerOption.equals(authProviderCombo.getSelectedItem())) {
                authProviderCombo.setSelectedItem(providerOption);
            }
        });
    }

    private JPanel buildAuthConnectionCard() {
        JPanel card = new JPanel(new MigLayout(CARD_LAYOUT_CONSTRAINTS, GROW_FILL, ""));
        UiComponents.styleCardPanel(card);

        JPanel headerRow = new JPanel(new MigLayout("fillx, insets 0, gapx 10", "[grow, fill][right]", ROW_CENTER));
        headerRow.setOpaque(false);
        authHeaderLabel = UiComponents.createSectionHeader(SECTION_AUTH_TITLE, Codicons.KEY, HEADER_FONT_STYLE);
        headerRow.add(authHeaderLabel, WMIN_0);
        headerRow.add(segmentedProviderPanel, "right");
        card.add(headerRow, HEADER_CONSTRAINTS);
        card.add(createRowSeparator(), SEPARATOR_CONSTRAINTS);

        JPanel apiKeyRow = new JPanel(
                new MigLayout("fillx, insets 0, gapx 12", "[grow, fill][240:340:440][]", ROW_CENTER));
        apiKeyRow.setOpaque(false);
        apiKeyRow.add(UiComponents.createFieldTextBlock("Gemini API Key",
                "Stored locally in ~/.roxycode/settings.toml (0600 permissions).", null), GROWX_WMIN_0);
        apiKeyRow.add(geminiApiKeyField, "growx");
        apiKeyRow.add(getApiKeyLinkButton);
        apiKeyPanel.add(apiKeyRow, GROWX_WMIN_0);

        JPanel vertexFieldsGrid = new JPanel(
                new MigLayout("fillx, insets 0, gapx 14", "[grow, fill, sg vfield]14[grow, fill, sg vfield]", ""));
        vertexFieldsGrid.setOpaque(false);

        JPanel projectBlock = new JPanel(new MigLayout("fillx, insets 0, wrap 1, gapy 4", GROW_FILL, ""));
        projectBlock.setOpaque(false);
        projectBlock.add(UiComponents.createFieldTextBlock("GCP Project ID",
                "Google Cloud project ID with the Vertex AI API enabled.", null), GROWX_WMIN_0);
        projectBlock.add(vertexProjectIdField, GROWX_WMIN_0);

        JPanel locationBlock = new JPanel(new MigLayout("fillx, insets 0, wrap 1, gapy 4", GROW_FILL, ""));
        locationBlock.setOpaque(false);
        locationBlock.add(UiComponents.createFieldTextBlock("Vertex Location",
                "Google Cloud region or 'global' endpoint for Vertex AI model calls.", null), GROWX_WMIN_0);
        locationBlock.add(vertexLocationField, GROWX_WMIN_0);

        vertexFieldsGrid.add(projectBlock, GROWX_WMIN_0);
        vertexFieldsGrid.add(locationBlock, GROWX_WMIN_0);
        vertexAiPanel.add(vertexFieldsGrid, GROWX_WMIN_0);

        JPanel credentialsRow = new JPanel(new MigLayout("fillx, insets 0, wrap 1, gapy 4", GROW_FILL, ""));
        credentialsRow.setOpaque(false);
        credentialsRow.add(UiComponents.createFieldTextBlock("Service Account JSON (Optional)",
                "Path to a GCP Service Account JSON key file. Leave blank to use Application Default Credentials (ADC).",
                null), GROWX_WMIN_0);
        JPanel credentialsInputRow = new JPanel(new MigLayout("insets 0, gapx 8", "[grow, fill][]", ROW_CENTER));
        credentialsInputRow.setOpaque(false);
        credentialsInputRow.add(vertexCredentialsPathField, GROWX_WMIN_0);
        credentialsInputRow.add(browseCredentialsButton);
        credentialsRow.add(credentialsInputRow, GROWX_WMIN_0);
        vertexAiPanel.add(credentialsRow, GROWX_WMIN_0);

        card.add(apiKeyPanel, "growx, wmin 0, hidemode 3");
        card.add(vertexAiPanel, "growx, wmin 0, hidemode 3");

        JPanel testActionRow = new JPanel(
                new MigLayout("insets 2 0 0 0, gapx 10, hidemode 3", "[][120:160:200][grow, fill]", ROW_CENTER));
        testActionRow.setOpaque(false);
        testActionRow.add(testConnectionButton);
        testActionRow.add(testConnectionProgressBar, "growx, hidemode 3");
        testActionRow.add(testConnectionStatusLabel, "growx, wmin 0, hidemode 3");
        card.add(testActionRow, GROWX_WMIN_0);

        return card;
    }

    private JPanel buildAgentProfileCard() {
        JPanel card = new JPanel(new MigLayout(CARD_LAYOUT_CONSTRAINTS, GROW_FILL, ""));
        UiComponents.styleCardPanel(card);

        JPanel headerRow = new JPanel(new MigLayout("fillx, insets 0, gapx 8", "[grow, fill][][]", ROW_CENTER));
        headerRow.setOpaque(false);
        profileHeaderLabel = UiComponents.createSectionHeader(SECTION_PROFILE_TITLE, Codicons.HUBOT, HEADER_FONT_STYLE);
        headerRow.add(profileHeaderLabel, WMIN_0);

        JLabel overrideHint = new JLabel("Can be overridden per-project in Project Settings");
        overrideHint.putClientProperty(FlatClientProperties.STYLE, "font: -1; foreground: $Label.disabledForeground");
        headerRow.add(overrideHint);
        headerRow.add(profileInfoIcon);
        card.add(headerRow, HEADER_CONSTRAINTS);

        JPanel grid = new JPanel(new MigLayout("fillx, insets 2 0 0 0, gapx 12",
                "[grow, fill, sg profile]12[grow, fill, sg profile]12[grow, fill, sg profile]", "[grow, fill]"));
        grid.setOpaque(false);
        profileCards.clear();
        for (AgentProfile profile : AgentProfile.values()) {
            ProfileCard profileCard = new ProfileCard(profile);
            profileCards.put(profile, profileCard);
            grid.add(profileCard, GROW_WMIN_0);
        }
        card.add(grid, GROWX_WMIN_0);
        return card;
    }

    private JPanel buildAgentExecutionCard() {
        JPanel card = new JPanel(new MigLayout(CARD_LAYOUT_CONSTRAINTS, GROW_FILL, ""));
        UiComponents.styleCardPanel(card);

        agentExecutionHeaderLabel = UiComponents.createSectionHeader(SECTION_AGENT_EXECUTION_TITLE, Codicons.PULSE,
                HEADER_FONT_STYLE);
        card.add(agentExecutionHeaderLabel, HEADER_CONSTRAINTS);
        card.add(createRowSeparator(), SEPARATOR_CONSTRAINTS);

        card.add(
                UiComponents.createFieldRow(
                        UiComponents.createFieldTextBlock("Max Tool Turns per Prompt",
                                "Autonomous JEXL turns before pause (1–100).", null),
                        maxAgentToolTurnsSpinner, "w 90:110:130"),
                GROWX_WMIN_0);

        card.add(createRowSeparator(), SEPARATOR_CONSTRAINTS);

        card.add(
                UiComponents.createFieldRow(UiComponents.createFieldTextBlock("Chat Memory Window",
                        "Retained messages per session (1–500).", null), maxMessagesSpinner, "w 90:110:130"),
                GROWX_WMIN_0);

        card.add(createRowSeparator(), SEPARATOR_CONSTRAINTS);
        card.add(googleGroundingCheckBox, GROWX_WMIN_0);

        return card;
    }

    private JPanel buildAppearanceCard() {
        JPanel card = new JPanel(new MigLayout(CARD_LAYOUT_CONSTRAINTS, GROW_FILL, ""));
        UiComponents.styleCardPanel(card);

        appearanceHeaderLabel = UiComponents.createSectionHeader(SECTION_APPEARANCE_TITLE, Codicons.SYMBOL_COLOR,
                HEADER_FONT_STYLE);
        card.add(appearanceHeaderLabel, HEADER_CONSTRAINTS);
        card.add(createRowSeparator(), SEPARATOR_CONSTRAINTS);

        card.add(UiComponents.createFieldRow(
                UiComponents.createFieldTextBlock("Interface Theme", "Applies immediately across all panels.", null),
                themeCombo, "w 160:190:220"), GROWX_WMIN_0);

        card.add(createRowSeparator(), SEPARATOR_CONSTRAINTS);

        JPanel logControlPanel = new JPanel(new MigLayout("insets 0, gapx 8", "[][right]", ROW_CENTER));
        logControlPanel.setOpaque(false);
        logControlPanel.add(toolLogPathLabel);
        logControlPanel.add(openToolLogButton);
        card.add(UiComponents.createFieldRow(
                UiComponents.createFieldTextBlock("JEXL Tool Call Log",
                        "Always-on structured JSONL log of every JEXL script and result.", null),
                logControlPanel, "right"), GROWX_WMIN_0);

        return card;
    }

    private static JSeparator createRowSeparator() {
        JSeparator separator = new JSeparator(SwingConstants.HORIZONTAL);
        separator.setOpaque(false);
        return separator;
    }

    private void loadProfiles() {
        UiComponents.selectAgentProfileById(profileCombo, settingsService.getSettings().getAgentProfile());
    }

    void updateSelectedProfileDetails() {
        UiComponents.updateProfileSelectionDetails(profileCombo, modelApiBadgeLabel, modelDescriptionArea);
        AgentProfile selectedProfile = (AgentProfile) profileCombo.getSelectedItem();
        for (Map.Entry<AgentProfile, ProfileCard> entry : profileCards.entrySet()) {
            entry.getValue().setSelected(entry.getKey() == selectedProfile);
        }
    }

    void selectProfileFromCard(AgentProfile profile) {
        if (profile != null && profileCombo.getSelectedItem() != profile) {
            profileCombo.setSelectedItem(profile);
        }
    }

    void applyThemeStyling() {
        Color accentColor = SwingHtmlUtils.getAccentColor();
        Color mutedColor = SwingHtmlUtils.getMutedForegroundColor();
        Color greenColor = SwingHtmlUtils.getUiColor(new Color(0x28, 0xa7, 0x45), "Actions.Green");

        statusLabel.setForeground(greenColor);
        if (authHeaderLabel != null) {
            authHeaderLabel.setIcon(FontIcon.of(Codicons.KEY, 18, accentColor));
        }
        if (profileHeaderLabel != null) {
            profileHeaderLabel.setIcon(FontIcon.of(Codicons.HUBOT, 18, accentColor));
        }
        if (appearanceHeaderLabel != null) {
            appearanceHeaderLabel.setIcon(FontIcon.of(Codicons.SYMBOL_COLOR, 18, accentColor));
        }
        if (agentExecutionHeaderLabel != null) {
            agentExecutionHeaderLabel.setIcon(FontIcon.of(Codicons.PULSE, 18, accentColor));
        }
        UiComponents.styleCodePillBadge(modelApiBadgeLabel);
        UiComponents.updateInfoIconTheme(profileInfoIcon);

        String accentHex = SwingHtmlUtils.toHex(accentColor);
        String borderHex = UiComponents.getCardBorderHex();
        String pillBg = UiComponents.getPillBackgroundDirective();
        UiComponents.refreshActionButtonIcons(this);

        if (segmentedProviderPanel != null) {
            segmentedProviderPanel.setOpaque(true);
            segmentedProviderPanel.putClientProperty(FlatClientProperties.STYLE,
                    "arc: 8; border: 1,1,1,1," + borderHex + ",1,8; background: " + pillBg);
        }
        applyProviderToggleStyling();

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

        toolLogPathLabel.setForeground(mutedColor);
        openToolLogButton.setIcon(FontIcon.of(Codicons.FOLDER_OPENED, 13, accentColor));
        openToolLogButton.putClientProperty(FlatClientProperties.STYLE,
                "font: -1; arc: 8; margin: 3,10,3,10; borderColor: " + borderHex + "; background: " + pillBg);

        for (ProfileCard card : profileCards.values()) {
            card.applyCardTheme();
        }
        updateSaveButtonState();
    }

    private void applyProviderToggleStyling() {
        if (apiKeyProviderToggleBtn == null || vertexAiProviderToggleBtn == null) {
            return;
        }
        styleSingleProviderToggle(apiKeyProviderToggleBtn, apiKeyProviderToggleBtn.isSelected());
        styleSingleProviderToggle(vertexAiProviderToggleBtn, vertexAiProviderToggleBtn.isSelected());
    }

    private static void styleSingleProviderToggle(JToggleButton button, boolean selected) {
        String accentHex = SwingHtmlUtils.toHex(SwingHtmlUtils.getAccentColor());
        if (selected) {
            button.putClientProperty(FlatClientProperties.STYLE,
                    "font: bold -1; arc: 6; margin: 4,10,4,10; borderWidth: 0; background: " + accentHex
                            + "; foreground: #ffffff; selectedBackground: " + accentHex
                            + "; selectedForeground: #ffffff");
        } else {
            button.putClientProperty(FlatClientProperties.STYLE,
                    "font: -1; arc: 6; margin: 4,10,4,10; borderWidth: 0; background: null; foreground: $Label.disabledForeground");
        }
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
        if (apiKeyProviderToggleBtn != null && vertexAiProviderToggleBtn != null) {
            apiKeyProviderToggleBtn.setSelected(!vertexSelected);
            vertexAiProviderToggleBtn.setSelected(vertexSelected);
            applyProviderToggleStyling();
        }
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

    Path resolveToolCallLogFile() {
        Path roxyHome = null;
        if (settingsService != null) {
            roxyHome = settingsService.getRoxyHomeDir();
        }
        if (roxyHome == null) {
            roxyHome = SettingsService.getDefaultRoxyHomeDir();
        }
        return roxyHome.resolve("logs").resolve("toolcalls.jsonl");
    }

    void openToolCallLog() {
        Path logFile = resolveToolCallLogFile();
        logFileOpener.accept(logFile);
    }

    private static void openLogFileOnDesktop(Path logFile) {
        if (logFile == null) {
            return;
        }
        Path target = logFile;
        if (!Files.exists(target)) {
            target = logFile.getParent();
        }
        if (target != null && Files.exists(target) && Desktop.isDesktopSupported()) {
            try {
                Desktop.getDesktop().open(target.toFile());
                return;
            } catch (IOException ex) {
                log.debug("Failed to open log path via Desktop: {}", ex.getMessage());
            }
        }
        UiComponents.openUrlOrCopyToClipboard(logFile.toString());
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

    private SettingsSnapshot captureCurrentSnapshot() {
        String theme = Objects.toString(themeCombo.getSelectedItem(), AppSettings.DEFAULT_THEME);
        boolean vertexEnabled = isVertexAiSelected();
        String apiKey = new String(geminiApiKeyField.getPassword());
        String projectId = vertexProjectIdField.getText().trim();
        String location = vertexLocationField.getText().trim();
        String credentialsPath = vertexCredentialsPathField.getText().trim();
        AgentProfile selectedProfile = (AgentProfile) profileCombo.getSelectedItem();
        String profileId;
        if (selectedProfile != null) {
            profileId = selectedProfile.getId();
        } else {
            profileId = AgentProfile.DEFAULT.getId();
        }
        int toolTurns = (Integer) maxAgentToolTurnsSpinner.getValue();
        int maxMessages = (Integer) maxMessagesSpinner.getValue();
        boolean groundingEnabled = googleGroundingCheckBox.isSelected();
        return new SettingsSnapshot(theme, vertexEnabled, apiKey, projectId, location, credentialsPath, profileId,
                toolTurns, maxMessages, groundingEnabled);
    }

    void captureSavedBaseline() {
        savedBaseline = captureCurrentSnapshot();
    }

    boolean hasUnsavedChanges() {
        if (savedBaseline == null) {
            return false;
        }
        return !savedBaseline.equals(captureCurrentSnapshot());
    }

    void updateSaveButtonState() {
        if (saveButton == null) {
            return;
        }
        if (hasUnsavedChanges()) {
            saveButton.setText(SAVE_CHANGES_DIRTY_TEXT);
            saveButton.putClientProperty(FlatClientProperties.STYLE,
                    SAVE_BUTTON_BASE_STYLE + UiComponents.ACCENT_BUTTON_STYLE);
        } else {
            saveButton.setText(SAVE_SETTINGS_TEXT);
            saveButton.putClientProperty(FlatClientProperties.STYLE,
                    SAVE_BUTTON_BASE_STYLE + UiComponents.SUBTLE_GREEN_BUTTON_STYLE);
        }
        UiComponents.refreshActionButtonIcons(saveButton);
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
            AgentProfile targetProfile = AgentProfile.fromString(event.settings().getAgentProfile());
            if (targetProfile != profileCombo.getSelectedItem()) {
                profileCombo.setSelectedItem(targetProfile);
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
            boolean groundingEnabled = event.settings().isGoogleGroundingEnabled();
            if (groundingEnabled != googleGroundingCheckBox.isSelected()) {
                googleGroundingCheckBox.setSelected(groundingEnabled);
            }
            updateOnboardingBannerState();
            captureSavedBaseline();
            updateSaveButtonState();
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

        AgentProfile selectedProfile = (AgentProfile) profileCombo.getSelectedItem();
        String modelName;
        if (selectedProfile != null) {
            modelName = selectedProfile.getPlanModel();
        } else {
            modelName = SettingsService.resolveGlobalGeminiModel(settingsService);
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

        long startNanos = System.nanoTime();
        CompletableFuture<String> future = Edt.supplyAsync(() -> {
            if (aiService == null) {
                throw new IllegalStateException("AI service is unavailable");
            }
            return aiService.testConnection(connectionConfig, modelName);
        }, response -> {
            long elapsedMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
            restoreTestConnectionButtonState();
            showTestConnectionStatus(CONNECTION_SUCCESS_TEXT + " (" + modelName + " • " + elapsedMs + " ms)", true);
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
        AgentProfile selectedProfile = (AgentProfile) profileCombo.getSelectedItem();
        if (selectedProfile != null) {
            settings.setAgentProfile(selectedProfile.getId());
        }
        settings.setMaxAgentToolTurns((Integer) maxAgentToolTurnsSpinner.getValue());
        settings.setMaxChatMemoryMessages(newMaxMessages);
        settings.setGoogleGroundingEnabled(googleGroundingCheckBox.isSelected());
        settingsService.saveSettings();
        updateOnboardingBannerState();
        captureSavedBaseline();
        updateSaveButtonState();

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

    void setLogFileOpener(Consumer<Path> logFileOpener) {
        this.logFileOpener = Objects.requireNonNull(logFileOpener, "logFileOpener must not be null");
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

    final class ProfileCard extends JPanel {
        private static final long serialVersionUID = 1L;

        final transient AgentProfile profile;
        final JLabel radioIconLabel;
        final JLabel cardTitleLabel;
        final JLabel selectedBadge;
        final JLabel planModelPill;
        final JLabel codeModelPill;
        final JTextArea descriptionArea;
        private boolean selected;

        ProfileCard(AgentProfile profile) {
            this.profile = profile;
            setLayout(new MigLayout("fillx, wrap 1, insets 12 14 12 14, gapy 6, hidemode 3", GROW_FILL, ""));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setOpaque(true);

            JPanel headerRow = new JPanel(
                    new MigLayout("fillx, insets 0, gapx 6", "[][grow, fill][right]", ROW_CENTER));
            headerRow.setOpaque(false);

            radioIconLabel = new JLabel();
            headerRow.add(radioIconLabel);

            cardTitleLabel = new JLabel(profile.getDisplayName());
            cardTitleLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold +1");
            headerRow.add(cardTitleLabel, WMIN_0);

            selectedBadge = new JLabel("SELECTED");
            selectedBadge.setVisible(false);
            headerRow.add(selectedBadge, "right, hidemode 3");
            add(headerRow, GROWX_WMIN_0);

            planModelPill = new JLabel("EXPLORE / PLAN: " + profile.getPlanModel());
            codeModelPill = new JLabel("CODE / REVIEW: " + profile.getCodeModel());
            add(planModelPill, GROWX_WMIN_0);
            add(codeModelPill, GROWX_WMIN_0);

            descriptionArea = UiComponents.createReadOnlyTextArea();
            descriptionArea.setText(profile.getDescription());
            descriptionArea.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            add(descriptionArea, GROWX_WMIN_0);

            MouseAdapter clickListener = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    selectProfileFromCard(profile);
                }
            };
            addMouseListener(clickListener);
            descriptionArea.addMouseListener(clickListener);
            headerRow.addMouseListener(clickListener);

            applyCardTheme();
        }

        void setSelected(boolean selected) {
            this.selected = selected;
            selectedBadge.setVisible(selected);
            applyCardTheme();
        }

        boolean isSelected() {
            return selected;
        }

        void applyCardTheme() {
            if (cardTitleLabel == null) {
                return;
            }
            Color accentColor = SwingHtmlUtils.getAccentColor();
            Color mutedColor = SwingHtmlUtils.getMutedForegroundColor();
            Color fgColor = SwingHtmlUtils.getUiColor(new Color(0x21, 0x25, 0x29), "Label.foreground");
            Color greenColor = SwingHtmlUtils.getUiColor(new Color(0x28, 0xa7, 0x45), "Actions.Green");
            String accentHex = SwingHtmlUtils.toHex(accentColor);
            String borderHex = UiComponents.getCardBorderHex();
            String pillBg = UiComponents.getPillBackgroundDirective();

            if (selected) {
                radioIconLabel.setIcon(FontIcon.of(Codicons.CIRCLE_FILLED, 15, accentColor));
                putClientProperty(FlatClientProperties.STYLE,
                        "arc: 10; border: 2,2,2,2," + accentHex + ",1,10; background: fade(" + accentHex + ", 8%)");
                cardTitleLabel.setForeground(fgColor);
                descriptionArea.setForeground(fgColor);
            } else {
                radioIconLabel.setIcon(FontIcon.of(Codicons.CIRCLE_OUTLINE, 15, mutedColor));
                putClientProperty(FlatClientProperties.STYLE,
                        "arc: 10; border: 1,1,1,1," + borderHex + ",1,10; background: " + pillBg);
                cardTitleLabel.setForeground(fgColor);
                descriptionArea.setForeground(mutedColor);
            }

            selectedBadge.putClientProperty(FlatClientProperties.STYLE,
                    "font: bold -2; arc: 6; border: 2,6,2,6; background: " + accentHex + "; foreground: #ffffff");

            planModelPill.setForeground(accentColor);
            planModelPill.putClientProperty(FlatClientProperties.STYLE,
                    "font: $monospaced.font -1; arc: 6; border: 3,8,3,8," + borderHex + ",1,6; background: " + pillBg);

            codeModelPill.setForeground(greenColor);
            codeModelPill.putClientProperty(FlatClientProperties.STYLE,
                    "font: $monospaced.font -1; arc: 6; border: 3,8,3,8," + borderHex + ",1,6; background: " + pillBg);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            applyCardTheme();
        }
    }
}
