package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.service.SystemToolService;
import org.roxycode.app.util.Edt;
import org.roxycode.app.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Lazy
public final class BuildConfigPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(BuildConfigPanel.class);
    static final String RECHECK_DOCKER_TEXT = "Re-check Docker";
    static final String CHECKING_DOCKER_TEXT = "Checking...";
    static final String DOCKER_MISSING_WARNING_TEXT =
            "Docker was not detected in System Tools. Running builds in Testcontainers is unavailable — build and test commands will execute directly on your host machine and can potentially modify your system.";
    static final String CONTAINER_ACTIVE_TEXT =
            "Testcontainers Isolation Active — Builds run inside an isolated Docker container.";
    static final String HOST_EXECUTION_ACTIVE_TEXT =
            "Host Execution Active — Container isolation is available via Docker ([container] enabled = true). Builds currently run on your host machine.";

    private final transient GenericBuildToolService buildToolService;
    private final transient SettingsService settingsService;
    private final transient SystemToolService systemToolService;
    private final JPanel bannerPanel;
    private final JLabel bannerIconLabel;
    private final JLabel bannerMessageLabel;
    private final JCheckBox containerToggleCheckBox;
    private final JButton recheckDockerButton;
    private final RSyntaxTextArea textArea;
    private final JButton saveButton;
    private final JLabel statusLabel;
    private final Timer statusTimer;

    private boolean dockerAvailable = false;
    private boolean updatingTextProgrammatically = false;
    private transient volatile CompletableFuture<String> loadConfigFuture =
            CompletableFuture.completedFuture("");
    private transient volatile CompletableFuture<Void> saveConfigFuture =
            CompletableFuture.completedFuture(null);
    private transient Consumer<String> errorDialogPresenter;

    public BuildConfigPanel(
            GenericBuildToolService buildToolService,
            SettingsService settingsService,
            SystemToolService systemToolService) {
        this.buildToolService = buildToolService;
        this.settingsService = settingsService;
        this.systemToolService = systemToolService;
        setLayout(new MigLayout("fill, insets 20", "[grow]", "[][][grow][]"));
        setOpaque(true);

        JLabel title = new JLabel("Build Configuration (build.toml)");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        add(title, "wrap, gapbottom 10");

        bannerPanel = new JPanel(new MigLayout("fillx, insets 10 14 10 14", "[][grow][][]", "[]"));
        bannerPanel.setOpaque(false);
        bannerIconLabel = new JLabel();
        bannerMessageLabel = new JLabel();
        containerToggleCheckBox = new JCheckBox("Run builds in Testcontainers (Docker)");
        containerToggleCheckBox.setOpaque(false);
        containerToggleCheckBox.addActionListener(
                e -> setContainerEnabledInToml(containerToggleCheckBox.isSelected()));

        recheckDockerButton = new JButton(RECHECK_DOCKER_TEXT);
        recheckDockerButton.addActionListener(e -> refreshDockerStatus());

        bannerPanel.add(bannerIconLabel, "gapright 8");
        bannerPanel.add(bannerMessageLabel, "growx, wmin 100");
        bannerPanel.add(containerToggleCheckBox, "gapleft 10");
        bannerPanel.add(recheckDockerButton, "gapleft 10");
        add(bannerPanel, "growx, wrap, gapbottom 12");

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
        statusLabel.setForeground(
                SwingHtmlUtils.getUiColor(new Color(0x2e, 0x7d, 0x32), "Actions.Green"));
        buttonPanel.add(statusLabel);
        add(buttonPanel, "growx, gaptop 15");

        statusTimer = new Timer(3000, e -> statusLabel.setText(""));
        statusTimer.setRepeats(false);

        loadConfig();
        updateBannerState();
        loadInitialDockerStatus();
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        Edt.run(
                () -> {
                    updateTextAreaTheme();
                    updateBannerState();
                });
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        Edt.run(
                () -> {
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
        CompletableFuture<Boolean> future =
                systemToolService != null ? systemToolService.isDockerAvailable() : null;
        if (future == null) {
            setDockerAvailable(false);
            return;
        }
        future.thenAccept(
                available -> Edt.run(() -> setDockerAvailable(Boolean.TRUE.equals(available))));
    }

    public CompletableFuture<Boolean> refreshDockerStatus() {
        if (systemToolService == null) {
            setDockerAvailable(false);
            return CompletableFuture.completedFuture(false);
        }
        Edt.run(
                () -> {
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
        return future.whenComplete(
                        (available, ex) ->
                                Edt.run(
                                        () -> {
                                            if (ex == null) {
                                                setDockerAvailable(Boolean.TRUE.equals(available));
                                            }
                                            recheckDockerButton.setText(RECHECK_DOCKER_TEXT);
                                            recheckDockerButton.setEnabled(true);
                                        }))
                .thenApply(Boolean.TRUE::equals);
    }

    void setDockerAvailable(boolean available) {
        this.dockerAvailable = available;
        updateBannerState();
    }

    boolean isDockerAvailableState() {
        return dockerAvailable;
    }

    private void onTomlTextEdited() {
        if (!updatingTextProgrammatically) {
            updateBannerState();
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (bannerPanel != null) {
            updateBannerState();
        }
        if (statusLabel != null) {
            statusLabel.setForeground(
                    SwingHtmlUtils.getUiColor(new Color(0x2e, 0x7d, 0x32), "Actions.Green"));
        }
    }

    void updateBannerState() {
        boolean containerEnabled =
                BuildContainerService.isContainerEnabledInToml(
                        textArea != null ? textArea.getText() : "");
        containerToggleCheckBox.setSelected(containerEnabled);
        containerToggleCheckBox.setEnabled(dockerAvailable);

        if (!dockerAvailable) {
            applyTintedBanner(
                    Codicons.WARNING,
                    SwingHtmlUtils.getUiColor(
                            new Color(0xd9, 0x77, 0x06),
                            "Component.warning.borderColor",
                            "Actions.Yellow"),
                    DOCKER_MISSING_WARNING_TEXT);
        } else if (containerEnabled) {
            applyTintedBanner(
                    Codicons.SHIELD,
                    SwingHtmlUtils.getUiColor(new Color(0x2e, 0x7d, 0x32), "Actions.Green"),
                    CONTAINER_ACTIVE_TEXT);
        } else {
            Color infoColor = SwingHtmlUtils.getAccentColor();
            Color borderColor =
                    SwingHtmlUtils.getUiColor(new Color(0xcc, 0xcc, 0xcc), "Component.borderColor");
            Color panelBg =
                    SwingHtmlUtils.getUiColor(new Color(0xf5, 0xf5, 0xf5), "Panel.background");
            applyBannerStyle(
                    Codicons.INFO,
                    infoColor,
                    "arc: 12; border: 1,1,1,1,"
                            + SwingHtmlUtils.toHex(borderColor)
                            + ",1,12; background: darken("
                            + SwingHtmlUtils.toHex(panelBg)
                            + ", 3%)",
                    HOST_EXECUTION_ACTIVE_TEXT);
        }
        bannerPanel.revalidate();
        bannerPanel.repaint();
    }

    private void applyTintedBanner(Codicons icon, Color color, String message) {
        String hex = SwingHtmlUtils.toHex(color);
        String bgHex = SwingHtmlUtils.toHex(SwingHtmlUtils.getTintedBackground(color, 0.15));
        applyBannerStyle(
                icon,
                color,
                "arc: 12; border: 1,1,1,1," + hex + ",1,12; background: " + bgHex,
                message);
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
        CompletableFuture<String> completed =
                CompletableFuture.supplyAsync(
                                () -> {
                                    try {
                                        String content = buildToolService.getConfigContent();
                                        return content != null ? content : "";
                                    } catch (IOException e) {
                                        log.error("Failed to load build config", e);
                                        return "# Error loading config: " + e.getMessage();
                                    }
                                })
                        .whenComplete(
                                (content, ex) -> {
                                    if (ex == null) {
                                        Edt.run(
                                                () -> {
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
        if (buildToolService == null) {
            return CompletableFuture.completedFuture(null);
        }
        String contentToSave = textArea.getText();
        Edt.run(() -> saveButton.setEnabled(false));
        CompletableFuture<Void> future =
                CompletableFuture.runAsync(
                                () -> {
                                    try {
                                        buildToolService.saveConfigContent(contentToSave);
                                    } catch (IOException e) {
                                        throw new UncheckedIOException(e);
                                    }
                                })
                        .whenComplete(
                                (unused, ex) ->
                                        Edt.run(
                                                () -> {
                                                    saveButton.setEnabled(true);
                                                    if (ex == null) {
                                                        updateBannerState();
                                                        statusLabel.setText(
                                                                "Configuration saved successfully!");
                                                        statusTimer.restart();
                                                    } else {
                                                        Throwable cause =
                                                                ExceptionUtils.getRootCause(ex);
                                                        log.error(
                                                                "Failed to save build config: {}",
                                                                cause.getMessage());
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

    CompletableFuture<String> getLoadConfigFuture() {
        return loadConfigFuture;
    }

    CompletableFuture<Void> getSaveConfigFuture() {
        return saveConfigFuture;
    }

    JLabel getStatusLabel() {
        return statusLabel;
    }

    private void updateTextAreaTheme() {
        SettingsPanel.applyRSyntaxTheme(textArea, settingsService);
    }

    JPanel getBannerPanel() {
        return bannerPanel;
    }

    JLabel getBannerIconLabel() {
        return bannerIconLabel;
    }

    JLabel getBannerMessageLabel() {
        return bannerMessageLabel;
    }

    JCheckBox getContainerToggleCheckBox() {
        return containerToggleCheckBox;
    }

    JButton getRecheckDockerButton() {
        return recheckDockerButton;
    }

    RSyntaxTextArea getTextArea() {
        return textArea;
    }

    JButton getSaveButton() {
        return saveButton;
    }
}
