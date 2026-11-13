package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.text.BadLocationException;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.BuildContainerService;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AgentProfile;
import org.roxycode.app.model.AgentProfileOption;
import org.roxycode.app.service.ProjectService;
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
final class BuildConfigPanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(BuildConfigPanel.class);
    static final int TAB_INDEX_BUILD_CONFIG = 0;
    static final int TAB_INDEX_AI_MODEL = 1;
    static final String TAB_BUILD_CONFIG_TITLE = "Build Configuration";
    static final String TAB_AI_MODEL_TITLE = "AI Model Override";
    static final String TITLE_TEXT = "Build Configuration (build.toml)";
    static final String SUBTITLE_TEXT = "Configure project-specific build, test, lint, and sandbox execution commands (build.toml).";
    static final String MODEL_TITLE_TEXT = "AI Model Override";
    static final String MODEL_SUBTITLE_TEXT = "Override the default Global Settings Agent Profile specifically for the active project.";
    static final String SECTION_AI_MODEL_TITLE = "AI Model Override";
    static final String BUILD_CONFIG_HINT_TEXT = "Need help customizing for your stack?";
    static final String CONFIGURE_WITH_ROXY_TEXT = "Configure with Roxy";
    static final String COPY_TOML_TEXT = "Copy";
    static final String APPLIED_FROM_ASK_ROXY_STATUS = "Applied build.toml from Ask Roxy! Review and click 'Confirm & Save Settings'.";
    static final String SAVE_BUTTON_TEXT = "Confirm & Save Settings";
    static final String SAVED_FEEDBACK_BUTTON_TEXT = "Confirmed & Saved!";
    static final String CONFIRMED_BUTTON_TEXT = "Settings Confirmed";
    static final String MODEL_SAVE_BUTTON_TEXT = "Save Model Settings";
    static final String MODEL_SAVED_FEEDBACK_BUTTON_TEXT = "Saved!";
    static final String MODEL_SAVED_STATUS_TEXT = "Model override settings saved!";
    private static final String DEFAULT_TEMPLATE_ID = "default";
    private static final String HEADER_FONT_STYLE = "font: bold +2";
    private static final String SAVE_BUTTON_BASE_STYLE = "arc: 8; font: bold; ";
    private static final String GROW_FILL = "[grow, fill]";
    private static final String WMIN_0 = "wmin 0";
    private static final String GROWX_WMIN_0 = "growx, wmin 0";
    private static final String ROW_CENTER = "[center]";
    private static final String BORDER_BG_STYLE = ",1,10; background: ";
    private static final int MAX_SUMMARY_COMMANDS_PREVIEW = 4;
    private static final Color LIGHT_VIOLET_BG = new Color(0xF5, 0xF3, 0xFF);
    private static final Color LIGHT_VIOLET_BORDER = new Color(0x8B, 0x5C, 0xF6);
    private static final Color LIGHT_VIOLET_FG = new Color(0x5B, 0x21, 0xB6);
    private static final Color DARK_VIOLET_BG = new Color(0x2E, 0x10, 0x65);
    private static final Color DARK_VIOLET_BORDER = new Color(0x7C, 0x3A, 0xED);
    private static final Color DARK_VIOLET_FG = new Color(0xDD, 0xD6, 0xFE);
    static final String UNCONFIRMED_BUILD_NOTICE_TEXT = "Build configuration was initialized from the auto-detected template. Please review the commands below (or click 'Configure with Roxy') and click 'Confirm & Save Settings'.";
    static final String BUILD_TOML_GUIDE_TOOLTIP_HTML = """
            <html><div style='width: 390px;'>
            <b>build.toml Quick Reference</b><br><br>
            &bull; <b><code>[commands]</code></b>: Shell commands for <code>install</code>, <code>clean</code>, <code>compile</code>, <code>build</code>, <code>test_all</code>, <code>test_single</code> (uses <code>{testName}</code> placeholder), <code>lint</code>, and <code>format</code>.<br>
            &bull; <b><code>[env]</code></b>: Key-value environment variables passed to all build processes (e.g., <code>JAVA_HOME</code>).<br>
            &bull; <b><code>[parse]</code></b>: <code>error_regex</code>, <code>test_failure_regex</code>, <code>context_lines</code>, and optional <code>junit_xml_paths</code> for structured failure extraction.<br>
            &bull; <b><code>[sandbox]</code></b>: <code>mode</code> (<code>"none"</code>, <code>"native"</code> for Linux <code>bwrap</code> / macOS <code>sandbox-exec</code>, or <code>"docker"</code>), <code>readable_paths</code>, <code>writable_paths</code>, <code>image</code>, and <code>workdir</code>.<br><br>
            <i>Click <b>Configure with Roxy</b> to automatically tailor build.toml to your project and host toolchains!</i>
            </div></html>""";

    private final transient GenericBuildToolService buildToolService;
    private final transient SettingsService settingsService;
    private final transient ProjectService projectService;
    final JTabbedPane settingsTabs;
    final JPanel buildTabPanel;
    final JPanel modelTabPanel;
    final JLabel titleLabel;
    final JLabel subtitleLabel;
    final JPanel summaryStripPanel;
    final SummaryCard pipelineSummaryCard;
    final SummaryCard sandboxSummaryCard;
    final SummaryCard parserSummaryCard;
    final SummaryCard syntaxSummaryCard;
    final JLabel modelTitleLabel;
    final JLabel modelSubtitleLabel;
    final JPanel modelCardPanel;
    final JPanel buildCardPanel;
    final JLabel modelSectionHeaderLabel;
    final JLabel modelOverrideStatusPill;
    final JComboBox<AgentProfileOption> profileCombo;
    final JLabel profileInfoIcon;
    final JLabel modelApiBadgeLabel;
    final JTextArea modelDescriptionArea;
    final GlobalDefaultCard globalDefaultProfileCard;
    final JLabel overrideSubheaderLabel;
    final Map<AgentProfile, ProjectProfileCard> profileCards = new EnumMap<>(AgentProfile.class);
    final JButton modelSaveButton;
    final JLabel modelStatusLabel;
    final Timer modelStatusTimer;
    final Timer modelSaveFeedbackTimer;
    final JLabel buildTomlFileBadge;
    final JLabel buildConfigInfoIcon;
    final JLabel jumpLabel;
    final JButton jumpCommandsButton;
    final JButton jumpEnvButton;
    final JButton jumpParseButton;
    final JButton jumpSandboxButton;
    final JLabel buildConfigHintLabel;
    final JButton configureWithRoxyButton;
    final JButton copyTomlButton;
    final UiComponents.WarningBanner unconfirmedBanner;
    final RSyntaxTextArea textArea;
    final JButton saveButton;
    final JLabel statusLabel;
    final Timer statusTimer;
    final Timer saveFeedbackTimer;
    private Timer copyTomlTimer;

    private final transient UiComponents.UiGuard textUpdateGuard = new UiComponents.UiGuard();
    private boolean textEditedByUser = false;
    private boolean lastStatusSuccess = true;
    private String savedTomlContent = "";
    private String savedProfileId = "";
    private String selectedProfileId = "";
    String detectedTemplateId = DEFAULT_TEMPLATE_ID;
    private transient Runnable onConfirmSavedCallback;
    private transient Consumer<String> onConfigureWithRoxyCallback;
    transient CompletableFuture<?> lastAsyncFuture = CompletableFuture.completedFuture(null);

    @Autowired
    public BuildConfigPanel(GenericBuildToolService buildToolService, SettingsService settingsService,
            @Nullable ProjectService projectService) {
        super(new BorderLayout());
        this.buildToolService = buildToolService;
        this.settingsService = settingsService;
        this.projectService = projectService;
        setOpaque(true);

        // --- Tab 1: Build Configuration (build.toml) ---
        buildTabPanel = new JPanel(new MigLayout("fill, insets 20, hidemode 3", GROW_FILL, "[][]14" + GROW_FILL));
        buildTabPanel.setOpaque(true);

        titleLabel = new JLabel(TITLE_TEXT);
        subtitleLabel = new JLabel(SUBTITLE_TEXT);
        statusLabel = new JLabel("");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 11.5f));
        statusLabel.setVisible(false);

        saveButton = UiComponents.createActionButton(SAVE_BUTTON_TEXT, Codicons.SAVE, new Insets(6, 14, 6, 14), false,
                SAVE_BUTTON_BASE_STYLE + UiComponents.ACCENT_BUTTON_STYLE, e -> saveConfig());
        saveButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        saveButton.setIconTextGap(6);

        saveFeedbackTimer = createFeedbackTimer(this::updateSaveButtonState);

        buildTabPanel.add(UiComponents.createPageHeader(titleLabel, subtitleLabel, saveButton), "growx, wmin 0, wrap");

        summaryStripPanel = new JPanel(new MigLayout("insets 0, fillx, gap 12",
                "[grow, fill, sg sum][grow, fill, sg sum][grow, fill, sg sum][grow, fill, sg sum]", "[grow, fill]"));
        summaryStripPanel.setOpaque(false);
        pipelineSummaryCard = new SummaryCard("Pipeline Commands", Codicons.TERMINAL);
        sandboxSummaryCard = new SummaryCard("Sandbox Isolation", Codicons.SHIELD);
        parserSummaryCard = new SummaryCard("Log Parser", Codicons.FILTER);
        syntaxSummaryCard = new SummaryCard("Timeout & Syntax", Codicons.PULSE);
        summaryStripPanel.add(pipelineSummaryCard);
        summaryStripPanel.add(sandboxSummaryCard);
        summaryStripPanel.add(parserSummaryCard);
        summaryStripPanel.add(syntaxSummaryCard);
        buildTabPanel.add(summaryStripPanel, "growx, wmin 0, wrap, gapbottom 10");

        buildCardPanel = new JPanel(
                new MigLayout("fill, insets 14 18 16 18, hidemode 3", GROW_FILL, "[][]" + GROW_FILL));
        UiComponents.styleCardPanel(buildCardPanel);

        buildTomlFileBadge = new JLabel("build.toml");
        UiComponents.styleCodePillBadge(buildTomlFileBadge);
        buildConfigInfoIcon = UiComponents.createInfoIcon(BUILD_TOML_GUIDE_TOOLTIP_HTML);

        jumpLabel = new JLabel("Jump:");
        jumpLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1; foreground: $Label.disabledForeground");
        jumpCommandsButton = createSectionJumpButton("[commands]");
        jumpEnvButton = createSectionJumpButton("[env]");
        jumpParseButton = createSectionJumpButton("[parse]");
        jumpSandboxButton = createSectionJumpButton("[sandbox]");

        buildConfigHintLabel = new JLabel(BUILD_CONFIG_HINT_TEXT);
        buildConfigHintLabel.putClientProperty(FlatClientProperties.STYLE,
                "font: -1; foreground: $Label.disabledForeground");
        configureWithRoxyButton = UiComponents.createActionButton(CONFIGURE_WITH_ROXY_TEXT, Codicons.LIGHTBULB_AUTOFIX,
                null, false, null, e -> configureWithRoxy());
        configureWithRoxyButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        configureWithRoxyButton.setToolTipText(
                "Inspect project build files and installed host toolchains to automatically generate build.toml with Roxy");

        copyTomlButton = UiComponents.createActionButton(COPY_TOML_TEXT, Codicons.CLIPPY, new Insets(4, 10, 4, 10),
                false, "arc: 8; font: -1", e -> copyTomlToClipboard());
        copyTomlButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        copyTomlButton.setToolTipText("Copy build.toml content to clipboard");

        JPanel buildToolbarRow = new JPanel(new MigLayout("fillx, insets 0, gapx 6, hidemode 3",
                "[][][][][][][][0:0, grow, right][][]", ROW_CENTER));
        buildToolbarRow.setOpaque(false);
        buildToolbarRow.add(buildTomlFileBadge);
        buildToolbarRow.add(buildConfigInfoIcon, "gapright 6");
        buildToolbarRow.add(jumpLabel);
        buildToolbarRow.add(jumpCommandsButton);
        buildToolbarRow.add(jumpEnvButton);
        buildToolbarRow.add(jumpParseButton);
        buildToolbarRow.add(jumpSandboxButton);
        buildToolbarRow.add(statusLabel, "cell 7 0, wmin 0, hidemode 3");
        buildToolbarRow.add(buildConfigHintLabel, "cell 7 0, wmin 0, hidemode 3");
        buildToolbarRow.add(configureWithRoxyButton, "cell 8 0");
        buildToolbarRow.add(copyTomlButton, "cell 9 0");
        buildCardPanel.add(buildToolbarRow, "growx, wmin 0, wrap, gapbottom 8");

        refreshDetectedTemplate();

        unconfirmedBanner = new UiComponents.WarningBanner(UNCONFIRMED_BUILD_NOTICE_TEXT);
        buildCardPanel.add(unconfirmedBanner, "growx, wmin 0, wrap, gapbottom 8, hidemode 3");

        textArea = new RSyntaxTextArea();
        UiComponents.configureSyntaxTextArea(textArea, SyntaxConstants.SYNTAX_STYLE_INI, true, false);
        SwingHtmlUtils.addChangeListener(textArea, this::onTomlTextEdited);
        SwingHtmlUtils.applyRSyntaxTheme(textArea, settingsService);

        buildCardPanel.add(new RTextScrollPane(textArea), "grow, push");
        buildTabPanel.add(buildCardPanel, "grow, push, wmin 0");

        // --- Tab 2: AI Model Override ---
        modelTabPanel = new JPanel(new MigLayout("fill, insets 20, hidemode 3", GROW_FILL, "[]14" + GROW_FILL));
        modelTabPanel.setOpaque(true);

        modelTitleLabel = new JLabel(MODEL_TITLE_TEXT);
        modelSubtitleLabel = new JLabel(MODEL_SUBTITLE_TEXT);
        modelOverrideStatusPill = new JLabel();
        modelStatusLabel = new JLabel("");
        modelStatusLabel.setFont(modelStatusLabel.getFont().deriveFont(Font.BOLD, 11.5f));

        modelSaveButton = UiComponents.createActionButton(MODEL_SAVE_BUTTON_TEXT, Codicons.SAVE,
                new Insets(6, 14, 6, 14), false, SAVE_BUTTON_BASE_STYLE + UiComponents.ACCENT_BUTTON_STYLE,
                e -> saveModelConfig());
        modelSaveButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        modelSaveButton.setIconTextGap(6);

        modelSaveFeedbackTimer = createFeedbackTimer(this::updateModelSaveButtonState);

        JPanel modelHeaderActions = new JPanel(new MigLayout("insets 0, gapx 10", "[][][]", ROW_CENTER));
        modelHeaderActions.setOpaque(false);
        modelHeaderActions.add(modelOverrideStatusPill);
        modelHeaderActions.add(modelStatusLabel);
        modelHeaderActions.add(modelSaveButton);

        modelTabPanel.add(UiComponents.createPageHeader(modelTitleLabel, modelSubtitleLabel, modelHeaderActions),
                "growx, wmin 0, wrap");

        modelCardPanel = new JPanel(new MigLayout("fillx, wrap 1, insets 16 18 16 18, gapy 10", GROW_FILL, ""));
        UiComponents.styleCardPanel(modelCardPanel);

        modelSectionHeaderLabel = UiComponents.createSectionHeader(SECTION_AI_MODEL_TITLE, Codicons.HUBOT,
                HEADER_FONT_STYLE);
        profileInfoIcon = UiComponents.createInfoIcon(UiComponents.AGENT_PROFILE_TOOLTIP_HTML);
        JPanel modelSectionRow = new JPanel(new MigLayout("insets 0, gapx 6", "[][]", ROW_CENTER));
        modelSectionRow.setOpaque(false);
        modelSectionRow.add(modelSectionHeaderLabel);
        modelSectionRow.add(profileInfoIcon);
        modelCardPanel.add(modelSectionRow, "growx, wmin 0, gapbottom 2");

        profileCombo = new JComboBox<>();
        modelApiBadgeLabel = new JLabel();
        UiComponents.styleCodePillBadge(modelApiBadgeLabel);
        modelDescriptionArea = UiComponents.createReadOnlyTextArea();

        globalDefaultProfileCard = new GlobalDefaultCard();
        modelCardPanel.add(globalDefaultProfileCard, GROWX_WMIN_0);

        overrideSubheaderLabel = new JLabel("OR OVERRIDE SPECIFICALLY FOR THIS PROJECT:");
        overrideSubheaderLabel.putClientProperty(FlatClientProperties.STYLE,
                "font: bold -2; foreground: $Label.disabledForeground");
        modelCardPanel.add(overrideSubheaderLabel, "growx, wmin 0, gaptop 4");

        JPanel profileGrid = new JPanel(new MigLayout("insets 0, fillx, gap 12",
                "[grow, fill, sg profile][grow, fill, sg profile][grow, fill, sg profile]", "[grow, fill]"));
        profileGrid.setOpaque(false);
        for (AgentProfile profile : AgentProfile.values()) {
            ProjectProfileCard card = new ProjectProfileCard(profile);
            profileCards.put(profile, card);
            profileGrid.add(card);
        }
        modelCardPanel.add(profileGrid, GROWX_WMIN_0);

        JPanel modelCardsContainer = UiComponents
                .createScrollableCardsPanel(new MigLayout("fillx, wrap 1, insets 4 2 16 6, gapy 14", GROW_FILL, ""));
        modelCardsContainer.add(modelCardPanel, GROWX_WMIN_0);
        modelTabPanel.add(UiComponents.createBorderlessScrollPane(modelCardsContainer), "grow, wmin 0");

        settingsTabs = UiComponents.createUnderlinedTabbedPane(null);
        settingsTabs.addTab(TAB_BUILD_CONFIG_TITLE, FontIcon.of(Codicons.PACKAGE, 15, SwingHtmlUtils.getAccentColor()),
                buildTabPanel);
        settingsTabs.addTab(TAB_AI_MODEL_TITLE,
                FontIcon.of(Codicons.HUBOT, 15, SwingHtmlUtils.getMutedForegroundColor()), modelTabPanel);
        settingsTabs.addChangeListener(e -> updateTabIcons());

        loadProfileOptions();
        profileCombo.addActionListener(e -> {
            updateSelectedProfileDetails();
            onComboSelectionChanged();
        });
        updateSelectedProfileDetails();

        add(settingsTabs, BorderLayout.CENTER);

        statusTimer = new Timer(3000, e -> clearBuildStatus());
        statusTimer.setRepeats(false);
        modelStatusTimer = UiComponents.createStatusTimer(modelStatusLabel, 3000);

        applySectionStyling();
        updateSummaryCards();
        lastAsyncFuture = loadConfig();
        updateBannerState();
    }

    private JButton createSectionJumpButton(String sectionHeader) {
        JButton btn = UiComponents.createActionButton(sectionHeader, null, new Insets(2, 8, 2, 8), false,
                "font: $monospaced.font -1; arc: 8", e -> jumpToSection(sectionHeader));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setToolTipText("Jump to " + sectionHeader + " section in build.toml");
        return btn;
    }

    void jumpToSection(String sectionHeader) {
        if (textArea == null || StringUtils.isBlank(sectionHeader)) {
            return;
        }
        String content = StringUtils.defaultString(textArea.getText());
        String[] lines = content.split("\\R", -1);
        int offset = 0;
        int matchedOffset = -1;
        for (String line : lines) {
            if (line.trim().equals(sectionHeader)) {
                matchedOffset = offset;
                break;
            }
            offset += line.length() + 1;
        }
        if (matchedOffset < 0) {
            return;
        }
        int clampedOffset = Math.min(matchedOffset, textArea.getDocument().getLength());
        textArea.setCaretPosition(clampedOffset);
        try {
            Rectangle2D rect = textArea.modelToView2D(clampedOffset);
            if (rect != null) {
                textArea.scrollRectToVisible(rect.getBounds());
            }
        } catch (BadLocationException ignored) {
            // Caret already moved safely
        }
        textArea.requestFocusInWindow();
    }

    private void copyTomlToClipboard() {
        String content = "";
        if (textArea != null) {
            content = StringUtils.defaultString(textArea.getText());
        }
        copyTomlTimer = UiComponents.copyWithFeedback(copyTomlButton, copyTomlTimer, content, COPY_TOML_TEXT, 2000);
    }

    void selectBuildConfigTab() {
        UiComponents.selectTabSafely(settingsTabs, TAB_INDEX_BUILD_CONFIG);
    }

    void selectAiModelTab() {
        UiComponents.selectTabSafely(settingsTabs, TAB_INDEX_AI_MODEL);
    }

    void updateTabIcons() {
        UiComponents.updateSelectedTabIcons(settingsTabs, List.of(Codicons.PACKAGE, Codicons.HUBOT));
    }

    void refreshDetectedTemplate() {
        if (buildToolService != null) {
            detectedTemplateId = StringUtils.defaultIfBlank(buildToolService.detectTemplate(), DEFAULT_TEMPLATE_ID);
        } else {
            detectedTemplateId = DEFAULT_TEMPLATE_ID;
        }
    }

    private void setEditorContentProgrammatically(String content, boolean updateSavedBaseline) {
        String normalizedContent = StringUtils.defaultString(content);
        textUpdateGuard.run(() -> {
            textArea.setText(normalizedContent);
            textArea.setCaretPosition(0);
            if (updateSavedBaseline) {
                savedTomlContent = normalizedContent;
                textEditedByUser = false;
            } else {
                textEditedByUser = !Objects.equals(normalizedContent, savedTomlContent);
            }
        });
        updateSummaryCards();
        updateBannerState();
    }

    void showBuildStatus(String message, boolean success) {
        lastStatusSuccess = success;
        buildConfigHintLabel.setVisible(false);
        statusLabel.setVisible(true);
        UiComponents.showStatusMessage(statusLabel, statusTimer, message, success);
        statusLabel.setToolTipText(message);
    }

    void clearBuildStatus() {
        UiComponents.clearStatusMessage(statusLabel, statusTimer, true);
        buildConfigHintLabel.setVisible(true);
    }

    void showModelStatus(String message, boolean success) {
        UiComponents.showStatusMessage(modelStatusLabel, modelStatusTimer, message, success);
    }

    void clearModelStatus() {
        UiComponents.clearStatusMessage(modelStatusLabel, modelStatusTimer, false);
    }

    void configureWithRoxy() {
        if (buildToolService == null || onConfigureWithRoxyCallback == null) {
            showBuildStatus("Roxy AI service is not available.", false);
            return;
        }
        performConfigureWithRoxy();
    }

    CompletableFuture<String> performConfigureWithRoxy() {
        refreshDetectedTemplate();
        final String templateId = detectedTemplateId;
        final String currentEditorText = textArea.getText();
        final Consumer<String> callback = onConfigureWithRoxyCallback;
        CompletableFuture<String> future = Edt.supplyAsync(
                () -> buildToolService.buildInteractiveConfigurationPrompt(templateId, currentEditorText),
                callback::accept, ex -> {
                    Throwable cause = ExceptionUtils.getRootCause(ex);
                    log.error("Failed to prepare interactive build.toml prompt: {}", cause.getMessage());
                    showBuildStatus("Roxy configuration failed: " + cause.getMessage(), false);
                });
        lastAsyncFuture = future;
        return future;
    }

    void loadProfileOptions() {
        AgentProfile globalProfile = SettingsService.resolveGlobalAgentProfile(settingsService);
        DefaultComboBoxModel<AgentProfileOption> comboModel = new DefaultComboBoxModel<>();
        comboModel.addElement(AgentProfileOption.useGlobalDefault(globalProfile));
        for (AgentProfile profile : AgentProfile.values()) {
            comboModel.addElement(AgentProfileOption.fromProfile(profile));
        }

        String projectProfile = "";
        if (projectService != null && projectService.hasActiveProject()) {
            projectProfile = StringUtils.defaultString(projectService.getProjectAgentProfile());
        }
        final String targetProfile = normalizeProfileId(projectProfile);
        textUpdateGuard.run(() -> {
            profileCombo.setModel(comboModel);
            UiComponents.selectAgentProfileOptionById(profileCombo, targetProfile);
            selectProfileOverride(targetProfile);
            savedProfileId = selectedProfileId;
        });
        globalDefaultProfileCard.updateGlobalProfileDisplay(globalProfile);
        updateModelSaveButtonState();
    }

    void selectProfileOverride(@Nullable String profileId) {
        String normalized = normalizeProfileId(profileId);
        selectedProfileId = normalized;
        globalDefaultProfileCard.setSelected(normalized.isEmpty());
        for (Map.Entry<AgentProfile, ProjectProfileCard> entry : profileCards.entrySet()) {
            boolean matches = entry.getKey().getId().equalsIgnoreCase(normalized);
            entry.getValue().setSelected(matches);
        }
        if (profileCombo != null && !Objects.equals(resolveComboProfileId(), normalized)) {
            textUpdateGuard.run(() -> UiComponents.selectAgentProfileOptionById(profileCombo, normalized));
        }
        updateSelectedProfileDetails();
        updateModelOverrideIndicators();
        if (!textUpdateGuard.isActive()) {
            UiComponents.stopTimer(modelSaveFeedbackTimer);
            clearModelStatus();
            updateModelSaveButtonState();
        }
    }

    private static String normalizeProfileId(@Nullable String profileId) {
        String trimmed = StringUtils.trimToEmpty(profileId);
        if (trimmed.isEmpty()) {
            return "";
        }
        return AgentProfile.fromString(trimmed).getId();
    }

    private void updateModelOverrideIndicators() {
        AgentProfile globalProfile = SettingsService.resolveGlobalAgentProfile(settingsService);
        String pillText;
        String tabTitle;
        boolean overridden = StringUtils.isNotBlank(selectedProfileId);
        if (overridden) {
            AgentProfile overrideProfile = AgentProfile.fromString(selectedProfileId);
            pillText = "Override: " + overrideProfile.getDisplayName();
            tabTitle = TAB_AI_MODEL_TITLE + " (" + overrideProfile.getDisplayName() + ")";
        } else {
            pillText = "Global (" + globalProfile.getDisplayName() + ")";
            tabTitle = TAB_AI_MODEL_TITLE + " (Global)";
        }
        if (modelOverrideStatusPill != null) {
            modelOverrideStatusPill.setText(pillText);
            Color accentColor = SwingHtmlUtils.getAccentColor();
            Color mutedColor = SwingHtmlUtils.getMutedForegroundColor();
            String borderHex = UiComponents.getCardBorderHex();
            String pillBg = UiComponents.getPillBackgroundDirective();
            if (overridden) {
                String accentHex = SwingHtmlUtils.toHex(accentColor);
                modelOverrideStatusPill.setForeground(accentColor);
                modelOverrideStatusPill.setIcon(FontIcon.of(Codicons.HUBOT, 13, accentColor));
                modelOverrideStatusPill.putClientProperty(FlatClientProperties.STYLE,
                        "font: bold -1; arc: 10; border: 3,9,3,9," + accentHex + BORDER_BG_STYLE + "fade(" + accentHex
                                + ", 10%)");
            } else {
                modelOverrideStatusPill.setForeground(mutedColor);
                modelOverrideStatusPill.setIcon(FontIcon.of(Codicons.GLOBE, 13, mutedColor));
                modelOverrideStatusPill.putClientProperty(FlatClientProperties.STYLE,
                        "font: bold -1; arc: 10; border: 3,9,3,9," + borderHex + BORDER_BG_STYLE + pillBg);
            }
        }
        if (settingsTabs != null && settingsTabs.getTabCount() > TAB_INDEX_AI_MODEL) {
            settingsTabs.setTitleAt(TAB_INDEX_AI_MODEL, tabTitle);
        }
    }

    void updateSelectedProfileDetails() {
        UiComponents.updateProfileSelectionDetails(profileCombo, modelApiBadgeLabel, modelDescriptionArea);
    }

    private String resolveComboProfileId() {
        if (profileCombo == null) {
            return "";
        }
        AgentProfileOption selectedOption = (AgentProfileOption) profileCombo.getSelectedItem();
        if (selectedOption != null && selectedOption.id() != null) {
            return normalizeProfileId(selectedOption.id());
        }
        return "";
    }

    private String resolveSelectedProfileId() {
        if (selectedProfileId != null) {
            return selectedProfileId;
        }
        return resolveComboProfileId();
    }

    boolean hasUnsavedChanges() {
        String currentText = "";
        if (textArea != null) {
            currentText = StringUtils.defaultString(textArea.getText());
        }
        return textEditedByUser || !Objects.equals(currentText, savedTomlContent);
    }

    boolean hasUnsavedModelChanges() {
        return !Objects.equals(resolveSelectedProfileId(), savedProfileId);
    }

    private static Timer createFeedbackTimer(Runnable onExpire) {
        Timer timer = new Timer(2500, null);
        timer.setRepeats(false);
        timer.addActionListener(e -> {
            timer.stop();
            onExpire.run();
        });
        return timer;
    }

    private void onComboSelectionChanged() {
        if (!textUpdateGuard.isActive()) {
            selectProfileOverride(resolveComboProfileId());
        }
    }

    void updateSaveButtonState() {
        if (saveButton == null) {
            return;
        }
        if (saveFeedbackTimer != null && saveFeedbackTimer.isRunning()) {
            applyButtonPresentation(saveButton, SAVED_FEEDBACK_BUTTON_TEXT, Codicons.CHECK,
                    SAVE_BUTTON_BASE_STYLE + UiComponents.GREEN_BUTTON_STYLE);
            return;
        }
        boolean confirmed = projectService != null && projectService.hasActiveProject()
                && projectService.isBuildConfirmed();
        if (confirmed && !hasUnsavedChanges()) {
            applyButtonPresentation(saveButton, CONFIRMED_BUTTON_TEXT, Codicons.CHECK,
                    SAVE_BUTTON_BASE_STYLE + UiComponents.SUBTLE_GREEN_BUTTON_STYLE);
        } else {
            applyButtonPresentation(saveButton, SAVE_BUTTON_TEXT, Codicons.SAVE,
                    SAVE_BUTTON_BASE_STYLE + UiComponents.ACCENT_BUTTON_STYLE);
        }
    }

    void updateModelSaveButtonState() {
        if (modelSaveButton == null) {
            return;
        }
        if (modelSaveFeedbackTimer != null && modelSaveFeedbackTimer.isRunning()) {
            applyButtonPresentation(modelSaveButton, MODEL_SAVED_FEEDBACK_BUTTON_TEXT, Codicons.CHECK,
                    SAVE_BUTTON_BASE_STYLE + UiComponents.GREEN_BUTTON_STYLE);
        } else {
            applyButtonPresentation(modelSaveButton, MODEL_SAVE_BUTTON_TEXT, Codicons.SAVE,
                    SAVE_BUTTON_BASE_STYLE + UiComponents.ACCENT_BUTTON_STYLE);
        }
    }

    private static void applyButtonPresentation(JButton button, String text, Codicons icon, String style) {
        button.setText(text);
        button.putClientProperty(FlatClientProperties.STYLE, style);
        button.setIcon(FontIcon.of(icon, 16, UiComponents.resolveActionButtonIconColor(style)));
    }

    private GenericBuildToolService.BuildConfig parseEditorToml(String rawToml) throws IOException {
        return GenericBuildToolService.parseConfigStatic(rawToml);
    }

    void updateSummaryCards() {
        if (pipelineSummaryCard == null) {
            return;
        }
        String rawToml = "";
        if (textArea != null) {
            rawToml = StringUtils.defaultString(textArea.getText());
        }
        Color accentColor = SwingHtmlUtils.getAccentColor();
        Color mutedColor = SwingHtmlUtils.getMutedForegroundColor();
        Color greenColor = SwingHtmlUtils.getUiColor(new Color(0x28, 0xa7, 0x45), "Actions.Green");
        Color redColor = SwingHtmlUtils.getUiColor(new Color(0xdc, 0x35, 0x45), "Actions.Red");

        try {
            GenericBuildToolService.BuildConfig config = parseEditorToml(rawToml);
            updatePipelineCard(config.commands(), accentColor, mutedColor);
            updateSandboxCard(config.sandbox(), accentColor, greenColor, mutedColor);
            updateParserCard(config.parse(), accentColor, mutedColor);

            int timeout = GenericBuildToolService.DEFAULT_TIMEOUT_SECONDS;
            if (config.timeoutSeconds() != null) {
                timeout = config.timeoutSeconds();
            }
            String envSummary = config.env().size() + " env var(s)";
            syntaxSummaryCard.updateContent(timeout + "s • Valid TOML", envSummary, greenColor, Codicons.CHECK);
        } catch (IOException ex) {
            String errorMsg = StringUtils.defaultIfBlank(ex.getMessage(), "Invalid TOML syntax");
            pipelineSummaryCard.updateContent("— / 8 configured", "Fix TOML syntax to inspect", mutedColor,
                    Codicons.TERMINAL);
            sandboxSummaryCard.updateContent("Unavailable", "Fix TOML syntax to inspect", mutedColor, Codicons.SHIELD);
            parserSummaryCard.updateContent("Unavailable", "Fix TOML syntax to inspect", mutedColor, Codicons.FILTER);
            syntaxSummaryCard.updateContent("Invalid TOML Syntax", errorMsg, redColor, Codicons.WARNING);
        }
    }

    private void updatePipelineCard(GenericBuildToolService.CommandsSection cmds, Color accentColor, Color mutedColor) {
        List<String> configured = new ArrayList<>();
        addIfNotBlank(configured, "install", cmds.install());
        addIfNotBlank(configured, "clean", cmds.clean());
        addIfNotBlank(configured, "compile", cmds.compile());
        addIfNotBlank(configured, "build", cmds.build());
        addIfNotBlank(configured, "test_all", cmds.testAll());
        addIfNotBlank(configured, "test_single", cmds.testSingle());
        addIfNotBlank(configured, "lint", cmds.lint());
        addIfNotBlank(configured, "format", cmds.format());

        int count = configured.size();
        String subtitle;
        Color valueColor;
        if (count == 0) {
            subtitle = "No commands configured";
            valueColor = mutedColor;
        } else {
            valueColor = accentColor;
            if (count <= MAX_SUMMARY_COMMANDS_PREVIEW) {
                subtitle = String.join(", ", configured);
            } else {
                subtitle = String.join(", ", configured.subList(0, MAX_SUMMARY_COMMANDS_PREVIEW)) + " +"
                        + (count - MAX_SUMMARY_COMMANDS_PREVIEW);
            }
        }
        pipelineSummaryCard.updateContent(count + " / 8 configured", subtitle, valueColor, Codicons.TERMINAL);
    }

    private static void addIfNotBlank(List<String> target, String label, @Nullable String command) {
        if (StringUtils.isNotBlank(command)) {
            target.add(label);
        }
    }

    private void updateSandboxCard(BuildContainerService.SandboxConfig sandbox, Color accentColor, Color greenColor,
            Color mutedColor) {
        BuildContainerService.SandboxMode mode = sandbox.resolvedMode();
        if (mode == BuildContainerService.SandboxMode.NATIVE) {
            String subtitle = sandbox.readablePaths().size() + " read, " + sandbox.writablePaths().size()
                    + " write paths";
            sandboxSummaryCard.updateContent("native (OS sandbox)", subtitle, greenColor, Codicons.SHIELD);
        } else if (mode == BuildContainerService.SandboxMode.DOCKER) {
            sandboxSummaryCard.updateContent("docker (" + sandbox.image() + ")", "workdir: " + sandbox.workdir(),
                    accentColor, Codicons.SHIELD);
        } else {
            sandboxSummaryCard.updateContent("none (Host execution)", "Runs directly on host", mutedColor,
                    Codicons.SHIELD);
        }
    }

    private void updateParserCard(GenericBuildToolService.ParseSection parse, Color accentColor, Color mutedColor) {
        boolean hasErrorRegex = StringUtils.isNotBlank(parse.errorRegex());
        boolean hasTestRegex = StringUtils.isNotBlank(parse.testFailureRegex());
        int contextLines = parse.resolvedContextLines();
        int xmlCount = parse.resolvedJunitXmlPaths().size();

        String value;
        Color color;
        if (hasErrorRegex && hasTestRegex) {
            value = "Error & Test regexes";
            color = accentColor;
        } else if (hasErrorRegex) {
            value = "Error regex";
            color = accentColor;
        } else if (hasTestRegex) {
            value = "Test regex";
            color = accentColor;
        } else {
            value = "Default log tail";
            color = mutedColor;
        }
        String subtitle = contextLines + " lines • " + xmlCount + " JUnit path(s)";
        parserSummaryCard.updateContent(value, subtitle, color, Codicons.FILTER);
    }

    private void applyConfigureWithRoxyButtonStyling() {
        if (configureWithRoxyButton == null) {
            return;
        }
        boolean dark = SwingHtmlUtils.isDarkTheme(resolveCurrentThemeName());
        Color bgColor;
        Color borderColor;
        Color fgColor;
        if (dark) {
            bgColor = DARK_VIOLET_BG;
            borderColor = DARK_VIOLET_BORDER;
            fgColor = DARK_VIOLET_FG;
        } else {
            bgColor = LIGHT_VIOLET_BG;
            borderColor = LIGHT_VIOLET_BORDER;
            fgColor = LIGHT_VIOLET_FG;
        }
        String style = "arc: 10; font: bold; border: 1,10,1,10," + SwingHtmlUtils.toHex(borderColor)
                + ",1,10; background: " + SwingHtmlUtils.toHex(bgColor) + "; foreground: "
                + SwingHtmlUtils.toHex(fgColor);
        configureWithRoxyButton.putClientProperty(FlatClientProperties.STYLE, style);
        configureWithRoxyButton.setForeground(fgColor);
        configureWithRoxyButton.setIcon(FontIcon.of(Codicons.LIGHTBULB_AUTOFIX, 15, fgColor));
    }

    @Nullable
    private String resolveCurrentThemeName() {
        if (settingsService != null && settingsService.getSettings() != null) {
            return settingsService.getSettings().getTheme();
        }
        return null;
    }

    private void applySectionStyling() {
        Color accentColor = SwingHtmlUtils.getAccentColor();

        UiComponents.styleCardPanel(modelCardPanel);
        UiComponents.styleCardPanel(buildCardPanel);
        UiComponents.styleCodePillBadge(modelApiBadgeLabel);
        UiComponents.styleCodePillBadge(buildTomlFileBadge);
        modelSectionHeaderLabel.setIcon(FontIcon.of(Codicons.HUBOT, 18, accentColor));
        UiComponents.updateInfoIconTheme(profileInfoIcon);
        UiComponents.updateInfoIconTheme(buildConfigInfoIcon);
        UiComponents.refreshActionButtonIcons(this);
        applyConfigureWithRoxyButtonStyling();
        updateTabIcons();
        updateSaveButtonState();
        updateModelSaveButtonState();
        updateModelOverrideIndicators();
        updateSummaryCards();

        if (lastStatusSuccess) {
            statusLabel.setForeground(SwingHtmlUtils.getUiColor(new Color(0x28, 0xa7, 0x45), "Actions.Green"));
        } else {
            statusLabel.setForeground(SwingHtmlUtils.getUiColor(new Color(0xdc, 0x35, 0x45), "Actions.Red"));
        }
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        Edt.run(() -> {
            SwingHtmlUtils.applyRSyntaxTheme(textArea, settingsService);
            applySectionStyling();
            loadProfileOptions();
            updateSelectedProfileDetails();
            updateBannerState();
        });
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        Edt.run(() -> {
            UiComponents.stopTimer(saveFeedbackTimer);
            UiComponents.stopTimer(modelSaveFeedbackTimer);
            clearBuildStatus();
            clearModelStatus();
            loadProfileOptions();
            updateSelectedProfileDetails();
            refreshDetectedTemplate();
            loadConfig();
            updateBannerState();
        });
    }

    private void onTomlTextEdited() {
        if (!textUpdateGuard.isActive()) {
            String currentText = StringUtils.defaultString(textArea.getText());
            textEditedByUser = !Objects.equals(currentText, savedTomlContent);
            UiComponents.stopTimer(saveFeedbackTimer);
            clearBuildStatus();
            updateSummaryCards();
            updateBannerState();
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (modelCardPanel != null && buildCardPanel != null) {
            applySectionStyling();
        }
        if (unconfirmedBanner != null) {
            updateBannerState();
        }
    }

    void updateBannerState() {
        boolean showUnconfirmedNotice = projectService != null && projectService.hasActiveProject()
                && !projectService.isBuildConfirmed();
        unconfirmedBanner.setVisibleState(showUnconfirmedNotice, UNCONFIRMED_BUILD_NOTICE_TEXT);
        updateSaveButtonState();
    }

    CompletableFuture<String> loadConfig() {
        if (buildToolService == null) {
            CompletableFuture<String> done = CompletableFuture.completedFuture("");
            lastAsyncFuture = done;
            return done;
        }
        CompletableFuture<String> future = Edt.supplyAsync(() -> {
            try {
                return StringUtils.defaultString(buildToolService.getConfigContent());
            } catch (IOException e) {
                log.error("Failed to load build config", e);
                return "# Error loading config: " + e.getMessage();
            }
        }, content -> setEditorContentProgrammatically(content, true));
        lastAsyncFuture = future;
        return future;
    }

    CompletableFuture<Void> saveConfig() {
        UiComponents.stopTimer(saveFeedbackTimer);
        if (buildToolService == null && projectService == null) {
            CompletableFuture<Void> done = CompletableFuture.completedFuture(null);
            lastAsyncFuture = done;
            return done;
        }
        String contentToSave = StringUtils.defaultString(textArea.getText());
        final boolean wasBuildConfirmed = projectService != null && projectService.isBuildConfirmed();
        saveButton.setEnabled(false);
        CompletableFuture<Void> future = Edt.runAsync(() -> {
            try {
                if (buildToolService != null) {
                    buildToolService.saveConfigContent(contentToSave);
                }
                if (projectService != null && projectService.hasActiveProject()) {
                    projectService.setBuildConfirmed(true);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, () -> {
            saveButton.setEnabled(true);
            savedTomlContent = contentToSave;
            textEditedByUser = false;
            saveFeedbackTimer.restart();
            updateBannerState();
            showBuildStatus("Configuration saved successfully!", true);
            if (!wasBuildConfirmed && onConfirmSavedCallback != null) {
                onConfirmSavedCallback.run();
            }
        }, ex -> {
            saveButton.setEnabled(true);
            updateSaveButtonState();
            Throwable cause = ExceptionUtils.getRootCause(ex);
            log.error("Failed to save build config: {}", cause.getMessage());
            showBuildStatus("Error saving config: " + cause.getMessage(), false);
        });
        lastAsyncFuture = future;
        return future;
    }

    CompletableFuture<Void> saveModelConfig() {
        UiComponents.stopTimer(modelSaveFeedbackTimer);
        if (projectService == null) {
            CompletableFuture<Void> done = CompletableFuture.completedFuture(null);
            lastAsyncFuture = done;
            return done;
        }
        final String profileOverrideToSave = resolveSelectedProfileId();
        modelSaveButton.setEnabled(false);
        CompletableFuture<Void> future = Edt.runAsync(() -> {
            if (projectService.hasActiveProject()) {
                projectService.setProjectAgentProfile(profileOverrideToSave);
            }
        }, () -> {
            modelSaveButton.setEnabled(true);
            savedProfileId = profileOverrideToSave;
            modelSaveFeedbackTimer.restart();
            updateModelSaveButtonState();
            showModelStatus(MODEL_SAVED_STATUS_TEXT, true);
        }, ex -> {
            modelSaveButton.setEnabled(true);
            updateModelSaveButtonState();
            Throwable cause = ExceptionUtils.getRootCause(ex);
            log.error("Failed to save project model override: {}", cause.getMessage());
            showModelStatus("Error saving model settings: " + cause.getMessage(), false);
        });
        lastAsyncFuture = future;
        return future;
    }

    void setOnConfirmSavedCallback(Runnable callback) {
        this.onConfirmSavedCallback = callback;
    }

    void setOnConfigureWithRoxyCallback(Consumer<String> callback) {
        this.onConfigureWithRoxyCallback = callback;
    }

    void applyGeneratedBuildToml(String toml) {
        selectBuildConfigTab();
        setEditorContentProgrammatically(toml, false);
        showBuildStatus(APPLIED_FROM_ASK_ROXY_STATUS, true);
    }

    final class SummaryCard extends JPanel {
        private static final long serialVersionUID = 1L;

        final JLabel iconLabel;
        final JLabel titleLabel;
        final JLabel valueLabel;
        final JLabel subtitleLabel;

        SummaryCard(String title, Codicons icon) {
            super(new MigLayout("fillx, wrap 1, insets 10 12 10 12, gapy 3", GROW_FILL, ""));
            setOpaque(true);

            JPanel topRow = new JPanel(new MigLayout("insets 0, gapx 6", "[][grow, fill]", ROW_CENTER));
            topRow.setOpaque(false);
            iconLabel = new JLabel(FontIcon.of(icon, 14, SwingHtmlUtils.getAccentColor()));
            titleLabel = new JLabel(title);
            titleLabel.putClientProperty(FlatClientProperties.STYLE,
                    "font: bold -1; foreground: $Label.disabledForeground");
            topRow.add(iconLabel);
            topRow.add(titleLabel, WMIN_0);
            add(topRow, GROWX_WMIN_0);

            valueLabel = new JLabel("—");
            valueLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold +0");
            add(valueLabel, GROWX_WMIN_0);

            subtitleLabel = new JLabel(" ");
            subtitleLabel.putClientProperty(FlatClientProperties.STYLE,
                    "font: -1; foreground: $Label.disabledForeground");
            add(subtitleLabel, GROWX_WMIN_0);

            applyCardTheme();
        }

        void updateContent(String valueText, String subtitleText, Color valueColor, Codicons icon) {
            valueLabel.setText(valueText);
            valueLabel.setForeground(valueColor);
            subtitleLabel.setText(subtitleText);
            subtitleLabel.setToolTipText(subtitleText);
            iconLabel.setIcon(FontIcon.of(icon, 14, valueColor));
        }

        void applyCardTheme() {
            if (titleLabel == null) {
                return;
            }
            String borderHex = UiComponents.getCardBorderHex();
            putClientProperty(FlatClientProperties.STYLE,
                    "arc: 12; border: 1,1,1,1," + borderHex + ",1,12; background: $TextArea.background");
        }

        @Override
        public void updateUI() {
            super.updateUI();
            applyCardTheme();
        }
    }

    final class GlobalDefaultCard extends JPanel {
        private static final long serialVersionUID = 1L;

        final JLabel radioIconLabel;
        final JLabel cardTitleLabel;
        final JLabel currentGlobalBadge;
        final JLabel descriptionLabel;
        private boolean selected;

        GlobalDefaultCard() {
            super(new MigLayout("fillx, wrap 1, insets 12 14 12 14, gapy 4", GROW_FILL, ""));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setOpaque(true);

            JPanel headerRow = new JPanel(
                    new MigLayout("fillx, insets 0, gapx 6", "[][grow, fill][right]", ROW_CENTER));
            headerRow.setOpaque(false);

            radioIconLabel = new JLabel();
            headerRow.add(radioIconLabel);

            cardTitleLabel = new JLabel("Use Global Default Profile");
            cardTitleLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold +1");
            headerRow.add(cardTitleLabel, WMIN_0);

            currentGlobalBadge = new JLabel();
            headerRow.add(currentGlobalBadge, "right");
            add(headerRow, GROWX_WMIN_0);

            descriptionLabel = new JLabel(
                    "Inherits the Agent Profile configured in Global Settings (Settings & Tools).");
            add(descriptionLabel, GROWX_WMIN_0);

            MouseAdapter clickListener = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    selectProfileOverride("");
                }
            };
            addMouseListener(clickListener);
            headerRow.addMouseListener(clickListener);
            descriptionLabel.addMouseListener(clickListener);

            applyCardTheme();
        }

        void updateGlobalProfileDisplay(AgentProfile globalProfile) {
            currentGlobalBadge.setText(
                    "Currently: " + globalProfile.getDisplayName() + " (" + globalProfile.getBadgeSummary() + ")");
        }

        void setSelected(boolean selected) {
            this.selected = selected;
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
            String accentHex = SwingHtmlUtils.toHex(accentColor);
            String borderHex = UiComponents.getCardBorderHex();
            String pillBg = UiComponents.getPillBackgroundDirective();

            if (selected) {
                radioIconLabel.setIcon(FontIcon.of(Codicons.CIRCLE_FILLED, 15, accentColor));
                putClientProperty(FlatClientProperties.STYLE,
                        "arc: 10; border: 2,2,2,2," + accentHex + BORDER_BG_STYLE + "fade(" + accentHex + ", 8%)");
                cardTitleLabel.setForeground(fgColor);
                descriptionLabel.setForeground(fgColor);
            } else {
                radioIconLabel.setIcon(FontIcon.of(Codicons.CIRCLE_OUTLINE, 15, mutedColor));
                putClientProperty(FlatClientProperties.STYLE,
                        "arc: 10; border: 1,1,1,1," + borderHex + BORDER_BG_STYLE + pillBg);
                cardTitleLabel.setForeground(fgColor);
                descriptionLabel.setForeground(mutedColor);
            }
            currentGlobalBadge.setForeground(mutedColor);
            currentGlobalBadge.putClientProperty(FlatClientProperties.STYLE,
                    "font: $monospaced.font -1; arc: 6; border: 3,8,3,8," + borderHex + ",1,6; background: " + pillBg);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            applyCardTheme();
        }
    }

    final class ProjectProfileCard extends JPanel {
        private static final long serialVersionUID = 1L;

        final transient AgentProfile profile;
        final JLabel radioIconLabel;
        final JLabel cardTitleLabel;
        final JLabel selectedBadge;
        final JLabel planModelPill;
        final JLabel codeModelPill;
        final JTextArea descriptionArea;
        private boolean selected;

        ProjectProfileCard(AgentProfile profile) {
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
                    selectProfileOverride(profile.getId());
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
                        "arc: 10; border: 2,2,2,2," + accentHex + BORDER_BG_STYLE + "fade(" + accentHex + ", 8%)");
                cardTitleLabel.setForeground(fgColor);
                descriptionArea.setForeground(fgColor);
            } else {
                radioIconLabel.setIcon(FontIcon.of(Codicons.CIRCLE_OUTLINE, 15, mutedColor));
                putClientProperty(FlatClientProperties.STYLE,
                        "arc: 10; border: 1,1,1,1," + borderHex + BORDER_BG_STYLE + pillBg);
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
