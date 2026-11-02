package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
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
    static final String SUBTITLE_TEXT = "Configure project-specific Gemini model override and build execution commands (build.toml).";
    static final String SECTION_AI_MODEL_TITLE = "AI Model Override";
    static final String SECTION_BUILD_CONFIG_TITLE = "Build Configuration (build.toml)";
    static final String APPLY_TEMPLATE_TEXT = "Apply Template";
    static final String SAVE_BUTTON_TEXT = "Confirm & Save Settings";
    private static final String DEFAULT_TEMPLATE_ID = "default";
    static final String UNCONFIRMED_BUILD_NOTICE_TEXT = "Build configuration was initialized from the auto-detected template. Please review the commands below and click 'Confirm & Save Settings'.";

    private final transient GenericBuildToolService buildToolService;
    private final transient SettingsService settingsService;
    private final transient ProjectService projectService;
    final JLabel titleLabel;
    final JLabel subtitleLabel;
    final JPanel modelCardPanel;
    final JLabel modelSectionHeaderLabel;
    final JComboBox<GeminiModelConfig> modelCombo;
    final JLabel modelApiBadgeLabel;
    final JTextArea modelDescriptionArea;
    final JLabel buildSectionHeaderLabel;
    final JComboBox<BuildTemplateInfo> templateCombo;
    final JButton applyTemplateButton;
    final JPanel unconfirmedBannerPanel;
    final JLabel unconfirmedBannerIconLabel;
    final JLabel unconfirmedBannerMessageLabel;
    final RSyntaxTextArea textArea;
    final JButton saveButton;
    final JLabel statusLabel;
    private final Timer statusTimer;

    private boolean updatingTextProgrammatically = false;
    private boolean textEditedByUser = false;
    String detectedTemplateId = DEFAULT_TEMPLATE_ID;
    private transient Consumer<String> errorDialogPresenter;
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
        add(modelCardPanel, "growx, wrap, gapbottom 14");

        loadModelOptions();
        modelCombo.addActionListener(e -> updateSelectedModelDetails());
        updateSelectedModelDetails();

        buildSectionHeaderLabel = UiComponents.createSectionHeader(SECTION_BUILD_CONFIG_TITLE, Codicons.PACKAGE,
                "font: bold +1");
        add(buildSectionHeaderLabel, "growx, wrap, gapbottom 6");

        JPanel templateToolbar = new JPanel(new MigLayout("fillx, insets 0", "[][grow][]", "[]"));
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
        applyTemplateButton = new JButton(APPLY_TEMPLATE_TEXT);
        applyTemplateButton.addActionListener(e -> applySelectedTemplate());

        templateToolbar.add(templateLabel, "gapright 6");
        templateToolbar.add(templateCombo, "w 220:280:360, gapright 8");
        templateToolbar.add(applyTemplateButton);
        add(templateToolbar, "growx, wrap, gapbottom 8");

        loadTemplateOptions();

        unconfirmedBannerPanel = new JPanel(new MigLayout("fillx, insets 10 14 10 14", "[][grow]", "[]"));
        unconfirmedBannerPanel.setOpaque(false);
        unconfirmedBannerIconLabel = new JLabel();
        unconfirmedBannerMessageLabel = new JLabel(UNCONFIRMED_BUILD_NOTICE_TEXT);
        unconfirmedBannerPanel.add(unconfirmedBannerIconLabel, "gapright 8");
        unconfirmedBannerPanel.add(unconfirmedBannerMessageLabel, "growx, wmin 100");
        unconfirmedBannerPanel.setVisible(false);
        add(unconfirmedBannerPanel, "growx, wrap, gapbottom 8, hidemode 3");

        textArea = new RSyntaxTextArea();
        textArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_INI);
        textArea.setCodeFoldingEnabled(true);
        textArea.setAntiAliasingEnabled(true);
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

        statusTimer = new Timer(3000, e -> statusLabel.setText(""));
        statusTimer.setRepeats(false);

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
            detectedTemplateId = org.apache.commons.lang3.StringUtils.defaultIfBlank(buildToolService.detectTemplate(),
                    DEFAULT_TEMPLATE_ID);
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

    @SuppressWarnings("PMD.UnusedAssignment")
    void applySelectedTemplate() {
        BuildTemplateInfo selected = (BuildTemplateInfo) templateCombo.getSelectedItem();
        if (selected == null || buildToolService == null) {
            return;
        }
        if (textEditedByUser && !GraphicsEnvironment.isHeadless()) {
            int result = JOptionPane.showConfirmDialog(this,
                    "Replace current build.toml editor contents with the '" + selected.displayName() + "' template?",
                    "Apply Template", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (result != JOptionPane.YES_OPTION) {
                return;
            }
        }
        String content = buildToolService.loadTemplateContent(selected.id());
        if (content != null) {
            updatingTextProgrammatically = true;
            try {
                textArea.setText(content);
                textArea.setCaretPosition(0);
                textEditedByUser = false;
            } finally {
                updatingTextProgrammatically = false;
            }
            updateBannerState();
            statusLabel.setText("Applied '" + selected.displayName() + "' template.");
            statusTimer.restart();
        }
    }

    private String resolveGlobalDefaultModel() {
        return SettingsService.resolveGlobalGeminiModel(settingsService);
    }

    void loadModelOptions() {
        String globalModel = resolveGlobalDefaultModel();
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

    void updateSelectedModelDetails() {
        UiComponents.updateModelSelectionDetails(modelCombo, modelApiBadgeLabel, modelDescriptionArea,
                resolveGlobalDefaultModel());
    }

    private void applySectionStyling() {
        Color accentColor = SwingHtmlUtils.getAccentColor();

        modelSectionHeaderLabel.setIcon(FontIcon.of(Codicons.HUBOT, 18, accentColor));
        buildSectionHeaderLabel.setIcon(FontIcon.of(Codicons.PACKAGE, 18, accentColor));

        statusLabel.setForeground(SwingHtmlUtils.getUiColor(new Color(0x2e, 0x7d, 0x32), "Actions.Green"));
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        Edt.run(() -> {
            updateTextAreaTheme();
            applySectionStyling();
            loadModelOptions();
            updateSelectedModelDetails();
            updateBannerState();
        });
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        Edt.run(() -> {
            loadModelOptions();
            updateSelectedModelDetails();
            loadTemplateOptions();
            loadConfig();
            updateBannerState();
        });
    }

    private void onTomlTextEdited() {
        if (!updatingTextProgrammatically) {
            textEditedByUser = true;
            updateBannerState();
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (modelCardPanel != null) {
            applySectionStyling();
        }
        if (unconfirmedBannerPanel != null) {
            updateBannerState();
        }
    }

    void updateBannerState() {
        boolean showUnconfirmedNotice = projectService != null && projectService.hasActiveProject()
                && !projectService.isBuildConfirmed();
        if (unconfirmedBannerPanel != null) {
            if (showUnconfirmedNotice) {
                Color warnColor = SwingHtmlUtils.getUiColor(new Color(0xd9, 0x77, 0x06),
                        "Component.warning.borderColor", "Actions.Yellow");
                String warnHex = SwingHtmlUtils.toHex(warnColor);
                String warnBgHex = SwingHtmlUtils.toHex(SwingHtmlUtils.getTintedBackground(warnColor, 0.15));
                unconfirmedBannerIconLabel.setIcon(FontIcon.of(Codicons.WARNING, 18, warnColor));
                unconfirmedBannerMessageLabel.setText(UNCONFIRMED_BUILD_NOTICE_TEXT);
                unconfirmedBannerPanel.putClientProperty(FlatClientProperties.STYLE,
                        "arc: 12; border: 1,1,1,1," + warnHex + ",1,12; background: " + warnBgHex);
            }
            unconfirmedBannerPanel.setVisible(showUnconfirmedNotice);
            unconfirmedBannerPanel.revalidate();
            unconfirmedBannerPanel.repaint();
        }
    }

    @SuppressWarnings("PMD.UnusedAssignment")
    CompletableFuture<String> loadConfig() {
        if (buildToolService == null) {
            CompletableFuture<String> done = CompletableFuture.completedFuture("");
            lastAsyncFuture = done;
            return done;
        }
        CompletableFuture<String> future = Edt.supplyAsync(() -> {
            try {
                return org.apache.commons.lang3.StringUtils.defaultString(buildToolService.getConfigContent());
            } catch (IOException e) {
                log.error("Failed to load build config", e);
                return "# Error loading config: " + e.getMessage();
            }
        }, content -> {
            updatingTextProgrammatically = true;
            try {
                textArea.setText(content);
                textArea.setCaretPosition(0);
                textEditedByUser = false;
            } finally {
                updatingTextProgrammatically = false;
            }
            updateBannerState();
        });
        lastAsyncFuture = future;
        return future;
    }

    CompletableFuture<Void> saveConfig() {
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
        saveButton.setEnabled(false);
        CompletableFuture<Void> future = Edt.runAsync(() -> {
            try {
                if (projectService != null && projectService.hasActiveProject()) {
                    projectService.setProjectGeminiModel(modelOverrideToSave);
                    projectService.setBuildConfirmed(true);
                }
                if (buildToolService != null) {
                    buildToolService.saveConfigContent(contentToSave);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, () -> {
            saveButton.setEnabled(true);
            textEditedByUser = false;
            updateBannerState();
            if (onConfirmSavedCallback != null) {
                onConfirmSavedCallback.run();
            }
            statusLabel.setText("Configuration saved successfully!");
            statusTimer.restart();
        }, ex -> {
            saveButton.setEnabled(true);
            Throwable cause = ExceptionUtils.getRootCause(ex);
            log.error("Failed to save build config: {}", cause.getMessage());
            showSaveErrorDialog(cause.getMessage());
        });
        lastAsyncFuture = future;
        return future;
    }

    void setOnConfirmSavedCallback(Runnable callback) {
        this.onConfirmSavedCallback = callback;
    }

    private void showSaveErrorDialog(String message) {
        String fullMsg = "Error saving config: " + message;
        if (errorDialogPresenter != null) {
            errorDialogPresenter.accept(fullMsg);
        } else if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(this, fullMsg, "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    void setErrorDialogPresenter(Consumer<String> errorDialogPresenter) {
        this.errorDialogPresenter = errorDialogPresenter;
    }

    private void updateTextAreaTheme() {
        SwingHtmlUtils.applyRSyntaxTheme(textArea, settingsService);
    }
}
