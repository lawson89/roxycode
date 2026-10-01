package org.roxycode.app.ui;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import net.miginfocom.swing.MigLayout;
import org.roxycode.app.model.config.GeminiModelConfig;
import org.roxycode.app.model.config.GeminiModels;
import org.roxycode.app.service.SettingsService;

import javax.swing.*;
import java.awt.*;
import java.util.Collections;
import java.util.List;

/**
 * Panel for application settings.
 */
public class SettingsPanel extends JPanel {
    private final SettingsService settingsService;
    private final JComboBox<String> themeCombo;
    private final JComboBox<GeminiModelConfig> modelCombo;
    private final JPasswordField geminiApiKeyField;
    private final JSpinner maxDiscoveryTurnsSpinner;
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

        add(new JLabel("Gemini Model:"));
        modelCombo = new JComboBox<>();
        loadModels();
        add(modelCombo, "wrap");

        add(new JLabel("Gemini API Key:"));
        geminiApiKeyField = new JPasswordField(20);
        geminiApiKeyField.setText(settingsService.getSettings().getGeminiApiKey());
        add(geminiApiKeyField, "wrap");

        add(new JLabel("Max Discovery Turns:"));
        maxDiscoveryTurnsSpinner = new JSpinner(new SpinnerNumberModel(
            settingsService.getSettings().getMaxDiscoveryTurns(), 1, 50, 1));
        add(maxDiscoveryTurnsSpinner, "wrap");

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

    private void loadModels() {
        try {
            TomlMapper mapper = new TomlMapper();
            GeminiModels geminiModels = mapper.readValue(getClass().getResourceAsStream("/models.toml"), GeminiModels.class);
            List<GeminiModelConfig> modelList = geminiModels.models();
            DefaultComboBoxModel<GeminiModelConfig> model = new DefaultComboBoxModel<>(modelList.toArray(new GeminiModelConfig[0]));
            modelCombo.setModel(model);
            
            String currentModelId = settingsService.getSettings().getGeminiModel();
            for (int i = 0; i < modelCombo.getItemCount(); i++) {
                if (modelCombo.getItemAt(i).apiName().equals(currentModelId)) {
                    modelCombo.setSelectedIndex(i);
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
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
        GeminiModelConfig selectedModel = (GeminiModelConfig) modelCombo.getSelectedItem();
        if (selectedModel != null) {
            settingsService.getSettings().setGeminiModel(selectedModel.apiName());
        }
        settingsService.getSettings().setMaxDiscoveryTurns((Integer) maxDiscoveryTurnsSpinner.getValue());
        settingsService.saveSettings();
        if (selectedModel != null) {
            statusLabel.setText("Active model switched to: " + selectedModel.name());
        } else {
            statusLabel.setText("Settings saved!");
        }
        statusTimer.restart();
    }

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
        }
    }
}