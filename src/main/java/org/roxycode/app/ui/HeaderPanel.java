package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.util.Edt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Header panel containing application controls and project selection. */
@Component
@Lazy
public final class HeaderPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(HeaderPanel.class);
    private static final String LAYOUT_CENTER = "center";
    static final String CHANGE_PROJECT_TEXT = "Change Project...";
    private static final String PILL_STYLE =
            "font: -1; foreground: $Label.disabledForeground; background: darken($Panel.background, 3%); arc: 10; border: 3,8,3,8";

    private final transient ProjectService projectService;
    private final transient GitService gitService;
    private final transient SettingsService settingsService;

    private final JLabel projectLabel;
    private final JButton changeProjectButton;
    private final JLabel branchLabel;
    private final JLabel modelLabel;
    private final JButton themeToggleButton;

    public HeaderPanel(
            ProjectService projectService, GitService gitService, SettingsService settingsService) {
        this.projectService = projectService;
        this.gitService = gitService;
        this.settingsService = settingsService;

        // 2-column layout: Left (Project), Right (Utils)
        setLayout(new MigLayout("insets 2 15 2 15, fillx", "[left]push[right]", LAYOUT_CENTER));

        putClientProperty(FlatClientProperties.STYLE, "background: darken($Panel.background, 2%)");
        updateBorder();

        // --- LEFT SECTION: Project Info ---
        JPanel leftPanel = new JPanel(new MigLayout("insets 0", "[]8[]12[]", LAYOUT_CENTER));
        leftPanel.setOpaque(false);

        projectLabel = new JLabel(projectService.getProjectName());
        projectLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold +1");
        leftPanel.add(projectLabel);

        changeProjectButton = createChangeProjectButton(e -> chooseProject());
        leftPanel.add(changeProjectButton);

        branchLabel = new JLabel();
        branchLabel.setVisible(false);
        leftPanel.add(branchLabel);

        add(leftPanel, "left");

        // --- RIGHT SECTION: Model & Utils ---
        JPanel rightPanel = new JPanel(new MigLayout("insets 0", "[]8[]", LAYOUT_CENTER));
        rightPanel.setOpaque(false);

        modelLabel = new JLabel();
        updateModelDisplay(settingsService.getSettings().getGeminiModel());

        themeToggleButton =
                createIconButton(
                        Codicons.COLOR_MODE, "Toggle Light/Dark Theme", e -> toggleTheme());

        rightPanel.add(modelLabel);
        rightPanel.add(themeToggleButton);
        add(rightPanel, "right");

        applyHeaderStyling();
        updateBranchLabel();

        if (projectService.getCurrentProjectRoot() != null) {
            projectLabel.setToolTipText(
                    projectService.getCurrentProjectRoot().toAbsolutePath().toString());
        } else {
            projectLabel.setToolTipText("No project selected");
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        updateBorder();
        if (projectLabel != null) {
            applyHeaderStyling();
        }
    }

    private void updateBorder() {
        setBorder(SwingHtmlUtils.createThemeMatteBorder(0, 0, 1, 0));
    }

    private static Color resolveAccentColor() {
        return SwingHtmlUtils.getUiColor(
                new Color(0x00, 0x7a, 0xcc), "Component.accentColor", "Actions.Blue");
    }

    private static Color resolveMutedColor() {
        return SwingHtmlUtils.getUiColor(
                new Color(0x77, 0x77, 0x77),
                "Label.disabledForeground",
                "Component.infoForeground");
    }

    private static Color resolveForegroundColor() {
        return SwingHtmlUtils.getUiColor(new Color(0x33, 0x33, 0x33), "Label.foreground");
    }

    void applyHeaderStyling() {
        Color accent = resolveAccentColor();
        Color muted = resolveMutedColor();
        Color fg = resolveForegroundColor();
        String accentHex = SwingHtmlUtils.toHex(accent);

        projectLabel.setIcon(FontIcon.of(Codicons.REPO, 16, accent));

        changeProjectButton.setIcon(FontIcon.of(Codicons.FOLDER_OPENED, 14, accent));
        changeProjectButton.putClientProperty(
                FlatClientProperties.STYLE,
                "arc: 10; font: -1; margin: 2,8,2,8; "
                        + "background: fade("
                        + accentHex
                        + ", 10%); "
                        + "hoverBackground: fade("
                        + accentHex
                        + ", 20%); "
                        + "pressedBackground: fade("
                        + accentHex
                        + ", 28%); "
                        + "borderColor: fade("
                        + accentHex
                        + ", 35%)");

        branchLabel.putClientProperty(FlatClientProperties.STYLE, PILL_STYLE);
        if (branchLabel.getText() != null && !branchLabel.getText().isEmpty()) {
            branchLabel.setIcon(FontIcon.of(Codicons.SOURCE_CONTROL, 14, muted));
        }

        modelLabel.putClientProperty(FlatClientProperties.STYLE, PILL_STYLE);
        modelLabel.setIcon(FontIcon.of(Codicons.HUBOT, 14, muted));

        themeToggleButton.setIcon(FontIcon.of(Codicons.COLOR_MODE, 16, fg));
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        Edt.run(
                () -> {
                    Path newRoot = event.projectRoot();
                    projectLabel.setText(projectService.getProjectName());
                    if (newRoot != null) {
                        projectLabel.setToolTipText(newRoot.toAbsolutePath().toString());
                    } else {
                        projectLabel.setToolTipText("No project selected");
                    }
                    updateBranchLabel();
                });
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        Edt.run(() -> updateModelDisplay(event.settings().getGeminiModel()));
    }

    private void toggleTheme() {
        AppSettings settings = settingsService.getSettings();
        String currentTheme = settings.getTheme();
        boolean isDark =
                (currentTheme != null && !currentTheme.isBlank())
                        ? SettingsPanel.isDarkTheme(currentTheme)
                        : FlatLaf.isLafDark();
        String newTheme = isDark ? "FlatLaf Light" : "FlatLaf Dark";
        settings.setTheme(newTheme);
        SettingsPanel.applyTheme(newTheme);
        settingsService.saveSettings();
    }

    private JButton createChangeProjectButton(java.awt.event.ActionListener listener) {
        JButton button = new JButton(CHANGE_PROJECT_TEXT);
        button.setToolTipText("Open Project");
        button.setFocusable(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(listener);
        return button;
    }

    private JButton createIconButton(
            Codicons icon, String tooltip, java.awt.event.ActionListener listener) {
        JButton button = new JButton(FontIcon.of(icon, 16, resolveForegroundColor()));
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setContentAreaFilled(false);
        button.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(listener);

        button.addMouseListener(
                new java.awt.event.MouseAdapter() {
                    @Override
                    public void mouseEntered(java.awt.event.MouseEvent e) {
                        button.setContentAreaFilled(true);
                        button.setBackground(UIManager.getColor("Button.hoverBackground"));
                    }

                    @Override
                    public void mouseExited(java.awt.event.MouseEvent e) {
                        button.setContentAreaFilled(false);
                    }
                });

        return button;
    }

    SwingWorker<String, Void> updateBranchLabel() {
        SwingWorker<String, Void> worker =
                new SwingWorker<>() {
                    @Override
                    protected String doInBackground() {
                        return gitService.getCurrentBranch();
                    }

                    @Override
                    protected void done() {
                        try {
                            String branch = get();
                            if (branch != null
                                    && !branch.isEmpty()
                                    && !branch.startsWith("Error")
                                    && !"Not a git repository".equals(branch)) {
                                branchLabel.setIcon(
                                        FontIcon.of(
                                                Codicons.SOURCE_CONTROL, 14, resolveMutedColor()));
                                branchLabel.setText(branch);
                                branchLabel.setVisible(true);
                            } else {
                                branchLabel.setIcon(null);
                                branchLabel.setText("");
                                branchLabel.setVisible(false);
                            }
                        } catch (Exception e) {
                            log.error("Failed to fetch git branch", e);
                            branchLabel.setIcon(null);
                            branchLabel.setText("");
                            branchLabel.setVisible(false);
                        }
                    }
                };
        worker.execute();
        return worker;
    }

    JLabel getProjectLabel() {
        return projectLabel;
    }

    JButton getChangeProjectButton() {
        return changeProjectButton;
    }

    JLabel getBranchLabel() {
        return branchLabel;
    }

    JLabel getModelLabel() {
        return modelLabel;
    }

    JButton getThemeToggleButton() {
        return themeToggleButton;
    }

    private void updateModelDisplay(String modelName) {
        modelLabel.setIcon(FontIcon.of(Codicons.HUBOT, 14, resolveMutedColor()));
        modelLabel.setText(modelName != null ? modelName : "No model selected");
    }

    private void chooseProject() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (projectService.getCurrentProjectRoot() != null) {
            chooser.setCurrentDirectory(projectService.getCurrentProjectRoot().toFile());
        }

        int returnVal = chooser.showOpenDialog(this);
        if (returnVal == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            projectService.setCurrentProjectRoot(file.toPath());
        }
    }
}
