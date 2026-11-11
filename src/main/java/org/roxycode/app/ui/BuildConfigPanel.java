package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.GeminiModelConfig;
import org.roxycode.app.model.ThinkingLevel;
import org.roxycode.app.model.ThinkingLevelOption;
import org.roxycode.app.service.AiService;
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
    static final String TITLE_TEXT = "Project Settings";
    static final String SUBTITLE_TEXT = "Configure project-specific Gemini model override and build execution commands (build.toml).";
    static final String SECTION_AI_MODEL_TITLE = "AI Model Override";
    static final String SECTION_BUILD_CONFIG_TITLE = "Build Configuration (build.toml)";
    static final String BUILD_CONFIG_HINT_TEXT = "Need help customizing build.toml or regexes for your stack? Ask Roxy!";
    static final String CONFIGURE_WITH_ROXY_TEXT = "Configure with Roxy";
    static final String CONFIGURING_WITH_ROXY_TEXT = "Configuring...";
    static final String CONFIGURING_WITH_ROXY_STATUS = "Roxy is inspecting project files and configuring build.toml...";
    static final String APPLIED_FROM_ASK_ROXY_STATUS = "Applied build.toml from Ask Roxy! Review and click 'Confirm & Save Settings'.";
    static final String SAVE_BUTTON_TEXT = "Confirm & Save Settings";
    static final String SAVED_FEEDBACK_BUTTON_TEXT = "Confirmed & Saved!";
    static final String CONFIRMED_BUTTON_TEXT = "Settings Confirmed";
    private static final String DEFAULT_TEMPLATE_ID = "default";
    private static final String HEADER_FONT_STYLE = "font: bold +2";
    private static final String SAVE_BUTTON_BASE_STYLE = "arc: 8; font: bold; ";
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
    private final transient AiService aiService;
    final JLabel titleLabel;
    final JLabel subtitleLabel;
    final JPanel modelCardPanel;
    final JPanel buildCardPanel;
    final JLabel modelSectionHeaderLabel;
    final JComboBox<GeminiModelConfig> modelCombo;
    final JComboBox<ThinkingLevelOption> thinkingLevelCombo;
    final JLabel modelApiBadgeLabel;
    final JTextArea modelDescriptionArea;
    final JLabel buildSectionHeaderLabel;
    final JLabel buildConfigInfoIcon;
    final JLabel buildConfigHintLabel;
    final JButton configureWithRoxyButton;
    final UiComponents.InlineConfirmBar templateConfirmBar;
    final JProgressBar configureProgressBar;
    final JLabel configureStatusLabel;
    final UiComponents.WarningBanner unconfirmedBanner;
    final RSyntaxTextArea textArea;
    final JButton saveButton;
    final JLabel statusLabel;
    private final Timer statusTimer;
    final Timer saveFeedbackTimer;

    private final transient UiComponents.UiGuard textUpdateGuard = new UiComponents.UiGuard();
    private boolean textEditedByUser = false;
    private boolean saveFeedbackActive = false;
    private String savedTomlContent = "";
    private String savedModelApiName = "";
    private String savedThinkingLevelId = "";
    String detectedTemplateId = DEFAULT_TEMPLATE_ID;
    private transient Runnable onConfirmSavedCallback;
    private transient Consumer<String> onConfigureWithRoxyCallback;
    transient CompletableFuture<?> lastAsyncFuture = CompletableFuture.completedFuture(null);

    public BuildConfigPanel(GenericBuildToolService buildToolService, SettingsService settingsService,
            @Nullable ProjectService projectService) {
        this(buildToolService, settingsService, projectService, null);
    }

    @Autowired
    public BuildConfigPanel(GenericBuildToolService buildToolService, SettingsService settingsService,
            @Nullable ProjectService projectService, @Nullable AiService aiService) {
        this.buildToolService = buildToolService;
        this.settingsService = settingsService;
        this.projectService = projectService;
        this.aiService = aiService;
        setLayout(new MigLayout("fill, insets 20, hidemode 3", "[grow, fill]", "[]12[]14[grow, fill]"));
        setOpaque(true);

        titleLabel = new JLabel(TITLE_TEXT);
        subtitleLabel = new JLabel(SUBTITLE_TEXT);
        statusLabel = new JLabel("");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 11.5f));

        saveButton = UiComponents.createActionButton(SAVE_BUTTON_TEXT, Codicons.SAVE, new Insets(6, 14, 6, 14), false,
                SAVE_BUTTON_BASE_STYLE + UiComponents.ACCENT_BUTTON_STYLE, e -> saveConfig());
        saveButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        saveButton.setIconTextGap(6);

        saveFeedbackTimer = new Timer(2500, e -> {
            saveFeedbackActive = false;
            updateSaveButtonState();
        });
        saveFeedbackTimer.setRepeats(false);

        JPanel headerActions = new JPanel(new MigLayout("insets 0, gapx 12", "[][]", "[center]"));
        headerActions.setOpaque(false);
        headerActions.add(statusLabel);
        headerActions.add(saveButton);

        add(UiComponents.createPageHeader(titleLabel, subtitleLabel, headerActions), "growx, wmin 0, wrap");

        modelCardPanel = new JPanel(new MigLayout("fillx, wrap 1, insets 16 18 16 18, gapy 8", "[grow, fill]", ""));
        UiComponents.styleCardPanel(modelCardPanel);

        modelSectionHeaderLabel = UiComponents.createSectionHeader(SECTION_AI_MODEL_TITLE, Codicons.HUBOT,
                HEADER_FONT_STYLE);
        modelCardPanel.add(modelSectionHeaderLabel, "growx, wmin 0, gapbottom 2");

        modelCombo = new JComboBox<>();
        modelApiBadgeLabel = new JLabel();
        UiComponents.styleCodePillBadge(modelApiBadgeLabel);

        modelDescriptionArea = UiComponents.createReadOnlyTextArea();

        JPanel modelControlBox = UiComponents.createModelControlBox(modelApiBadgeLabel, modelCombo, "w 240:280:340");

        modelCardPanel.add(UiComponents.createFieldRow(
                UiComponents.createFieldTextBlock("Project Gemini Model",
                        "Override the default Global Settings Gemini model specifically for the active project.", null),
                modelControlBox, ""), "growx, wmin 0");

        modelCardPanel.add(modelDescriptionArea, "growx, wmin 0, gaptop 2, hidemode 3");

        thinkingLevelCombo = new JComboBox<>();
        JSeparator modelCardSeparator = new JSeparator(SwingConstants.HORIZONTAL);
        modelCardSeparator.setOpaque(false);
        modelCardPanel.add(modelCardSeparator, "growx, gaptop 2, gapbottom 2");
        modelCardPanel.add(UiComponents.createFieldRow(UiComponents.createFieldTextBlock("Project Thinking Level",
                "Override the default Global Settings thinking level specifically for the active project.", null),
                thinkingLevelCombo, "w 240:280:340"), "growx, wmin 0");

        add(modelCardPanel, "growx, wmin 0, wrap");

        loadModelOptions();
        loadThinkingLevelOptions();
        modelCombo.addActionListener(e -> {
            updateSelectedModelDetails();
            onComboSelectionChanged();
        });
        thinkingLevelCombo.addActionListener(e -> onComboSelectionChanged());
        updateSelectedModelDetails();

        buildCardPanel = new JPanel(
                new MigLayout("fill, insets 16 18 16 18, hidemode 3", "[grow, fill]", "[][][][][grow, fill]"));
        UiComponents.styleCardPanel(buildCardPanel);

        buildSectionHeaderLabel = UiComponents.createSectionHeader(SECTION_BUILD_CONFIG_TITLE, Codicons.PACKAGE,
                HEADER_FONT_STYLE);
        buildConfigInfoIcon = UiComponents.createInfoIcon(BUILD_TOML_GUIDE_TOOLTIP_HTML);
        buildConfigHintLabel = new JLabel(BUILD_CONFIG_HINT_TEXT);
        buildConfigHintLabel.putClientProperty(FlatClientProperties.STYLE,
                "font: -1; foreground: $Label.disabledForeground");
        configureWithRoxyButton = UiComponents.createActionButton(CONFIGURE_WITH_ROXY_TEXT, Codicons.HUBOT, null, false,
                null, e -> configureWithRoxy());
        configureWithRoxyButton.setToolTipText(
                "Inspect project build files and installed host toolchains to automatically generate build.toml with Roxy");

        JPanel buildHeaderRow = new JPanel(
                new MigLayout("fillx, insets 0, gapx 8", "[][][grow][right][right]", "[center]"));
        buildHeaderRow.setOpaque(false);
        buildHeaderRow.add(buildSectionHeaderLabel);
        buildHeaderRow.add(buildConfigInfoIcon);
        buildHeaderRow.add(buildConfigHintLabel, "skip 1");
        buildHeaderRow.add(configureWithRoxyButton);
        buildCardPanel.add(buildHeaderRow, "growx, wmin 0, wrap, gapbottom 8");

        templateConfirmBar = new UiComponents.InlineConfirmBar();
        buildCardPanel.add(templateConfirmBar, "growx, wmin 0, wrap, gapbottom 8, hidemode 3");

        configureProgressBar = new JProgressBar();
        configureProgressBar.setVisible(false);
        configureStatusLabel = new JLabel("");
        configureStatusLabel.setFont(configureStatusLabel.getFont().deriveFont(Font.BOLD, 11.5f));
        configureStatusLabel.setVisible(false);

        JPanel configureProgressRow = new JPanel(
                new MigLayout("fillx, insets 0, gapx 10, hidemode 3", "[180:220:260][grow]", "[center]"));
        configureProgressRow.setOpaque(false);
        configureProgressRow.add(configureProgressBar, "growx, hidemode 3");
        configureProgressRow.add(configureStatusLabel, "growx, wmin 0, hidemode 3");
        buildCardPanel.add(configureProgressRow, "growx, wmin 0, wrap, gapbottom 8, hidemode 3");

        refreshDetectedTemplate();

        unconfirmedBanner = new UiComponents.WarningBanner(UNCONFIRMED_BUILD_NOTICE_TEXT);
        buildCardPanel.add(unconfirmedBanner, "growx, wmin 0, wrap, gapbottom 8, hidemode 3");

        textArea = new RSyntaxTextArea();
        UiComponents.configureSyntaxTextArea(textArea, SyntaxConstants.SYNTAX_STYLE_INI, true, false);
        SwingHtmlUtils.addChangeListener(textArea, this::onTomlTextEdited);
        updateTextAreaTheme();

        buildCardPanel.add(new RTextScrollPane(textArea), "grow, push");
        add(buildCardPanel, "grow, push, wmin 0");

        statusTimer = UiComponents.createStatusTimer(statusLabel, 3000);

        applySectionStyling();
        lastAsyncFuture = loadConfig();
        updateBannerState();
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
        updateBannerState();
    }

    void configureWithRoxy() {
        if (buildToolService == null || (onConfigureWithRoxyCallback == null && aiService == null)) {
            UiComponents.showStatusMessage(statusLabel, statusTimer, "Roxy AI service is not available.", false);
            return;
        }
        if (onConfigureWithRoxyCallback != null) {
            templateConfirmBar.cancel();
            performConfigureWithRoxy();
            return;
        }
        if (textEditedByUser) {
            templateConfirmBar.prompt("Replace unsaved build.toml edits with Roxy's auto-generated configuration?",
                    "Confirm", false, this::performConfigureWithRoxy);
            return;
        }
        templateConfirmBar.cancel();
        performConfigureWithRoxy();
    }

    CompletableFuture<String> performConfigureWithRoxy() {
        refreshDetectedTemplate();
        final String templateId = detectedTemplateId;
        final String currentEditorText = textArea.getText();

        if (onConfigureWithRoxyCallback != null) {
            final Consumer<String> callback = onConfigureWithRoxyCallback;
            CompletableFuture<String> future = Edt.supplyAsync(
                    () -> buildToolService.buildInteractiveConfigurationPrompt(templateId, currentEditorText),
                    callback::accept, ex -> {
                        Throwable cause = ExceptionUtils.getRootCause(ex);
                        log.error("Failed to prepare interactive build.toml prompt: {}", cause.getMessage());
                        UiComponents.showStatusMessage(statusLabel, statusTimer,
                                "Roxy configuration failed: " + cause.getMessage(), false);
                    });
            lastAsyncFuture = future;
            return future;
        }

        configureWithRoxyButton.setEnabled(false);
        configureWithRoxyButton.setText(CONFIGURING_WITH_ROXY_TEXT);
        configureProgressBar.setIndeterminate(true);
        configureProgressBar.setVisible(true);
        configureStatusLabel.setForeground(SwingHtmlUtils.getAccentColor());
        configureStatusLabel.setText(CONFIGURING_WITH_ROXY_STATUS);
        configureStatusLabel.setVisible(true);
        statusTimer.stop();
        statusLabel.setForeground(SwingHtmlUtils.getAccentColor());
        statusLabel.setText(CONFIGURING_WITH_ROXY_STATUS);
        statusLabel.setToolTipText(CONFIGURING_WITH_ROXY_STATUS);

        CompletableFuture<String> future = Edt.supplyAsync(() -> {
            try {
                String context = buildToolService.collectBuildConfigurationContext(templateId, currentEditorText);
                String rawResponse = aiService.generateBuildToml(context);
                return buildToolService.extractAndValidateGeneratedToml(rawResponse);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, generatedToml -> {
            restoreConfigureWithRoxyButtonState();
            setEditorContentProgrammatically(generatedToml, false);
            UiComponents.showStatusMessage(statusLabel, statusTimer,
                    "Roxy configured build.toml! Review and click 'Confirm & Save Settings'.", true);
        }, ex -> {
            restoreConfigureWithRoxyButtonState();
            Throwable cause = ExceptionUtils.getRootCause(ex);
            log.error("Failed to configure build.toml with Roxy: {}", cause.getMessage());
            UiComponents.showStatusMessage(statusLabel, statusTimer, "Roxy configuration failed: " + cause.getMessage(),
                    false);
        });
        lastAsyncFuture = future;
        return future;
    }

    private void restoreConfigureWithRoxyButtonState() {
        configureWithRoxyButton.setEnabled(true);
        configureWithRoxyButton.setText(CONFIGURE_WITH_ROXY_TEXT);
        configureProgressBar.setIndeterminate(false);
        configureProgressBar.setVisible(false);
        configureStatusLabel.setText("");
        configureStatusLabel.setVisible(false);
    }

    void loadModelOptions() {
        String globalModel = SettingsService.resolveGlobalGeminiModel(settingsService);
        GeminiModelConfig defaultOption = new GeminiModelConfig("", "Use Global Default (" + globalModel + ")",
                "Inherits the default Gemini model configured in Global Settings (" + globalModel + ").");
        DefaultComboBoxModel<GeminiModelConfig> comboModel = new DefaultComboBoxModel<>();
        comboModel.addElement(defaultOption);

        List<GeminiModelConfig> available = null;
        if (settingsService != null) {
            available = settingsService.getAvailableModels();
        }
        if (available != null) {
            for (GeminiModelConfig cfg : available) {
                comboModel.addElement(cfg);
            }
        }
        String projectModel = "";
        if (projectService != null && projectService.hasActiveProject()) {
            projectModel = StringUtils.defaultString(projectService.getProjectGeminiModel());
        }
        final String targetModel = projectModel;
        textUpdateGuard.run(() -> {
            modelCombo.setModel(comboModel);
            UiComponents.selectModelByApiName(modelCombo, targetModel);
            savedModelApiName = resolveSelectedModelApiName();
        });
    }

    void loadThinkingLevelOptions() {
        ThinkingLevel globalLevel = SettingsService.resolveGlobalThinkingLevel(settingsService);
        DefaultComboBoxModel<ThinkingLevelOption> comboModel = new DefaultComboBoxModel<>();
        comboModel.addElement(ThinkingLevelOption.useGlobalDefault(globalLevel));
        for (ThinkingLevel level : ThinkingLevel.values()) {
            comboModel.addElement(ThinkingLevelOption.fromThinkingLevel(level));
        }

        String projectThinking = "";
        if (projectService != null && projectService.hasActiveProject()) {
            projectThinking = StringUtils.defaultString(projectService.getProjectThinkingLevel());
        }
        final String targetThinking = projectThinking;
        textUpdateGuard.run(() -> {
            thinkingLevelCombo.setModel(comboModel);
            UiComponents.selectThinkingLevelOptionById(thinkingLevelCombo, targetThinking);
            savedThinkingLevelId = resolveSelectedThinkingLevelId();
        });
    }

    void updateSelectedModelDetails() {
        UiComponents.updateModelSelectionDetails(modelCombo, modelApiBadgeLabel, modelDescriptionArea,
                SettingsService.resolveGlobalGeminiModel(settingsService));
    }

    private String resolveSelectedModelApiName() {
        if (modelCombo == null) {
            return "";
        }
        GeminiModelConfig selectedModel = (GeminiModelConfig) modelCombo.getSelectedItem();
        if (selectedModel != null && selectedModel.apiName() != null) {
            return selectedModel.apiName();
        }
        return "";
    }

    private String resolveSelectedThinkingLevelId() {
        if (thinkingLevelCombo == null) {
            return "";
        }
        ThinkingLevelOption selectedThinking = (ThinkingLevelOption) thinkingLevelCombo.getSelectedItem();
        if (selectedThinking != null && selectedThinking.id() != null) {
            return selectedThinking.id();
        }
        return "";
    }

    boolean hasUnsavedChanges() {
        String currentText = "";
        if (textArea != null) {
            currentText = StringUtils.defaultString(textArea.getText());
        }
        if (textEditedByUser || !Objects.equals(currentText, savedTomlContent)) {
            return true;
        }
        if (!Objects.equals(resolveSelectedModelApiName(), savedModelApiName)) {
            return true;
        }
        return !Objects.equals(resolveSelectedThinkingLevelId(), savedThinkingLevelId);
    }

    private void onComboSelectionChanged() {
        if (!textUpdateGuard.isActive()) {
            cancelSaveFeedback();
            updateSaveButtonState();
        }
    }

    private void cancelSaveFeedback() {
        if (saveFeedbackTimer != null && saveFeedbackTimer.isRunning()) {
            saveFeedbackTimer.stop();
        }
        saveFeedbackActive = false;
    }

    void updateSaveButtonState() {
        if (saveButton == null) {
            return;
        }
        if (saveFeedbackActive) {
            applySaveButtonPresentation(SAVED_FEEDBACK_BUTTON_TEXT, Codicons.CHECK,
                    SAVE_BUTTON_BASE_STYLE + UiComponents.GREEN_BUTTON_STYLE);
            return;
        }
        boolean confirmed = projectService != null && projectService.hasActiveProject()
                && projectService.isBuildConfirmed();
        if (confirmed && !hasUnsavedChanges()) {
            applySaveButtonPresentation(CONFIRMED_BUTTON_TEXT, Codicons.CHECK,
                    SAVE_BUTTON_BASE_STYLE + UiComponents.SUBTLE_GREEN_BUTTON_STYLE);
        } else {
            applySaveButtonPresentation(SAVE_BUTTON_TEXT, Codicons.SAVE,
                    SAVE_BUTTON_BASE_STYLE + UiComponents.ACCENT_BUTTON_STYLE);
        }
    }

    private void applySaveButtonPresentation(String text, Codicons icon, String style) {
        saveButton.setText(text);
        saveButton.putClientProperty(FlatClientProperties.STYLE, style);
        saveButton.setIcon(FontIcon.of(icon, 16, UiComponents.resolveActionButtonIconColor(style)));
    }

    private void applySectionStyling() {
        Color accentColor = SwingHtmlUtils.getAccentColor();

        UiComponents.styleCardPanel(modelCardPanel);
        UiComponents.styleCardPanel(buildCardPanel);
        UiComponents.styleCodePillBadge(modelApiBadgeLabel);
        modelSectionHeaderLabel.setIcon(FontIcon.of(Codicons.HUBOT, 18, accentColor));
        buildSectionHeaderLabel.setIcon(FontIcon.of(Codicons.PACKAGE, 18, accentColor));
        UiComponents.updateInfoIconTheme(buildConfigInfoIcon);
        UiComponents.refreshActionButtonIcons(this);
        updateSaveButtonState();

        statusLabel.setForeground(SwingHtmlUtils.getUiColor(new Color(0x2e, 0x7d, 0x32), "Actions.Green"));
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        Edt.run(() -> {
            updateTextAreaTheme();
            applySectionStyling();
            loadModelOptions();
            loadThinkingLevelOptions();
            updateSelectedModelDetails();
            updateBannerState();
        });
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        Edt.run(() -> {
            cancelSaveFeedback();
            loadModelOptions();
            loadThinkingLevelOptions();
            updateSelectedModelDetails();
            refreshDetectedTemplate();
            loadConfig();
            updateBannerState();
        });
    }

    private void onTomlTextEdited() {
        if (!textUpdateGuard.isActive()) {
            String currentText = StringUtils.defaultString(textArea.getText());
            textEditedByUser = !Objects.equals(currentText, savedTomlContent);
            cancelSaveFeedback();
            templateConfirmBar.cancel();
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
        templateConfirmBar.cancel();
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
        templateConfirmBar.cancel();
        cancelSaveFeedback();
        if (buildToolService == null && projectService == null) {
            CompletableFuture<Void> done = CompletableFuture.completedFuture(null);
            lastAsyncFuture = done;
            return done;
        }
        String contentToSave = StringUtils.defaultString(textArea.getText());
        final String modelOverrideToSave = resolveSelectedModelApiName();
        final String thinkingOverrideToSave = resolveSelectedThinkingLevelId();
        final boolean wasBuildConfirmed = projectService != null && projectService.isBuildConfirmed();
        saveButton.setEnabled(false);
        CompletableFuture<Void> future = Edt.runAsync(() -> {
            try {
                if (buildToolService != null) {
                    buildToolService.saveConfigContent(contentToSave);
                }
                if (projectService != null && projectService.hasActiveProject()) {
                    projectService.setProjectGeminiModel(modelOverrideToSave);
                    projectService.setProjectThinkingLevel(thinkingOverrideToSave);
                    projectService.setBuildConfirmed(true);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, () -> {
            saveButton.setEnabled(true);
            savedTomlContent = contentToSave;
            savedModelApiName = modelOverrideToSave;
            savedThinkingLevelId = thinkingOverrideToSave;
            textEditedByUser = false;
            saveFeedbackActive = true;
            saveFeedbackTimer.restart();
            updateBannerState();
            UiComponents.showStatusMessage(statusLabel, statusTimer, "Configuration saved successfully!", true);
            if (!wasBuildConfirmed && onConfirmSavedCallback != null) {
                onConfirmSavedCallback.run();
            }
        }, ex -> {
            saveButton.setEnabled(true);
            updateSaveButtonState();
            Throwable cause = ExceptionUtils.getRootCause(ex);
            log.error("Failed to save build config: {}", cause.getMessage());
            UiComponents.showStatusMessage(statusLabel, statusTimer, "Error saving config: " + cause.getMessage(),
                    false);
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
        setEditorContentProgrammatically(toml, false);
        UiComponents.showStatusMessage(statusLabel, statusTimer, APPLIED_FROM_ASK_ROXY_STATUS, true);
    }

    private void updateTextAreaTheme() {
        SwingHtmlUtils.applyRSyntaxTheme(textArea, settingsService);
    }
}
