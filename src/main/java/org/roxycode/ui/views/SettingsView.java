package org.roxycode.ui.views;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.httprpc.sierra.Outlet;
import org.httprpc.sierra.UILoader;
import org.roxycode.core.SettingsService;
import org.roxycode.core.config.GeminiModelRegistry;
import org.roxycode.ui.ThemeService;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;

@Singleton
public class SettingsView extends JPanel {

    private final SettingsService settingsService;

    private final GeminiModelRegistry geminiModelRegistry;

    private final ThemeService themeService;

    @Outlet
    private JComponent viewSettings;

    @Outlet
    private JPasswordField apiKeyField;

    @Outlet
    private JTextField maxTurnsField;

    @Outlet
    private JTextField historyWindowSize;

    @Outlet
    private JTextField logLinesCountField;

    @Outlet
    private JCheckBox logAutoScrollCheckBox;

    @Outlet
    private JButton saveSettingsButton;

    @Outlet
    private JComboBox<String> themeComboBox;

    @Outlet
    private JComboBox<String> modelComboBox;

    @Outlet
    private JLabel modelInputPriceLabel;

    @Outlet
    private JLabel modelOutputPriceLabel;

    @Outlet
    private JCheckBox cacheEnabledCheckBox;

    @Outlet
    private JTextField cacheTTLField;

    @Outlet
    private JTextField cacheMinSizeField;

    @Inject
    public SettingsView(SettingsService settingsService, GeminiModelRegistry geminiModelRegistry, ThemeService themeService) {
        this.settingsService = settingsService;
        this.geminiModelRegistry = geminiModelRegistry;
        this.themeService = themeService;
        setLayout(new BorderLayout());
    }

    @PostConstruct
    public void init() {
        add(UILoader.load(this, "SettingsView.xml"));
        initSettings();
        initListeners();
    }

    private void initSettings() {
        if (modelComboBox != null) {
            DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
            geminiModelRegistry.getAllModels().forEach(m -> model.addElement(m.getApiName()));
            modelComboBox.setModel(model);
        }
        if (themeComboBox != null) {
            themeComboBox.setModel(new DefaultComboBoxModel<>(new String[] { "Light", "Dark", "IntelliJ", "Darcula" }));
        }
        apiKeyField.setText(settingsService.getGeminiApiKey());
        maxTurnsField.setText(String.valueOf(settingsService.getMaxTurns()));
        logLinesCountField.setText(String.valueOf(settingsService.getLogLinesCount()));
        logAutoScrollCheckBox.setSelected(settingsService.isLogAutoScroll());
        themeComboBox.setSelectedItem(settingsService.getTheme());
        modelComboBox.setSelectedItem(settingsService.getGeminiModel());
        historyWindowSize.setText(String.valueOf(settingsService.getHistoryWindowSize()));
        cacheEnabledCheckBox.setSelected(settingsService.isCacheEnabled());
        cacheTTLField.setText(String.valueOf(settingsService.getCacheTTL()));
        cacheMinSizeField.setText(String.valueOf(settingsService.getCacheMinSize()));
        updateModelPricing();
    }

    private void initListeners() {
        if (saveSettingsButton != null) {
            saveSettingsButton.addActionListener(this::onSaveSettings);
        }
        if (modelComboBox != null) {
            modelComboBox.addActionListener(e -> updateModelPricing());
        }
    }

    private void updateModelPricing() {
        String selectedModel = (String) modelComboBox.getSelectedItem();
        if (selectedModel != null) {
            geminiModelRegistry.getModelByName(selectedModel).ifPresent(model -> {
                if (modelInputPriceLabel != null) {
                    modelInputPriceLabel.setText(String.format("$%.3f / 1M tokens", model.getInputPrice()));
                }
                if (modelOutputPriceLabel != null) {
                    modelOutputPriceLabel.setText(String.format("$%.3f / 1M tokens", model.getOutputPrice()));
                }
            });
        }
    }

    private void onSaveSettings(ActionEvent e) {
        settingsService.setGeminiApiKey(new String(apiKeyField.getPassword()));
        settingsService.setMaxTurns(Integer.parseInt(maxTurnsField.getText()));
        settingsService.setLogLinesCount(Integer.parseInt(logLinesCountField.getText()));
        settingsService.setLogAutoScroll(logAutoScrollCheckBox.isSelected());
        settingsService.setTheme((String) themeComboBox.getSelectedItem());
        settingsService.setGeminiModel((String) modelComboBox.getSelectedItem());
        settingsService.setHistoryWindowSize(Integer.parseInt(historyWindowSize.getText()));
        settingsService.setCacheEnabled(cacheEnabledCheckBox.isSelected());
        settingsService.setCacheTTL(Integer.parseInt(cacheTTLField.getText()));
        settingsService.setCacheMinSize(Integer.parseInt(cacheMinSizeField.getText()));
        // Find top-level window to update the UI tree
        Window window = SwingUtilities.getWindowAncestor(this);
        themeService.applyTheme(settingsService.getTheme(), window);
        JOptionPane.showMessageDialog(this, "Settings saved successfully.", "Success", JOptionPane.INFORMATION_MESSAGE);
    }
}
