package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import net.miginfocom.swing.MigLayout;
import org.roxycode.app.service.SettingsService;

import javax.swing.*;
import java.awt.*;

/**
 * Panel for application settings.
 */
public class SettingsPanel extends JPanel {
    private final SettingsService settingsService;
    private final JComboBox<String> themeCombo;
    private final JPasswordField geminiApiKeyField;
    private final JButton saveButton;
    private final JLabel statusLabel;
    private final Timer statusTimer;

    public SettingsPanel(SettingsService settingsService) {
        this.settingsService = settingsService;
        setLayout(new MigLayout("fillx, insets 20", "[right][fill, grow]", "[]20[]"));
        setOpaque(true);

        JLabel title = new JLabel("Settings");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        add(title, "span, gapbottom 15, wrap");

        add(new JLabel("Theme:"));
        themeCombo = new JComboBox<>(new String[] {
            "FlatLaf Light", "FlatLaf Dark", "IntelliJ", "Darcula", "macOS Light", "macOS Dark"
        });
        themeCombo.setSelectedItem(settingsService.getSettings().getTheme());
        themeCombo.addActionListener(e -> updateTheme());
        add(themeCombo, "wrap");

        add(new JLabel("Gemini API Key:"));
        geminiApiKeyField = new JPasswordField(20);
        geminiApiKeyField.setText(settingsService.getSettings().getGeminiApiKey());
        add(geminiApiKeyField, "wrap");

        saveButton = new JButton("Save Settings");
        saveButton.addActionListener(e -> saveSettings());
        add(saveButton, "span, split 2, gaptop 20");

        statusLabel = new JLabel("");
        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Actions.Green");
        add(statusLabel, "gapleft 10");

        statusTimer = new Timer(3000, e -> {
            statusLabel.setText("");
        });
        statusTimer.setRepeats(false);
    }

    private void updateTheme() {
        String selectedTheme = (String) themeCombo.getSelectedItem();
        if (selectedTheme != null) {
            settingsService.getSettings().setTheme(selectedTheme);
            applyTheme(selectedTheme);
        }
    }

    private void saveSettings() {
        settingsService.getSettings().setGeminiApiKey(new String(geminiApiKeyField.getPassword()));
        settingsService.saveSettings();
        statusLabel.setText("Settings saved!");
        statusTimer.restart();
    }

    /**
     * Applies the specified theme to the UI.
     * @param themeName The name of the theme to apply.
     */
    public static void applyTheme(String themeName) {
        try {
            switch (themeName) {
                case "FlatLaf Light" -> UIManager.setLookAndFeel(new FlatLightLaf());
                case "FlatLaf Dark" -> UIManager.setLookAndFeel(new FlatDarkLaf());
                case "IntelliJ" -> UIManager.setLookAndFeel(new FlatIntelliJLaf());
                case "Darcula" -> UIManager.setLookAndFeel(new FlatDarculaLaf());
                case "macOS Light" -> UIManager.setLookAndFeel(new FlatMacLightLaf());
                case "macOS Dark" -> UIManager.setLookAndFeel(new FlatMacDarkLaf());
                default -> UIManager.setLookAndFeel(new FlatLightLaf());
            }
            FlatLaf.updateUI();
        } catch (Exception ex) {
            // Log failure
        }
    }
}