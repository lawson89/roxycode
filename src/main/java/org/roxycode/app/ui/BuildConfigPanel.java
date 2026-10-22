package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.miginfocom.swing.MigLayout;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.service.SystemToolService;
import org.roxycode.app.util.Edt;
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
        bannerIconLabel = new JLabel();
        bannerMessageLabel = new JLabel();
        containerToggleCheckBox = new JCheckBox("Run builds in Testcontainers (Docker)");
        containerToggleCheckBox.setOpaque(false);
        containerToggleCheckBox.addActionListener(
                e -> setContainerEnabledInToml(containerToggleCheckBox.isSelected()));

        recheckDockerButton = new JButton("Re-check Docker");
        recheckDockerButton.addActionListener(e -> refreshDockerStatus());

        bannerPanel.add(bannerIconLabel, "gapright 8");
        bannerPanel.add(bannerMessageLabel, "growx, wmin 100");
        bannerPanel.add(containerToggleCheckBox, "gapleft 10");
        bannerPanel.add(recheckDockerButton, "gapleft 10");

        add(bannerPanel, "growx, wrap, gapbottom 12");

        textArea = new RSyntaxTextArea();
        // Using PROPERTIES as a safe fallback if TOML is not available in the library
        // version
        textArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_INI);
        textArea.setCodeFoldingEnabled(true);
        textArea.setAntiAliasingEnabled(true);
        textArea.getDocument()
                .addDocumentListener(
                        new DocumentListener() {
                            @Override
                            public void insertUpdate(DocumentEvent e) {
                                onTomlTextEdited();
                            }

                            @Override
                            public void removeUpdate(DocumentEvent e) {
                                onTomlTextEdited();
                            }

                            @Override
                            public void changedUpdate(DocumentEvent e) {
                                onTomlTextEdited();
                            }
                        });
        updateTextAreaTheme();

        RTextScrollPane scrollPane = new RTextScrollPane(textArea);
        add(scrollPane, "grow, wrap");

        JPanel buttonPanel = new JPanel(new MigLayout("insets 0", "[]10push[]"));
        buttonPanel.setOpaque(false);

        saveButton = new JButton("Save Changes");
        saveButton.addActionListener(e -> saveConfig());
        buttonPanel.add(saveButton);

        statusLabel = new JLabel("");
        statusLabel.setForeground(resolveColor(new Color(0x2e, 0x7d, 0x32), "Actions.Green"));
        buttonPanel.add(statusLabel);

        add(buttonPanel, "growx, gaptop 15");

        statusTimer = new Timer(3000, e -> statusLabel.setText(""));
        statusTimer.setRepeats(false);

        loadConfig();
        updateBannerState();
        refreshDockerStatus();
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        SwingUtilities.invokeLater(
                () -> {
                    updateTextAreaTheme();
                    updateBannerState();
                });
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        SwingUtilities.invokeLater(
                () -> {
                    loadConfig();
                    updateBannerState();
                });
    }

    public CompletableFuture<Boolean> refreshDockerStatus() {
        if (systemToolService == null) {
            setDockerAvailable(false);
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> future = systemToolService.isDockerAvailable();
        if (future == null) {
            boolean available = systemToolService.checkDockerAvailable();
            setDockerAvailable(available);
            return CompletableFuture.completedFuture(available);
        }
        return future.thenApply(
                available -> {
                    boolean isAvail = Boolean.TRUE.equals(available);
                    Edt.run(() -> setDockerAvailable(isAvail));
                    return isAvail;
                });
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
            statusLabel.setForeground(resolveColor(new Color(0x2e, 0x7d, 0x32), "Actions.Green"));
        }
    }

    void updateBannerState() {
        boolean containerEnabled =
                isContainerEnabledInToml(textArea != null ? textArea.getText() : "");
        containerToggleCheckBox.setSelected(containerEnabled);
        containerToggleCheckBox.setEnabled(dockerAvailable);

        if (!dockerAvailable) {
            Color warningColor =
                    resolveColor(
                            new Color(0xd9, 0x77, 0x06),
                            "Component.warning.borderColor",
                            "Actions.Yellow");
            String hex = toHex(warningColor);
            bannerIconLabel.setIcon(FontIcon.of(Codicons.WARNING, 18, warningColor));
            bannerMessageLabel.setText(DOCKER_MISSING_WARNING_TEXT);
            bannerPanel.putClientProperty(
                    FlatClientProperties.STYLE,
                    "arc: 12; border: 1,1,1,1,"
                            + hex
                            + ",1,12; background: fade("
                            + hex
                            + ", 15%)");
        } else if (containerEnabled) {
            Color verifiedColor = resolveColor(new Color(0x2e, 0x7d, 0x32), "Actions.Green");
            String hex = toHex(verifiedColor);
            bannerIconLabel.setIcon(FontIcon.of(Codicons.SHIELD, 18, verifiedColor));
            bannerMessageLabel.setText(CONTAINER_ACTIVE_TEXT);
            bannerPanel.putClientProperty(
                    FlatClientProperties.STYLE,
                    "arc: 12; border: 1,1,1,1,"
                            + hex
                            + ",1,12; background: fade("
                            + hex
                            + ", 15%)");
        } else {
            Color infoColor =
                    resolveColor(
                            new Color(0x00, 0x7a, 0xcc), "Component.accentColor", "Actions.Blue");
            Color borderColor = resolveColor(new Color(0xcc, 0xcc, 0xcc), "Component.borderColor");
            Color panelBg = resolveColor(new Color(0xf5, 0xf5, 0xf5), "Panel.background");
            String borderHex = toHex(borderColor);
            String panelBgHex = toHex(panelBg);
            bannerIconLabel.setIcon(FontIcon.of(Codicons.INFO, 18, infoColor));
            bannerMessageLabel.setText(HOST_EXECUTION_ACTIVE_TEXT);
            bannerPanel.putClientProperty(
                    FlatClientProperties.STYLE,
                    "arc: 12; border: 1,1,1,1,"
                            + borderHex
                            + ",1,12; background: darken("
                            + panelBgHex
                            + ", 3%)");
        }
        bannerPanel.revalidate();
        bannerPanel.repaint();
    }

    private static Color resolveColor(Color fallback, String... uiKeys) {
        for (String key : uiKeys) {
            Color color = UIManager.getColor(key);
            if (color != null) {
                return color;
            }
        }
        return fallback;
    }

    private static String toHex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    static boolean isContainerEnabledInToml(String toml) {
        if (toml == null || toml.isBlank()) {
            return false;
        }
        java.util.List<String> lines = toml.lines().toList();
        boolean inContainerSection = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inContainerSection = "[container]".equals(trimmed);
                continue;
            }
            if (inContainerSection && isEnabledAssignment(trimmed)) {
                int eqIdx = trimmed.indexOf('=');
                if (eqIdx >= 0) {
                    String val = trimmed.substring(eqIdx + 1).trim();
                    int commentIdx = val.indexOf('#');
                    if (commentIdx >= 0) {
                        val = val.substring(0, commentIdx).trim();
                    }
                    return Boolean.parseBoolean(val);
                }
            }
        }
        return false;
    }

    @SuppressWarnings("PMD.UnusedAssignment")
    void setContainerEnabledInToml(boolean enabled) {
        String current = textArea.getText();
        String updated = updateContainerEnabledInToml(current, enabled);
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

    static String updateContainerEnabledInToml(String toml, boolean enabled) {
        String safeToml = toml != null ? toml : "";
        java.util.List<String> lines = safeToml.lines().toList();
        boolean foundContainerSection = false;
        boolean updatedEnabledLine = false;
        int containerSectionIndex = -1;
        java.util.List<String> outputLines = new java.util.ArrayList<>();

        boolean inContainerSection = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inContainerSection = "[container]".equals(trimmed);
                if (inContainerSection) {
                    foundContainerSection = true;
                    containerSectionIndex = i;
                }
                outputLines.add(line);
            } else if (inContainerSection && !updatedEnabledLine && isEnabledAssignment(trimmed)) {
                int eqIdx = line.indexOf('=');
                String prefix = line.substring(0, eqIdx + 1);
                String rest = line.substring(eqIdx + 1);
                int commentIdx = rest.indexOf('#');
                String commentSuffix = commentIdx >= 0 ? " " + rest.substring(commentIdx) : "";
                outputLines.add(prefix + " " + enabled + commentSuffix);
                updatedEnabledLine = true;
            } else {
                outputLines.add(line);
            }
        }

        if (!foundContainerSection) {
            String section =
                    """
                    [container]
                    enabled = %s
                    image = 'eclipse-temurin:25-jdk'
                    workdir = '/workspace'
                    """
                            .formatted(enabled);
            if (safeToml.isEmpty()) {
                return section;
            }
            return safeToml.stripTrailing()
                    + """


                    """
                    + section;
        }

        if (!updatedEnabledLine && containerSectionIndex >= 0) {
            outputLines.add(containerSectionIndex + 1, "enabled = " + enabled);
        }

        return String.join(
                """

                """,
                outputLines);
    }

    private static boolean isEnabledAssignment(String trimmed) {
        if (!trimmed.startsWith("enabled")) {
            return false;
        }
        String afterKey = trimmed.substring("enabled".length()).trim();
        return afterKey.startsWith("=");
    }

    @SuppressWarnings("PMD.UnusedAssignment")
    private void loadConfig() {
        updatingTextProgrammatically = true;
        try {
            textArea.setText(buildToolService.getConfigContent());
            textArea.setCaretPosition(0);
        } catch (IOException e) {
            log.error("Failed to load build config", e);
            textArea.setText("# Error loading config: " + e.getMessage());
        } finally {
            updatingTextProgrammatically = false;
        }
    }

    private void saveConfig() {
        try {
            buildToolService.saveConfigContent(textArea.getText());
            updateBannerState();
            statusLabel.setText("Configuration saved successfully!");
            statusTimer.restart();
        } catch (Exception e) {
            log.error("Failed to save build config: {}", e.getMessage());
            JOptionPane.showMessageDialog(
                    this,
                    "Error saving config: " + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateTextAreaTheme() {
        String themeName = settingsService.getSettings().getTheme();
        boolean isDark =
                themeName.toLowerCase(Locale.ROOT).contains("dark")
                        || "Darcula".equalsIgnoreCase(themeName);
        String themePath =
                isDark
                        ? "/org/fife/ui/rsyntaxtextarea/themes/dark.xml"
                        : "/org/fife/ui/rsyntaxtextarea/themes/default.xml";
        try {
            Theme theme = Theme.load(getClass().getResourceAsStream(themePath));
            theme.apply(textArea);
        } catch (IOException ioe) {
            log.error("Failed to load RSyntaxTextArea theme: {}", ioe.getMessage());
        }
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
