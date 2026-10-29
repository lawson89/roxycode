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
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Theme;
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
    private static final String MIG_COL_GROW_FILL = "[0:0, grow, fill]";
    private static final String MIG_GROWX_WMIN_ZERO = "growx, wmin 0";
    private static final String MIG_TOP_SPLIT = "[0:0, grow, fill]16[right]";
    private static final String MIG_CENTER = "[center]";
    private static final String LABEL_FOREGROUND_KEY = "Label.foreground";
    private static final String LABEL_DISABLED_FG_KEY = "Label.disabledForeground";
    private static final String COMPONENT_INFO_FG_KEY = "Component.infoForeground";
    private static final String COMPONENT_BORDER_COLOR_KEY = "Component.borderColor";

    static final String SUBTITLE_TEXT = "Configure appearance, Gemini model credentials, agent execution limits, and diagnostics.";
    static final String RESTART_BADGE_TEXT = "Restart Required";
    static final String RESTART_LEGEND_TEXT = "(*) Restart required for Gemini API Key and Max Chat History Messages";
    static final String SAVED_RESTART_REQUIRED_TEXT = "Settings saved. Please restart RoxyCode for API Key / Chat History changes to take effect.";
    static final String SAVED_APPLIED_TEXT = "Settings saved and applied.";
    static final String SECTION_APPEARANCE_TITLE = "Appearance";
    static final String SECTION_AI_MODEL_TITLE = "AI Model & Credentials";
    static final String SECTION_AGENT_EXECUTION_TITLE = "Agent Execution & Diagnostics";

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

    private final List<JPanel> sectionCards = new ArrayList<>();
    private final List<SectionHeader> sectionHeaders = new ArrayList<>();
    private final List<JLabel> fieldTitleLabels = new ArrayList<>();
    private final List<JLabel> fieldDescriptionLabels = new ArrayList<>();
    private final List<JLabel> restartBadges = new ArrayList<>();

    private boolean updatingFromSettings = false;

    private record SectionHeader(JLabel label, Codicons icon) {
    }

    public SettingsPanel(SettingsService settingsService) {
        this.settingsService = settingsService;
        setLayout(new MigLayout("fill, insets 20, hidemode 3", MIG_COL_GROW_FILL, "[]12[grow, fill]"));
        setOpaque(true);

        titleLabel = new JLabel("Settings");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 18f));
        subtitleLabel = new JLabel(SUBTITLE_TEXT);

        JPanel titleBlock = new JPanel(new MigLayout("insets 0, wrap 1, gapy 2", MIG_COL_GROW_FILL, "[]"));
        titleBlock.setOpaque(false);
        titleBlock.add(titleLabel, MIG_GROWX_WMIN_ZERO);
        titleBlock.add(subtitleLabel, MIG_GROWX_WMIN_ZERO);

        statusLabel = new JLabel("");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 11.5f));

        saveButton = new JButton("Save Settings");
        saveButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        saveButton.setIconTextGap(6);
        saveButton.addActionListener(e -> saveSettings());

        JPanel headerActions = new JPanel(new MigLayout("insets 0, gapx 12", "[][]", MIG_CENTER));
        headerActions.setOpaque(false);
        headerActions.add(statusLabel);
        headerActions.add(saveButton);

        JPanel header = new JPanel(new MigLayout("fillx, insets 0", "[0:0, grow, fill][]", MIG_CENTER));
        header.setOpaque(false);
        header.add(titleBlock, MIG_GROWX_WMIN_ZERO);
        header.add(headerActions);
        add(header, "growx, wmin 0, wrap");

        themeCombo = new JComboBox<>(
                new String[]{"FlatLaf Light", "FlatLaf Dark", "IntelliJ", "Darcula", "macOS Light", "macOS Dark"});
        themeCombo.setSelectedItem(settingsService.getSettings().getTheme());
        themeCombo.addActionListener(e -> updateTheme());

        modelCombo = new JComboBox<>();
        modelApiBadgeLabel = new JLabel();
        modelApiBadgeLabel.setIconTextGap(5);
        modelDescriptionArea = createReadOnlyDescriptionArea();
        loadModels();
        modelCombo.addActionListener(e -> updateSelectedModelDetails());
        updateSelectedModelDetails();

        geminiApiKeyField = new JPasswordField(24);
        geminiApiKeyField.setText(settingsService.getSettings().getGeminiApiKey());
        geminiApiKeyField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Enter your Gemini API key...");
        SwingHtmlUtils.installTextContextMenu(geminiApiKeyField);

        maxAgentToolTurnsSpinner = new JSpinner(
                new SpinnerNumberModel(settingsService.getSettings().getMaxAgentToolTurns(), 1, 50, 1));

        maxMessagesSpinner = new JSpinner(
                new SpinnerNumberModel(settingsService.getSettings().getMaxChatMemoryMessages(), 1, 500, 5));

        logToolCallsCheckBox = new JCheckBox("Write tool calls and results to ~/.roxycode/logs/toolcalls.log");
        logToolCallsCheckBox.setOpaque(false);
        logToolCallsCheckBox.setSelected(settingsService.getSettings().isLogToolCalls());

        JPanel cardsContainer = new ScrollableCardsPanel();
        cardsContainer.setLayout(new MigLayout("fillx, wrap 1, insets 4 2 16 6, gapy 14", MIG_COL_GROW_FILL, ""));
        cardsContainer.setOpaque(true);

        cardsContainer.add(buildAppearanceCard(), MIG_GROWX_WMIN_ZERO);
        cardsContainer.add(buildAiModelCard(), MIG_GROWX_WMIN_ZERO);
        cardsContainer.add(buildAgentExecutionCard(), MIG_GROWX_WMIN_ZERO);

        restartCalloutPanel = new JPanel(
                new MigLayout("fillx, insets 10 14 10 14, gapx 10", "[][0:0, grow, fill]", MIG_CENTER));
        restartCalloutPanel.setOpaque(true);
        legendIconLabel = new JLabel();
        legendLabel = new JLabel(RESTART_LEGEND_TEXT);
        legendLabel.setFont(legendLabel.getFont().deriveFont(Font.ITALIC, 11.5f));
        restartCalloutPanel.add(legendIconLabel);
        restartCalloutPanel.add(legendLabel, MIG_GROWX_WMIN_ZERO);
        cardsContainer.add(restartCalloutPanel, MIG_GROWX_WMIN_ZERO);

        JScrollPane scrollPane = new JScrollPane(cardsContainer);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setOpaque(true);
        scrollPane.getViewport().setOpaque(true);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, "grow, wmin 0");

        statusTimer = new Timer(3000, e -> statusLabel.setText(""));
        statusTimer.setRepeats(false);

        applyThemeStyling();
    }

    private JPanel buildAppearanceCard() {
        JPanel card = createSectionCard(SECTION_APPEARANCE_TITLE, Codicons.SYMBOL_COLOR);

        JPanel themeRow = new JPanel(new MigLayout("fillx, insets 0", MIG_TOP_SPLIT, MIG_CENTER));
        themeRow.setOpaque(false);
        themeRow.add(
                createFieldTextBlock("Theme",
                        "Choose between light and dark color schemes. Changes preview immediately."),
                MIG_GROWX_WMIN_ZERO);
        themeRow.add(themeCombo, "w 220:250:280");

        card.add(themeRow, MIG_GROWX_WMIN_ZERO);
        return card;
    }

    private JPanel buildAiModelCard() {
        JPanel card = createSectionCard(SECTION_AI_MODEL_TITLE, Codicons.HUBOT);

        JPanel modelHeaderRow = new JPanel(new MigLayout("fillx, insets 0", MIG_TOP_SPLIT, MIG_CENTER));
        modelHeaderRow.setOpaque(false);
        modelHeaderRow.add(createFieldTextBlock("Gemini Model",
                "Default Gemini model used for Codebase Q&A and Feature Mode turns."), MIG_GROWX_WMIN_ZERO);

        JPanel modelControlBox = new JPanel(new MigLayout("insets 0, gapx 8", "[][]", MIG_CENTER));
        modelControlBox.setOpaque(false);
        modelControlBox.add(modelApiBadgeLabel, "hidemode 3");
        modelControlBox.add(modelCombo, "w 220:250:280");
        modelHeaderRow.add(modelControlBox);

        card.add(modelHeaderRow, MIG_GROWX_WMIN_ZERO);
        card.add(modelDescriptionArea, "growx, wmin 0, gaptop 2, hidemode 3");
        card.add(createRowSeparator(), "growx, gaptop 4, gapbottom 4");

        JPanel apiKeyRow = new JPanel(new MigLayout("fillx, insets 0, wrap 1, gapy 8", MIG_COL_GROW_FILL, ""));
        apiKeyRow.setOpaque(false);
        apiKeyRow.add(
                createFieldTextBlockWithBadge("Gemini API Key",
                        "Google GenAI API key used to authenticate model requests.", createRestartBadge()),
                MIG_GROWX_WMIN_ZERO);
        apiKeyRow.add(geminiApiKeyField, "w 280:420:520");

        card.add(apiKeyRow, MIG_GROWX_WMIN_ZERO);
        return card;
    }

    private JPanel buildAgentExecutionCard() {
        JPanel card = createSectionCard(SECTION_AGENT_EXECUTION_TITLE, Codicons.PULSE);

        JPanel turnsRow = new JPanel(new MigLayout("fillx, insets 0", MIG_TOP_SPLIT, MIG_CENTER));
        turnsRow.setOpaque(false);
        turnsRow.add(
                createFieldTextBlock("Max Agent Tool Turns",
                        "Maximum consecutive JEXL tool execution turns allowed per prompt (1–50)."),
                MIG_GROWX_WMIN_ZERO);
        turnsRow.add(maxAgentToolTurnsSpinner, "w 100:120:140");
        card.add(turnsRow, MIG_GROWX_WMIN_ZERO);

        card.add(createRowSeparator(), "growx, gaptop 4, gapbottom 4");

        JPanel memoryRow = new JPanel(new MigLayout("fillx, insets 0", MIG_TOP_SPLIT, MIG_CENTER));
        memoryRow.setOpaque(false);
        memoryRow.add(createFieldTextBlockWithBadge("Max Chat History Messages",
                "Maximum number of conversation messages retained in active chat memory (1–500).",
                createRestartBadge()), MIG_GROWX_WMIN_ZERO);
        memoryRow.add(maxMessagesSpinner, "w 100:120:140");
        card.add(memoryRow, MIG_GROWX_WMIN_ZERO);

        card.add(createRowSeparator(), "growx, gaptop 4, gapbottom 4");

        JPanel logRow = new JPanel(new MigLayout("fillx, insets 0, wrap 1, gapy 6", MIG_COL_GROW_FILL, ""));
        logRow.setOpaque(false);
        logRow.add(createFieldTextBlock("Log Tool Calls & Results",
                "Record every JEXL script execution and its output for troubleshooting."), MIG_GROWX_WMIN_ZERO);
        logRow.add(logToolCallsCheckBox, MIG_GROWX_WMIN_ZERO);
        card.add(logRow, MIG_GROWX_WMIN_ZERO);

        return card;
    }

    private JPanel createSectionCard(String sectionTitle, Codicons icon) {
        JPanel card = new JPanel(new MigLayout("fillx, wrap 1, insets 16 18 16 18, gapy 10", MIG_COL_GROW_FILL, ""));
        card.setOpaque(true);

        JLabel headerLabel = new JLabel(sectionTitle);
        headerLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold +2");
        headerLabel.setIconTextGap(8);
        sectionHeaders.add(new SectionHeader(headerLabel, icon));

        card.add(headerLabel, "growx, wmin 0, gapbottom 2");
        sectionCards.add(card);
        return card;
    }

    private JPanel createFieldTextBlock(String title, String description) {
        return createFieldTextBlockWithBadge(title, description, null);
    }

    private JPanel createFieldTextBlockWithBadge(String title, String description, JLabel badge) {
        JPanel block = new JPanel(new MigLayout("insets 0, wrap 1, gapy 2", MIG_COL_GROW_FILL, ""));
        block.setOpaque(false);

        JLabel titleLbl = new JLabel(title);
        titleLbl.putClientProperty(FlatClientProperties.STYLE, "font: bold");
        fieldTitleLabels.add(titleLbl);

        if (badge != null) {
            JPanel titleLine = new JPanel(new MigLayout("insets 0, gapx 8", "[][]", MIG_CENTER));
            titleLine.setOpaque(false);
            titleLine.add(titleLbl);
            titleLine.add(badge);
            block.add(titleLine, MIG_GROWX_WMIN_ZERO);
        } else {
            block.add(titleLbl, MIG_GROWX_WMIN_ZERO);
        }

        JLabel descLbl = new JLabel(description);
        descLbl.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        fieldDescriptionLabels.add(descLbl);
        block.add(descLbl, MIG_GROWX_WMIN_ZERO);

        return block;
    }

    private JLabel createRestartBadge() {
        JLabel badge = new JLabel(RESTART_BADGE_TEXT);
        badge.setIconTextGap(4);
        restartBadges.add(badge);
        return badge;
    }

    private static JSeparator createRowSeparator() {
        JSeparator separator = new JSeparator(SwingConstants.HORIZONTAL);
        separator.setOpaque(false);
        return separator;
    }

    private static JTextArea createReadOnlyDescriptionArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setFocusable(false);
        area.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        return area;
    }

    private void loadModels() {
        List<GeminiModelConfig> modelList = settingsService.getAvailableModels();
        if (modelList == null)
            modelList = List.of();
        modelCombo.setModel(new DefaultComboBoxModel<>(modelList.toArray(new GeminiModelConfig[0])));

        String currentModelId = settingsService.getSettings().getGeminiModel();
        for (int i = 0; i < modelCombo.getItemCount(); i++) {
            if (modelCombo.getItemAt(i).apiName().equals(currentModelId)) {
                modelCombo.setSelectedIndex(i);
                break;
            }
        }
    }

    void updateSelectedModelDetails() {
        GeminiModelConfig selected = (GeminiModelConfig) modelCombo.getSelectedItem();
        if (selected == null) {
            modelApiBadgeLabel.setText("");
            modelApiBadgeLabel.setVisible(false);
            modelDescriptionArea.setText("");
            modelDescriptionArea.setVisible(false);
            return;
        }
        String apiName = StringUtils.defaultString(selected.apiName());
        modelApiBadgeLabel.setText(apiName);
        modelApiBadgeLabel.setVisible(!apiName.isBlank());

        String description = StringUtils.defaultString(selected.description());
        modelDescriptionArea.setText(description);
        modelDescriptionArea.setVisible(!description.isBlank());
    }

    void applyThemeStyling() {
        boolean dark = FlatLaf.isLafDark();
        Color accentColor = SwingHtmlUtils.getAccentColor();
        Color borderColor = SwingHtmlUtils.getUiColor(dark ? new Color(0x3c, 0x3f, 0x41) : new Color(0xd8, 0xdc, 0xe0),
                COMPONENT_BORDER_COLOR_KEY);
        Color mutedColor = SwingHtmlUtils.getUiColor(new Color(0x6c, 0x75, 0x7d), LABEL_DISABLED_FG_KEY,
                COMPONENT_INFO_FG_KEY);
        Color fgColor = SwingHtmlUtils.getUiColor(new Color(0x21, 0x25, 0x29), LABEL_FOREGROUND_KEY);
        Color greenColor = SwingHtmlUtils.getUiColor(new Color(0x28, 0xa7, 0x45), "Actions.Green");
        Color amberColor = SwingHtmlUtils.getUiColor(new Color(0xd9, 0x77, 0x06), "Component.warning.borderColor",
                "Actions.Yellow");

        titleLabel.setForeground(fgColor);
        subtitleLabel.setForeground(mutedColor);
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
        String cardBgDirective = dark ? "lighten($Panel.background, 3%)" : "darken($Panel.background, 2%)";
        for (JPanel card : sectionCards) {
            card.putClientProperty(FlatClientProperties.STYLE,
                    "arc: 12; border: 1,1,1,1," + borderHex + ",1,12; background: " + cardBgDirective);
        }

        for (SectionHeader header : sectionHeaders) {
            header.label().setForeground(fgColor);
            header.label().setIcon(FontIcon.of(header.icon(), 18, accentColor));
        }

        for (JLabel titleLbl : fieldTitleLabels) {
            titleLbl.setForeground(fgColor);
        }

        for (JLabel descLbl : fieldDescriptionLabels) {
            descLbl.setForeground(mutedColor);
        }

        Font baseFont = UIManager.getFont("Label.font");
        if (baseFont != null) {
            modelDescriptionArea.setFont(baseFont.deriveFont(Math.max(11f, baseFont.getSize2D() - 1f)));
        }
        modelDescriptionArea.setForeground(mutedColor);

        String pillBg = dark ? "lighten($Panel.background, 7%)" : "darken($Panel.background, 5%)";
        modelApiBadgeLabel.putClientProperty(FlatClientProperties.STYLE,
                "font: $monospaced.font -1; arc: 8; border: 2,8,2,8," + borderHex + ",1,8; background: " + pillBg);
        modelApiBadgeLabel.setForeground(fgColor);
        modelApiBadgeLabel.setIcon(FontIcon.of(Codicons.CODE, 13, accentColor));

        String amberHex = SwingHtmlUtils.toHex(amberColor);
        for (JLabel badge : restartBadges) {
            badge.setForeground(amberColor);
            badge.setIcon(FontIcon.of(Codicons.REFRESH, 12, amberColor));
            badge.putClientProperty(FlatClientProperties.STYLE, "font: bold -2; arc: 999; border: 2,8,2,8," + amberHex
                    + ",1,999; background: fade(" + amberHex + ", 14%)");
        }

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
    @SuppressWarnings("PMD.UnusedAssignment")
    public void onSettingsChanged(SettingsChangedEvent event) {
        if (event == null || event.settings() == null)
            return;
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
                    for (int i = 0; i < modelCombo.getItemCount(); i++) {
                        if (modelId.equals(modelCombo.getItemAt(i).apiName())) {
                            modelCombo.setSelectedIndex(i);
                            break;
                        }
                    }
                }
            }
        });
    }

    private void updateTheme() {
        if (updatingFromSettings)
            return;
        String selectedTheme = (String) themeCombo.getSelectedItem();
        if (selectedTheme != null) {
            settingsService.getSettings().setTheme(selectedTheme);
            applyTheme(selectedTheme);
        }
    }

    private void saveSettings() {
        AppSettings settings = settingsService.getSettings();
        String oldApiKey = settings.getGeminiApiKey() != null ? settings.getGeminiApiKey() : "";
        String newApiKey = new String(geminiApiKeyField.getPassword());
        int oldMaxMessages = settings.getMaxChatMemoryMessages();
        int newMaxMessages = (Integer) maxMessagesSpinner.getValue();
        boolean restartRequiredChanged = !oldApiKey.equals(newApiKey) || oldMaxMessages != newMaxMessages;

        settings.setGeminiApiKey(newApiKey);
        GeminiModelConfig selectedModel = (GeminiModelConfig) modelCombo.getSelectedItem();
        if (selectedModel != null)
            settings.setGeminiModel(selectedModel.apiName());
        settings.setMaxAgentToolTurns((Integer) maxAgentToolTurnsSpinner.getValue());
        settings.setMaxChatMemoryMessages(newMaxMessages);
        settings.setLogToolCalls(logToolCallsCheckBox.isSelected());
        settingsService.saveSettings();

        statusLabel.setText(restartRequiredChanged ? SAVED_RESTART_REQUIRED_TEXT : SAVED_APPLIED_TEXT);
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

    JComboBox<String> getThemeCombo() {
        return themeCombo;
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

    JPasswordField getGeminiApiKeyField() {
        return geminiApiKeyField;
    }

    JCheckBox getLogToolCallsCheckBox() {
        return logToolCallsCheckBox;
    }

    JButton getSaveButton() {
        return saveButton;
    }

    private static final class ScrollableCardsPanel extends JPanel implements Scrollable {
        private static final long serialVersionUID = 1L;

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(visibleRect.height - 32, 32);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    public static boolean isDarkTheme(String themeName) {
        if (themeName != null && !themeName.isBlank()) {
            String lower = themeName.toLowerCase(Locale.ROOT);
            if (lower.contains("dark") || "darcula".equals(lower))
                return true;
            if (lower.contains("light") || "intellij".equals(lower))
                return false;
        }
        return FlatLaf.isLafDark();
    }

    public static void applyRSyntaxTheme(RSyntaxTextArea textArea, SettingsService settingsService) {
        applyRSyntaxTheme(textArea,
                settingsService != null && settingsService.getSettings() != null
                        ? settingsService.getSettings().getTheme()
                        : null);
    }

    private static final Cache<Boolean, Theme> RSYNTAX_THEME_CACHE = Caffeine.newBuilder().maximumSize(2).build();

    static Theme getRSyntaxTheme(boolean dark) throws IOException {
        try {
            return RSYNTAX_THEME_CACHE.get(dark, isDark -> {
                String themePath = isDark
                        ? "/org/fife/ui/rsyntaxtextarea/themes/dark.xml"
                        : "/org/fife/ui/rsyntaxtextarea/themes/default.xml";
                try (InputStream in = SettingsPanel.class.getResourceAsStream(themePath)) {
                    return Theme.load(in);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    public static void applyRSyntaxTheme(RSyntaxTextArea textArea, String themeName) {
        if (textArea == null)
            return;
        try {
            getRSyntaxTheme(isDarkTheme(themeName)).apply(textArea);
        } catch (IOException ioe) {
            log.error("Failed to load RSyntaxTextArea theme: {}", ioe.getMessage());
        }
    }

    public static void applyTheme(String themeName) {
        Edt.runAndWait(() -> {
            try {
                UIManager.put("TabbedPane.tabType", "card");
                UIManager.put("TabbedPane.showTabSeparators", true);
                UIManager.setLookAndFeel(switch (themeName != null ? themeName : "") {
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
