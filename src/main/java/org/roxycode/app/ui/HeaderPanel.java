package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ThinkingLevel;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.util.Edt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Lazy
public final class HeaderPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(HeaderPanel.class);
    private static final String LAYOUT_CENTER = "center";
    static final String SELECT_PROJECT_TEXT = "Select Project...";
    static final String CHANGE_PROJECT_TEXT = "Change Project...";
    static final String NO_PROJECT_NOTICE_TEXT = "Select a project folder to get started";
    private static final Color BRANCH_ACCENT_LIGHT = new Color(0x0d, 0x94, 0x88);
    private static final Color BRANCH_ACCENT_DARK = new Color(0x2d, 0xd4, 0xbf);
    private static final Color MODEL_ACCENT_LIGHT = new Color(0x7c, 0x3a, 0xed);
    private static final Color MODEL_ACCENT_DARK = new Color(0xa7, 0x8b, 0xfa);
    private static final String PILL_STYLE_PREFIX = "font: bold -1; background: ";
    private static final String PILL_STYLE_SUFFIX = "; arc: 10; border: 3,8,3,8";

    private final transient ProjectService projectService;
    private final transient GitService gitService;
    private final transient SettingsService settingsService;

    final JLabel projectLabel;
    final JButton changeProjectButton;
    final JLabel noProjectNoticeLabel;
    final JLabel branchLabel;
    final JLabel modelLabel;
    final JLabel groundingLabel;
    final JButton themeToggleButton;

    public HeaderPanel(ProjectService projectService, GitService gitService, SettingsService settingsService) {
        this.projectService = projectService;
        this.gitService = gitService;
        this.settingsService = settingsService;

        setLayout(new MigLayout("insets 2 15 2 15, fillx", "[left]push[right]", LAYOUT_CENTER));
        putClientProperty(FlatClientProperties.STYLE, "background: darken($Panel.background, 2%)");
        updateBorder();

        JPanel leftPanel = new JPanel(new MigLayout("insets 0, hidemode 3", "[]8[]8[]12[]", LAYOUT_CENTER));
        leftPanel.setOpaque(false);

        projectLabel = new JLabel(projectService.getProjectName());
        projectLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold +1");
        leftPanel.add(projectLabel);

        changeProjectButton = createChangeProjectButton(e -> chooseProject());
        leftPanel.add(changeProjectButton);

        noProjectNoticeLabel = new JLabel(NO_PROJECT_NOTICE_TEXT);
        leftPanel.add(noProjectNoticeLabel);

        branchLabel = new JLabel();
        branchLabel.setVisible(false);
        leftPanel.add(branchLabel);
        add(leftPanel, "left");

        JPanel rightPanel = new JPanel(new MigLayout("insets 0", "[]8[]8[]", LAYOUT_CENTER));
        rightPanel.setOpaque(false);

        modelLabel = new JLabel();
        modelLabel.setToolTipText("Active Gemini model");

        groundingLabel = new JLabel();
        groundingLabel.setToolTipText("Google Search Grounding enabled");

        updateModelDisplay(resolveEffectiveModel(), resolveEffectiveThinkingLevel());
        themeToggleButton = createIconButton(Codicons.COLOR_MODE, "Toggle Light/Dark Theme", e -> toggleTheme());

        rightPanel.add(modelLabel);
        rightPanel.add(groundingLabel);
        rightPanel.add(themeToggleButton);
        add(rightPanel, "right");

        applyHeaderStyling();
        updateProjectStateDisplay();
        updateBranchLabel();
        updateProjectTooltip(projectService.getCurrentProjectRoot());
    }

    void updateProjectStateDisplay() {
        boolean hasProject = projectService != null && projectService.hasActiveProject();
        if (hasProject) {
            changeProjectButton.setText(CHANGE_PROJECT_TEXT);
            noProjectNoticeLabel.setVisible(false);
        } else {
            changeProjectButton.setText(SELECT_PROJECT_TEXT);
            noProjectNoticeLabel.setVisible(true);
        }
    }

    private String resolveEffectiveModel() {
        return ProjectService.resolveEffectiveGeminiModel(projectService, settingsService);
    }

    private ThinkingLevel resolveEffectiveThinkingLevel() {
        return ProjectService.resolveEffectiveThinkingLevel(projectService, settingsService);
    }

    private void updateProjectTooltip(Path root) {
        if (root != null) {
            projectLabel.setToolTipText(root.toAbsolutePath().toString());
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

    private static Color resolveForegroundColor() {
        return SwingHtmlUtils.getUiColor(new Color(0x33, 0x33, 0x33), "Label.foreground");
    }

    static Color resolveBranchAccentColor() {
        if (FlatLaf.isLafDark()) {
            return BRANCH_ACCENT_DARK;
        }
        return BRANCH_ACCENT_LIGHT;
    }

    static Color resolveModelAccentColor() {
        if (FlatLaf.isLafDark()) {
            return MODEL_ACCENT_DARK;
        }
        return MODEL_ACCENT_LIGHT;
    }

    void applyHeaderStyling() {
        Color accent = SwingHtmlUtils.getAccentColor();
        Color branchAccent = resolveBranchAccentColor();
        Color modelAccent = resolveModelAccentColor();
        String accentHex = SwingHtmlUtils.toHex(accent);
        String branchBgHex = SwingHtmlUtils.toHex(SwingHtmlUtils.getTintedBackground(branchAccent, 0.12));
        String modelBgHex = SwingHtmlUtils.toHex(SwingHtmlUtils.getTintedBackground(modelAccent, 0.12));

        projectLabel.setIcon(FontIcon.of(Codicons.REPO, 16, accent));

        changeProjectButton.setIcon(FontIcon.of(Codicons.FOLDER_OPENED, 14, accent));
        changeProjectButton.putClientProperty(FlatClientProperties.STYLE,
                "arc: 10; font: -1; margin: 2,8,2,8; background: fade(" + accentHex + ", 10%); hoverBackground: fade("
                        + accentHex + ", 20%); pressedBackground: fade(" + accentHex + ", 28%); borderColor: fade("
                        + accentHex + ", 35%)");

        Color warnColor = SwingHtmlUtils.getWarningColor();
        String warnBgHex = SwingHtmlUtils.toHex(SwingHtmlUtils.getTintedBackground(warnColor, 0.14));
        noProjectNoticeLabel.setForeground(warnColor);
        noProjectNoticeLabel.setIcon(FontIcon.of(Codicons.WARNING, 14, warnColor));
        noProjectNoticeLabel.putClientProperty(FlatClientProperties.STYLE,
                PILL_STYLE_PREFIX + warnBgHex + PILL_STYLE_SUFFIX);

        branchLabel.setForeground(branchAccent);
        branchLabel.putClientProperty(FlatClientProperties.STYLE, PILL_STYLE_PREFIX + branchBgHex + PILL_STYLE_SUFFIX);
        if (branchLabel.getText() != null && !branchLabel.getText().isEmpty()) {
            branchLabel.setIcon(FontIcon.of(Codicons.SOURCE_CONTROL, 14, branchAccent));
        }

        modelLabel.setForeground(modelAccent);
        modelLabel.putClientProperty(FlatClientProperties.STYLE, PILL_STYLE_PREFIX + modelBgHex + PILL_STYLE_SUFFIX);
        modelLabel.setIcon(FontIcon.of(Codicons.HUBOT, 14, modelAccent));

        groundingLabel.setForeground(modelAccent);
        groundingLabel.putClientProperty(FlatClientProperties.STYLE,
                PILL_STYLE_PREFIX + modelBgHex + PILL_STYLE_SUFFIX);
        groundingLabel.setIcon(FontIcon.of(Codicons.GLOBE, 14, modelAccent));

        themeToggleButton.setIcon(FontIcon.of(Codicons.COLOR_MODE, 16, resolveForegroundColor()));
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        Edt.run(() -> {
            projectLabel.setText(projectService.getProjectName());
            updateProjectStateDisplay();
            updateProjectTooltip(event.projectRoot());
            updateModelDisplay(resolveEffectiveModel(), resolveEffectiveThinkingLevel());
            updateBranchLabel();
        });
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        Edt.run(() -> updateModelDisplay(resolveEffectiveModel(), resolveEffectiveThinkingLevel()));
    }

    private void toggleTheme() {
        AppSettings settings = settingsService.getSettings();
        String currentTheme = settings.getTheme();
        boolean isDark;
        if (currentTheme != null && !currentTheme.isBlank()) {
            isDark = SwingHtmlUtils.isDarkTheme(currentTheme);
        } else {
            isDark = FlatLaf.isLafDark();
        }
        String newTheme;
        if (isDark) {
            newTheme = "FlatLaf Light";
        } else {
            newTheme = "FlatLaf Dark";
        }
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

    private JButton createIconButton(Codicons icon, String tooltip, java.awt.event.ActionListener listener) {
        JButton button = new JButton(FontIcon.of(icon, 16, resolveForegroundColor()));
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        button.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(listener);
        return button;
    }

    CompletableFuture<String> updateBranchLabel() {
        return Edt.supplyAsync(gitService::getCurrentBranch, branch -> {
            boolean valid = branch != null && !branch.isEmpty() && !branch.startsWith("Error")
                    && !"Not a git repository".equals(branch);
            if (valid) {
                branchLabel.setIcon(FontIcon.of(Codicons.SOURCE_CONTROL, 14, resolveBranchAccentColor()));
                branchLabel.setText(branch);
            } else {
                branchLabel.setIcon(null);
                branchLabel.setText("");
            }
            branchLabel.setVisible(valid);
        }, e -> {
            log.error("Failed to fetch git branch", e);
            branchLabel.setIcon(null);
            branchLabel.setText("");
            branchLabel.setVisible(false);
        });
    }

    private void updateModelDisplay(String modelName, ThinkingLevel thinkingLevel) {
        groundingLabel.setVisible(settingsService.getSettings().isGoogleGroundingEnabled());
        modelLabel.setIcon(FontIcon.of(Codicons.HUBOT, 14, resolveModelAccentColor()));
        String baseModelText = java.util.Objects.requireNonNullElse(modelName, "No model selected");
        ThinkingLevel resolvedLevel = java.util.Objects.requireNonNullElse(thinkingLevel, ThinkingLevel.DEFAULT);
        if (resolvedLevel != ThinkingLevel.DEFAULT) {
            modelLabel.setText(baseModelText + " • " + resolvedLevel.getDisplayName());
        } else {
            modelLabel.setText(baseModelText);
        }
        modelLabel.setToolTipText(
                "Active Gemini model: " + baseModelText + " (Thinking: " + resolvedLevel.getDisplayName() + ")");
    }

    private void chooseProject() {
        File file = UiComponents.chooseDirectory(this, "Select Project Directory",
                projectService.getCurrentProjectRoot());
        if (file != null) {
            switchProjectAsync(file.toPath());
        }
    }

    CompletableFuture<Void> switchProjectAsync(Path projectPath) {
        return Edt.runAsync(() -> projectService.setCurrentProjectRoot(projectPath), null);
    }
}
