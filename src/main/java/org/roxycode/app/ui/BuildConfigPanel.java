package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import net.miginfocom.swing.MigLayout;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;

public class BuildConfigPanel extends JPanel {
    private static final Logger log = LoggerFactory.getLogger(BuildConfigPanel.class);
    private final GenericBuildToolService buildToolService;
    private final SettingsService settingsService;
    private final ProjectService projectService;
    private final RSyntaxTextArea textArea;
    private final JButton saveButton;
    private final JLabel statusLabel;
    private final Timer statusTimer;

    public BuildConfigPanel(GenericBuildToolService buildToolService, SettingsService settingsService, ProjectService projectService) {
        this.buildToolService = buildToolService;
        this.settingsService = settingsService;
        this.projectService = projectService;
        setLayout(new MigLayout("fill, insets 20", "[grow]", "[][grow][]"));
        setOpaque(true);

        JLabel title = new JLabel("Build Configuration (agents/colinxcode.toml)");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        add(title, "wrap, gapbottom 15");

        textArea = new RSyntaxTextArea();
        // Using PROPERTIES as a safe fallback if TOML is not available in the library version
        textArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_INI);
        textArea.setCodeFoldingEnabled(true);
        textArea.setAntiAliasingEnabled(true);
        updateTextAreaTheme();

        RTextScrollPane scrollPane = new RTextScrollPane(textArea);
        add(scrollPane, "grow, wrap");

        JPanel buttonPanel = new JPanel(new MigLayout("insets 0", "[]10push[]"));
        buttonPanel.setOpaque(false);

        saveButton = new JButton("Save Changes");
        saveButton.addActionListener(e -> saveConfig());
        buttonPanel.add(saveButton);

        statusLabel = new JLabel("");
        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Actions.Green");
        buttonPanel.add(statusLabel);

        add(buttonPanel, "growx, gaptop 15");

        statusTimer = new Timer(3000, e -> statusLabel.setText(""));
        statusTimer.setRepeats(false);

        loadConfig();

        settingsService.addSettingsListener(settings -> updateTextAreaTheme());
        projectService.addProjectListener(path -> loadConfig());
    }

    private void loadConfig() {
        try {
            textArea.setText(buildToolService.getConfigContent());
            textArea.setCaretPosition(0);
        } catch (IOException e) {
            log.error("Failed to load build config", e);
            textArea.setText("# Error loading config: " + e.getMessage());
        }
    }

    private void saveConfig() {
        try {
            buildToolService.saveConfigContent(textArea.getText());
            statusLabel.setText("Configuration saved successfully!");
            statusTimer.restart();
        } catch (Exception e) {
            log.error("Failed to save build config: {}", e.getMessage());
            JOptionPane.showMessageDialog(this, "Error saving config: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateTextAreaTheme() {
        String themeName = settingsService.getSettings().getTheme();
        boolean isDark = themeName.toLowerCase().contains("dark") || themeName.equalsIgnoreCase("Darcula");
        String themePath = isDark ? "/org/fife/ui/rsyntaxtextarea/themes/dark.xml" : "/org/fife/ui/rsyntaxtextarea/themes/default.xml";
        try {
            Theme theme = Theme.load(getClass().getResourceAsStream(themePath));
            theme.apply(textArea);
        } catch (IOException ioe) {
            log.error("Failed to load RSyntaxTextArea theme: {}", ioe.getMessage());
        }
    }
}
