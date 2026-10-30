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
import org.roxycode.app.ai.services.BuildContainerService;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.events.SystemToolsUpdatedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.GeminiModelConfig;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.service.SystemToolService;
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
    static final String RECHECK_DOCKER_TEXT = "Re-check Docker";
    static final String CHECKING_DOCKER_TEXT = "Checking...";
    static final String DOCKER_MISSING_WARNING_TEXT = "Docker was not detected in System Tools. Running builds in Testcontainers is unavailable — build and test commands will execute directly on your host machine and can potentially modify your system.";
    static final String CONTAINER_ACTIVE_TEXT = "Testcontainers Isolation Active — Builds run inside an isolated Docker container.";
    static final String HOST_EXECUTION_ACTIVE_TEXT = "Host Execution Active — Container isolation is available via Docker ([container] enabled = true). Builds currently run on your host machine.";

    private final transient GenericBuildToolService buildToolService;
    private final transient SettingsService settingsService;
    private final transient SystemToolService systemToolService;
    private final transient ProjectService projectService;
    final JLabel titleLabel;
    final JLabel subtitleLabel;
    final JPanel modelCardPanel;
    final JLabel modelSectionHeaderLabel;
    final JComboBox<GeminiModelConfig> modelCombo;
    final JLabel modelApiBadgeLabel;
    final JTextArea modelDescriptionArea;
    final JLabel buildSectionHeaderLabel;
    final JPanel bannerPanel;
    final JLabel bannerIconLabel;
    final JLabel bannerMessageLabel;
    final JCheckBox containerToggleCheckBox;
    final JButton recheckDockerButton;
    final RSyntaxTextArea textArea;
    final JButton saveButton;
    final JLabel statusLabel;
    private final Timer statusTimer;

    boolean dockerAvailable = false;
    private boolean updatingTextProgrammatically = false;
    transient volatile CompletableFuture<String> loadConfigFuture = CompletableFuture.completedFuture("");
    transient volatile CompletableFuture<Void> saveConfigFuture = CompletableFuture.completedFuture(null);
    private transient Consumer<String> errorDialogPresenter;

    public BuildConfigPanel(GenericBuildToolService buildToolService, SettingsService settingsService,
            SystemToolService systemToolService) {
        this(buildToolService, settingsService, systemToolService, null);
    }

    @Autowired
    public BuildConfigPanel(GenericBuildToolService buildToolService, SettingsService settingsService,
            SystemToolService systemToolService, @Nullable ProjectService projectService) {
        this.buildToolService = buildToolService;
        this.settingsService = settingsService;
        this.systemToolService = systemToolService;
        this.projectService = projectService;
        setLayout(new MigLayout("fill, insets 20, hidemode 3", "[grow, fill]", "[][][][][grow][]"));
        setOpaque(true);

        titleLabel = new JLabel(TITLE_TEXT);
        subtitleLabel = new JLabel(SUBTITLE_TEXT);
        add(UiComponents.createPageHeader(titleLabel, subtitleLabel, null), "growx, wmin 0, wrap, gapbottom 10");

        modelCardPanel = new JPanel(new MigLayout("fillx, wrap 1, insets 14 18 14 18, gapy 8", "[grow, fill]", ""));
        UiComponents.styleCardPanel(modelCardPanel);

        modelSectionHeaderLabel = new JLabel(SECTION_AI_MODEL_TITLE);
        modelSectionHeaderLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold +1");
        modelSectionHeaderLabel.setIconTextGap(8);
        modelCardPanel.add(modelSectionHeaderLabel, "growx, wmin 0");

        modelCombo = new JComboBox<>();
        modelApiBadgeLabel = new JLabel();
        UiComponents.styleCodePillBadge(modelApiBadgeLabel);

        modelDescriptionArea = UiComponents.createReadOnlyTextArea();

        JPanel modelControlBox = new JPanel(new MigLayout("insets 0, gapx 8", "[][]", "[center]"));
        modelControlBox.setOpaque(false);
        modelControlBox.add(modelApiBadgeLabel, "hidemode 3");
        modelControlBox.add(modelCombo, "w 240:280:340");

        modelCardPanel.add(UiComponents.createFieldRow(
                UiComponents.createFieldTextBlock("Project Gemini Model",
                        "Override the default Global Settings Gemini model specifically for the active project.", null),
                modelControlBox, ""), "growx, wmin 0");

        modelCardPanel.add(modelDescriptionArea, "growx, wmin 0, gaptop 2, hidemode 3");
        add(modelCardPanel, "growx, wrap, gapbottom 14");

        loadModelOptions();
        modelCombo.addActionListener(e -> updateSelectedModelDetails());
        updateSelectedModelDetails();

        buildSectionHeaderLabel = new JLabel(SECTION_BUILD_CONFIG_TITLE);
        buildSectionHeaderLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold +1");
        buildSectionHeaderLabel.setIconTextGap(8);
        add(buildSectionHeaderLabel, "growx, wrap, gapbottom 6");

        bannerPanel = new JPanel(new MigLayout("fillx, insets 10 14 10 14", "[][grow][][]", "[]"));
        bannerPanel.setOpaque(false);
        bannerIconLabel = new JLabel();
        bannerMessageLabel = new JLabel();
        containerToggleCheckBox = new JCheckBox("Run builds in Testcontainers (Docker)");
        containerToggleCheckBox.setOpaque(false);
        containerToggleCheckBox.addActionListener(e -> setContainerEnabledInToml(containerToggleCheckBox.isSelected()));

        recheckDockerButton = new JButton(RECHECK_DOCKER_TEXT);
        recheckDockerButton.addActionListener(e -> refreshDockerStatus());

        bannerPanel.add(bannerIconLabel, "gapright 8");
        bannerPanel.add(bannerMessageLabel, "growx, wmin 100");
        bannerPanel.add(containerToggleCheckBox, "gapleft 10");
        bannerPanel.add(recheckDockerButton, "gapleft 10");
        add(bannerPanel, "growx, wrap, gapbottom 10");

        textArea = new RSyntaxTextArea();
        textArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_INI);
        textArea.setCodeFoldingEnabled(true);
        textArea.setAntiAliasingEnabled(true);
        SwingHtmlUtils.addChangeListener(textArea, this::onTomlTextEdited);
        updateTextAreaTheme();

        add(new RTextScrollPane(textArea), "grow, wrap");

        JPanel buttonPanel = new JPanel(new MigLayout("insets 0", "[]10push[]"));
        buttonPanel.setOpaque(false);
        saveButton = new JButton("Save Changes");
        saveButton.addActionListener(e -> saveConfig());
        buttonPanel.add(saveButton);

        statusLabel = new JLabel("");
        buttonPanel.add(statusLabel);
        add(buttonPanel, "growx, gaptop 15");

        statusTimer = new Timer(3000, e -> statusLabel.setText(""));
        statusTimer.setRepeats(false);

        applySectionStyling();
        loadConfig();
        updateBannerState();
        loadInitialDockerStatus();
    }

    private String resolveGlobalDefaultModel() {
        if (settingsService != null && settingsService.getSettings() != null) {
            String model = settingsService.getSettings().getGeminiModel();
            if (model != null && !model.isBlank()) {
                return model.trim();
            }
        }
        return AppSettings.DEFAULT_GEMINI_MODEL;
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
            loadConfig();
            updateBannerState();
        });
    }

    @EventListener
    public void onSystemToolsUpdated(SystemToolsUpdatedEvent event) {
        if (event != null) {
            Edt.run(() -> setDockerAvailable(event.dockerAvailable()));
        }
    }

    private void loadInitialDockerStatus() {
        CompletableFuture<Boolean> future = null;
        if (systemToolService != null) {
            future = systemToolService.isDockerAvailable();
        }
        if (future == null) {
            setDockerAvailable(false);
            return;
        }
        future.thenAccept(available -> Edt.run(() -> setDockerAvailable(Boolean.TRUE.equals(available))));
    }

    public CompletableFuture<Boolean> refreshDockerStatus() {
        if (systemToolService == null) {
            setDockerAvailable(false);
            return CompletableFuture.completedFuture(false);
        }
        Edt.run(() -> {
            recheckDockerButton.setEnabled(false);
            recheckDockerButton.setText(CHECKING_DOCKER_TEXT);
        });
        CompletableFuture<Boolean> future = systemToolService.refreshDockerAvailable();
        if (future == null) {
            future = systemToolService.isDockerAvailable();
        }
        if (future == null) {
            future = CompletableFuture.completedFuture(false);
        }
        return future.whenComplete((available, ex) -> Edt.run(() -> {
            if (ex == null) {
                setDockerAvailable(Boolean.TRUE.equals(available));
            }
            recheckDockerButton.setText(RECHECK_DOCKER_TEXT);
            recheckDockerButton.setEnabled(true);
        })).thenApply(Boolean.TRUE::equals);
    }

    void setDockerAvailable(boolean available) {
        this.dockerAvailable = available;
        updateBannerState();
    }

    private void onTomlTextEdited() {
        if (!updatingTextProgrammatically) {
            updateBannerState();
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (modelCardPanel != null) {
            applySectionStyling();
        }
        if (bannerPanel != null) {
            updateBannerState();
        }
    }

    void updateBannerState() {
        String currentText = "";
        if (textArea != null) {
            currentText = textArea.getText();
        }
        boolean containerEnabled = BuildContainerService.isContainerEnabledInToml(currentText);
        containerToggleCheckBox.setSelected(containerEnabled);
        containerToggleCheckBox.setEnabled(dockerAvailable);

        if (!dockerAvailable) {
            applyTintedBanner(Codicons.WARNING, SwingHtmlUtils.getUiColor(new Color(0xd9, 0x77, 0x06),
                    "Component.warning.borderColor", "Actions.Yellow"), DOCKER_MISSING_WARNING_TEXT);
        } else if (containerEnabled) {
            applyTintedBanner(Codicons.SHIELD, SwingHtmlUtils.getUiColor(new Color(0x2e, 0x7d, 0x32), "Actions.Green"),
                    CONTAINER_ACTIVE_TEXT);
        } else {
            Color infoColor = SwingHtmlUtils.getAccentColor();
            Color borderColor = SwingHtmlUtils.getUiColor(new Color(0xcc, 0xcc, 0xcc), "Component.borderColor");
            Color panelBg = SwingHtmlUtils.getUiColor(new Color(0xf5, 0xf5, 0xf5), "Panel.background");
            applyBannerStyle(
                    Codicons.INFO, infoColor, "arc: 12; border: 1,1,1,1," + SwingHtmlUtils.toHex(borderColor)
                            + ",1,12; background: darken(" + SwingHtmlUtils.toHex(panelBg) + ", 3%)",
                    HOST_EXECUTION_ACTIVE_TEXT);
        }
        bannerPanel.revalidate();
        bannerPanel.repaint();
    }

    private void applyTintedBanner(Codicons icon, Color color, String message) {
        String hex = SwingHtmlUtils.toHex(color);
        String bgHex = SwingHtmlUtils.toHex(SwingHtmlUtils.getTintedBackground(color, 0.15));
        applyBannerStyle(icon, color, "arc: 12; border: 1,1,1,1," + hex + ",1,12; background: " + bgHex, message);
    }

    private void applyBannerStyle(Codicons icon, Color iconColor, String style, String message) {
        bannerIconLabel.setIcon(FontIcon.of(icon, 18, iconColor));
        bannerMessageLabel.setText(message);
        bannerPanel.putClientProperty(FlatClientProperties.STYLE, style);
    }

    @SuppressWarnings("PMD.UnusedAssignment")
    void setContainerEnabledInToml(boolean enabled) {
        String current = textArea.getText();
        String updated = BuildContainerService.updateContainerEnabledInToml(current, enabled);
        if (!updated.equals(current)) {
            updatingTextProgrammatically = true;
            try {
                int caret = textArea.getCaretPosition();
                textArea.setText(updated);
                textArea.setCaretPosition(Math.min(caret, updated.length()));
            } finally {
                updatingTextProgrammatically = false;
            }
        }
        updateBannerState();
    }

    @SuppressWarnings("PMD.UnusedAssignment")
    CompletableFuture<String> loadConfig() {
        if (buildToolService == null) {
            return CompletableFuture.completedFuture("");
        }
        CompletableFuture<String> completed = CompletableFuture.supplyAsync(() -> {
            try {
                String content = buildToolService.getConfigContent();
                if (content != null) {
                    return content;
                }
                return "";
            } catch (IOException e) {
                log.error("Failed to load build config", e);
                return "# Error loading config: " + e.getMessage();
            }
        }).whenComplete((content, ex) -> {
            if (ex == null) {
                Edt.run(() -> {
                    updatingTextProgrammatically = true;
                    try {
                        textArea.setText(content);
                        textArea.setCaretPosition(0);
                    } finally {
                        updatingTextProgrammatically = false;
                    }
                    updateBannerState();
                });
            }
        });
        this.loadConfigFuture = completed;
        return completed;
    }

    CompletableFuture<Void> saveConfig() {
        if (buildToolService == null && projectService == null) {
            return CompletableFuture.completedFuture(null);
        }
        String contentToSave = textArea.getText();
        GeminiModelConfig selectedModel = (GeminiModelConfig) modelCombo.getSelectedItem();
        String selectedModelApiName = "";
        if (selectedModel != null && selectedModel.apiName() != null) {
            selectedModelApiName = selectedModel.apiName();
        }
        String modelOverrideToSave = selectedModelApiName;
        Edt.run(() -> saveButton.setEnabled(false));
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                if (projectService != null && projectService.hasActiveProject()) {
                    projectService.setProjectGeminiModel(modelOverrideToSave);
                }
                if (buildToolService != null) {
                    buildToolService.saveConfigContent(contentToSave);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).whenComplete((unused, ex) -> Edt.run(() -> {
            saveButton.setEnabled(true);
            if (ex == null) {
                updateBannerState();
                statusLabel.setText("Configuration saved successfully!");
                statusTimer.restart();
            } else {
                Throwable cause = ExceptionUtils.getRootCause(ex);
                log.error("Failed to save build config: {}", cause.getMessage());
                showSaveErrorDialog(cause.getMessage());
            }
        }));
        this.saveConfigFuture = future;
        return future;
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

    JComboBox<GeminiModelConfig> getModelCombo() {
        return modelCombo;
    }

    JLabel getModelApiBadgeLabel() {
        return modelApiBadgeLabel;
    }

    JTextArea getModelDescriptionArea() {
        return modelDescriptionArea;
    }

    private void updateTextAreaTheme() {
        SwingHtmlUtils.applyRSyntaxTheme(textArea, settingsService);
    }
}
