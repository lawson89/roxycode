package org.roxycode.app.ui;

import java.awt.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.ai.services.GenericBuildToolService.BuildTemplateInfo;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.GeminiModelConfig;
import org.roxycode.app.model.ThinkingLevel;
import org.roxycode.app.model.ThinkingLevelOption;
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
public final class BuildConfigPanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(BuildConfigPanel.class);
    static final String TITLE_TEXT = "Project Settings";
    static final String SUBTITLE_TEXT = "Configure project-specific Gemini model override and build execution commands (build.toml). Need help customizing build.toml or regexes for your stack? Ask Roxy in Codebase Q&A!";
    static final String SECTION_AI_MODEL_TITLE = "AI Model Override";
    static final String SECTION_BUILD_CONFIG_TITLE = "Build Configuration (build.toml)";
    static final String APPLY_TEMPLATE_TEXT = "Apply Template";
    static final String SAVE_BUTTON_TEXT = "Confirm & Save Settings";
    private static final String DEFAULT_TEMPLATE_ID = "default";
    static final String UNCONFIRMED_BUILD_NOTICE_TEXT = "Build configuration was initialized from the auto-detected template. Please review the commands below and click 'Confirm & Save Settings'. Need help customizing build.toml or regexes for your stack? Ask Roxy in Codebase Q&A!";
    static final String BUILD_TOML_GUIDE_TOOLTIP_HTML = """
            <html><div style='width: 390px;'>
            <b>build.toml Quick Reference</b><br><br>
            &bull; <b><code>[commands]</code></b>: Shell commands for <code>install</code>, <code>clean</code>, <code>compile</code>, <code>build</code>, <code>test_all</code>, <code>test_single</code> (uses <code>{testName}</code> placeholder), <code>lint</code>, and <code>format</code>.<br>
            &bull; <b><code>[env]</code></b>: Key-value environment variables passed to all build processes.<br>
            &bull; <b><code>[parse]</code></b>: <code>error_regex</code>, <code>test_failure_regex</code>, <code>context_lines</code>, and optional <code>junit_xml_paths</code> for structured failure extraction.<br>
            &bull; <b><code>[sandbox]</code></b>: <code>mode</code> (<code>"none"</code>, <code>"native"</code> for Linux <code>bwrap</code> / macOS <code>sandbox-exec</code>, or <code>"docker"</code>), <code>writable_paths</code>, <code>image</code>, and <code>workdir</code>.<br><br>
            <i>Need help customizing build.toml or regexes for your stack? Ask Roxy in <b>Codebase Q&amp;A</b>!</i>
            </div></html>""";

    private final transient GenericBuildToolService buildToolService;
    private final transient SettingsService settingsService;
    private final transient ProjectService projectService;
    final JLabel titleLabel;
    final JLabel subtitleLabel;
    final JPanel modelCardPanel;
    final JLabel modelSectionHeaderLabel;
    final JComboBox<GeminiModelConfig> modelCombo;
    final JComboBox<ThinkingLevelOption> thinkingLevelCombo;
    final JLabel modelApiBadgeLabel;
    final JTextArea modelDescriptionArea;
    final JLabel buildSectionHeaderLabel;
    final JLabel buildConfigInfoIcon;
    final JComboBox<BuildTemplateInfo> templateCombo;
    final JButton applyTemplateButton;
    final UiComponents.InlineConfirmBar templateConfirmBar;
    final UiComponents.WarningBanner unconfirmedBanner;
    final RSyntaxTextArea textArea;
    final JButton saveButton;
    final JLabel statusLabel;
    private final Timer statusTimer;

    private final transient UiComponents.UiGuard textUpdateGuard = new UiComponents.UiGuard();
    private boolean textEditedByUser = false;
    String detectedTemplateId = DEFAULT_TEMPLATE_ID;
    private transient Runnable onConfirmSavedCallback;
    transient CompletableFuture<?> lastAsyncFuture = CompletableFuture.completedFuture(null);

    @Autowired
    public BuildConfigPanel(GenericBuildToolService buildToolService, SettingsService settingsService,
            @Nullable ProjectService projectService) {
        this.buildToolService = buildToolService;
        this.settingsService = settingsService;
        this.projectService = projectService;
        setLayout(new MigLayout("fill, insets 20, hidemode 3", "[grow, fill]", "[][][][][][grow][]"));
        setOpaque(true);

        titleLabel = new JLabel(TITLE_TEXT);
        subtitleLabel = new JLabel(SUBTITLE_TEXT);
        add(UiComponents.createPageHeader(titleLabel, subtitleLabel, null), "growx, wmin 0, wrap, gapbottom 10");

        modelCardPanel = new JPanel(new MigLayout("fillx, wrap 1, insets 14 18 14 18, gapy 8", "[grow, fill]", ""));
        UiComponents.styleCardPanel(modelCardPanel);

        modelSectionHeaderLabel = UiComponents.createSectionHeader(SECTION_AI_MODEL_TITLE, Codicons.HUBOT,
                "font: bold +1");
        modelCardPanel.add(modelSectionHeaderLabel, "growx, wmin 0");

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

        add(modelCardPanel, "growx, wrap, gapbottom 14");

        loadModelOptions();
        loadThinkingLevelOptions();
        modelCombo.addActionListener(e -> updateSelectedModelDetails());
        updateSelectedModelDetails();

        buildSectionHeaderLabel = UiComponents.createSectionHeader(SECTION_BUILD_CONFIG_TITLE, Codicons.PACKAGE,
                "font: bold +1");
        buildConfigInfoIcon = UiComponents.createInfoIcon(BUILD_TOML_GUIDE_TOOLTIP_HTML);
        JPanel buildHeaderRow = new JPanel(new MigLayout("insets 0, gapx 8", "[][][grow]", "[center]"));
        buildHeaderRow.setOpaque(false);
        buildHeaderRow.add(buildSectionHeaderLabel);
        buildHeaderRow.add(buildConfigInfoIcon);
        add(buildHeaderRow, "growx, wrap, gapbottom 6");

        JPanel templateToolbar = new JPanel(new MigLayout("fillx, insets 0, hidemode 3", "[][grow][][]", "[]"));
        templateToolbar.setOpaque(false);
        JLabel templateLabel = new JLabel("Build Template:");
        templateCombo = new JComboBox<>();
        templateCombo.setRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof BuildTemplateInfo info) {
                    setText(formatTemplateDisplayText(info));
                }
                return this;
            }
        });
        templateConfirmBar = new UiComponents.InlineConfirmBar();
        templateCombo.addActionListener(e -> templateConfirmBar.cancel());
        applyTemplateButton = new JButton(APPLY_TEMPLATE_TEXT);
        applyTemplateButton.addActionListener(e -> applySelectedTemplate());

        templateToolbar.add(templateLabel, "gapright 6");
        templateToolbar.add(templateCombo, "w 220:280:360, gapright 8");
        templateToolbar.add(applyTemplateButton, "gapright 8");
        templateToolbar.add(templateConfirmBar);
        add(templateToolbar, "growx, wrap, gapbottom 8");

        loadTemplateOptions();

        unconfirmedBanner = new UiComponents.WarningBanner(UNCONFIRMED_BUILD_NOTICE_TEXT);
        add(unconfirmedBanner, "growx, wrap, gapbottom 8, hidemode 3");

        textArea = new RSyntaxTextArea();
        UiComponents.configureSyntaxTextArea(textArea, SyntaxConstants.SYNTAX_STYLE_INI, true, false);
        SwingHtmlUtils.addChangeListener(textArea, this::onTomlTextEdited);
        updateTextAreaTheme();

        add(new RTextScrollPane(textArea), "grow, wrap");

        JPanel buttonPanel = new JPanel(new MigLayout("insets 0", "[]10push[]"));
        buttonPanel.setOpaque(false);
        saveButton = new JButton(SAVE_BUTTON_TEXT);
        saveButton.addActionListener(e -> saveConfig());
        buttonPanel.add(saveButton);

        statusLabel = new JLabel("");
        buttonPanel.add(statusLabel);
        add(buttonPanel, "growx, gaptop 15");

        statusTimer = UiComponents.createStatusTimer(statusLabel, 3000);

        applySectionStyling();
        lastAsyncFuture = loadConfig();
        updateBannerState();
    }

    String formatTemplateDisplayText(BuildTemplateInfo info) {
        if (info == null) {
            return "";
        }
        if (info.id().equals(detectedTemplateId) && !DEFAULT_TEMPLATE_ID.equals(info.id())) {
            return info.displayName() + " (auto-detected)";
        }
        return info.displayName();
    }

    void loadTemplateOptions() {
        DefaultComboBoxModel<BuildTemplateInfo> comboModel = new DefaultComboBoxModel<>();
        List<BuildTemplateInfo> templates = GenericBuildToolService.AVAILABLE_TEMPLATES;
        if (buildToolService != null) {
            List<BuildTemplateInfo> fromService = buildToolService.getAvailableTemplates();
            if (fromService != null && !fromService.isEmpty()) {
                templates = fromService;
            }
            detectedTemplateId = StringUtils.defaultIfBlank(buildToolService.detectTemplate(), DEFAULT_TEMPLATE_ID);
        } else {
            detectedTemplateId = DEFAULT_TEMPLATE_ID;
        }
        BuildTemplateInfo toSelect = null;
        for (BuildTemplateInfo info : templates) {
            comboModel.addElement(info);
            if (info.id().equals(detectedTemplateId)) {
                toSelect = info;
            }
        }
        templateCombo.setModel(comboModel);
        if (toSelect != null) {
            templateCombo.setSelectedItem(toSelect);
        } else if (comboModel.getSize() > 0) {
            templateCombo.setSelectedIndex(0);
        }
    }

    void applySelectedTemplate() {
        BuildTemplateInfo selected = (BuildTemplateInfo) templateCombo.getSelectedItem();
        if (selected == null || buildToolService == null) {
            return;
        }
        if (textEditedByUser) {
            templateConfirmBar.prompt("Replace unsaved build.toml edits with '" + selected.displayName() + "'?",
                    "Confirm", false, () -> performApplyTemplate(selected));
            return;
        }
        templateConfirmBar.cancel();
        performApplyTemplate(selected);
    }

    private void setEditorContentProgrammatically(String content) {
        textUpdateGuard.run(() -> {
            textArea.setText(content);
            textArea.setCaretPosition(0);
            textEditedByUser = false;
        });
        updateBannerState();
    }

    private void performApplyTemplate(BuildTemplateInfo selected) {
        String content = buildToolService.loadTemplateContent(selected.id());
        if (content != null) {
            setEditorContentProgrammatically(content);
            UiComponents.showStatusMessage(statusLabel, statusTimer,
                    "Applied '" + selected.displayName() + "' template.", true);
        }
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
        modelCombo.setModel(comboModel);

        String projectModel = "";
        if (projectService != null && projectService.hasActiveProject()) {
            projectModel = projectService.getProjectGeminiModel();
        }
        UiComponents.selectModelByApiName(modelCombo, projectModel);
    }

    void loadThinkingLevelOptions() {
        ThinkingLevel globalLevel = SettingsService.resolveGlobalThinkingLevel(settingsService);
        DefaultComboBoxModel<ThinkingLevelOption> comboModel = new DefaultComboBoxModel<>();
        comboModel.addElement(ThinkingLevelOption.useGlobalDefault(globalLevel));
        for (ThinkingLevel level : ThinkingLevel.values()) {
            comboModel.addElement(ThinkingLevelOption.fromThinkingLevel(level));
        }
        thinkingLevelCombo.setModel(comboModel);

        String projectThinking = "";
        if (projectService != null && projectService.hasActiveProject()) {
            projectThinking = projectService.getProjectThinkingLevel();
        }
        UiComponents.selectThinkingLevelOptionById(thinkingLevelCombo, projectThinking);
    }

    void updateSelectedModelDetails() {
        UiComponents.updateModelSelectionDetails(modelCombo, modelApiBadgeLabel, modelDescriptionArea,
                SettingsService.resolveGlobalGeminiModel(settingsService));
    }

    private void applySectionStyling() {
        Color accentColor = SwingHtmlUtils.getAccentColor();

        modelSectionHeaderLabel.setIcon(FontIcon.of(Codicons.HUBOT, 18, accentColor));
        buildSectionHeaderLabel.setIcon(FontIcon.of(Codicons.PACKAGE, 18, accentColor));
        UiComponents.updateInfoIconTheme(buildConfigInfoIcon);

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
            loadModelOptions();
            loadThinkingLevelOptions();
            updateSelectedModelDetails();
            loadTemplateOptions();
            loadConfig();
            updateBannerState();
        });
    }

    private void onTomlTextEdited() {
        if (!textUpdateGuard.isActive()) {
            textEditedByUser = true;
            templateConfirmBar.cancel();
            updateBannerState();
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (modelCardPanel != null) {
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
        }, this::setEditorContentProgrammatically);
        lastAsyncFuture = future;
        return future;
    }

    CompletableFuture<Void> saveConfig() {
        templateConfirmBar.cancel();
        if (buildToolService == null && projectService == null) {
            CompletableFuture<Void> done = CompletableFuture.completedFuture(null);
            lastAsyncFuture = done;
            return done;
        }
        String contentToSave = textArea.getText();
        GeminiModelConfig selectedModel = (GeminiModelConfig) modelCombo.getSelectedItem();
        String selectedModelApiName = "";
        if (selectedModel != null && selectedModel.apiName() != null) {
            selectedModelApiName = selectedModel.apiName();
        }
        final String modelOverrideToSave = selectedModelApiName;
        ThinkingLevelOption selectedThinking = (ThinkingLevelOption) thinkingLevelCombo.getSelectedItem();
        String selectedThinkingId = "";
        if (selectedThinking != null && selectedThinking.id() != null) {
            selectedThinkingId = selectedThinking.id();
        }
        final String thinkingOverrideToSave = selectedThinkingId;
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
            textEditedByUser = false;
            updateBannerState();
            UiComponents.showStatusMessage(statusLabel, statusTimer, "Configuration saved successfully!", true);
            if (!wasBuildConfirmed && onConfirmSavedCallback != null) {
                onConfirmSavedCallback.run();
            }
        }, ex -> {
            saveButton.setEnabled(true);
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

    private void updateTextAreaTheme() {
        SwingHtmlUtils.applyRSyntaxTheme(textArea, settingsService);
    }
}
