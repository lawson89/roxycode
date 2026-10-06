package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import net.miginfocom.swing.MigLayout;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.roxycode.app.service.PromptService;
import org.roxycode.app.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;

public class PromptPanel extends JPanel {
    private static final Logger log = LoggerFactory.getLogger(PromptPanel.class);
    private final PromptService promptService;
    private final SettingsService settingsService;
    private final RSyntaxTextArea textArea;
    private final JButton saveButton;
    private final JButton resetButton;
    private final JLabel statusLabel;
    private final Timer statusTimer;

    public PromptPanel(PromptService promptService, SettingsService settingsService) {
        this.promptService = promptService;
        this.settingsService = settingsService;
        setLayout(new MigLayout("fill, insets 20", "[grow]", "[][grow][]"));
        setOpaque(true);

        JLabel title = new JLabel("Core Workflow Prompt");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        add(title, "wrap, gapbottom 15");

        textArea = new RSyntaxTextArea();
        textArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
        textArea.setCodeFoldingEnabled(true);
        textArea.setAntiAliasingEnabled(true);
        updateTextAreaTheme();

        RTextScrollPane scrollPane = new RTextScrollPane(textArea);
        add(scrollPane, "grow, wrap");

        JPanel buttonPanel = new JPanel(new MigLayout("insets 0", "[]10[]push[]"));
        buttonPanel.setOpaque(false);

        saveButton = new JButton("Save Changes");
        saveButton.addActionListener(e -> savePrompt());
        buttonPanel.add(saveButton);

        resetButton = new JButton("Reset to Default");
        resetButton.addActionListener(e -> resetPrompt());
        buttonPanel.add(resetButton);

        statusLabel = new JLabel("");
        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Actions.Green");
        buttonPanel.add(statusLabel);

        add(buttonPanel, "growx, gaptop 15");

        statusTimer = new Timer(3000, e -> statusLabel.setText(""));
        statusTimer.setRepeats(false);

        loadPrompt();

        settingsService.addSettingsListener(settings -> {
            updateTextAreaTheme();
        });
    }

    private void loadPrompt() {
        textArea.setText(promptService.loadCoreWorkflowPrompt());
        textArea.setCaretPosition(0);
    }

    private void savePrompt() {
        try {
            promptService.saveCoreWorkflowPrompt(textArea.getText());
            statusLabel.setText("Prompt saved successfully!");
            statusTimer.restart();
        } catch (Exception e) {
            log.error("Failed to save prompt: {}", e.getMessage());
            JOptionPane.showMessageDialog(this, "Error saving prompt: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void resetPrompt() {
        int choice = JOptionPane.showConfirmDialog(this, 
                "Are you sure you want to reset the prompt to its default state? This will discard your custom changes.",
                "Confirm Reset", JOptionPane.YES_NO_OPTION);
        
        if (choice == JOptionPane.YES_OPTION) {
            try {
                promptService.resetCoreWorkflowPrompt();
                loadPrompt();
                statusLabel.setText("Prompt reset to default.");
                statusTimer.restart();
            } catch (Exception e) {
                log.error("Failed to reset prompt: {}", e.getMessage());
                JOptionPane.showMessageDialog(this, "Error resetting prompt: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
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
