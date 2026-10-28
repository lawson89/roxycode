package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.GeminiModelConfig;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.util.Edt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Lazy
public final class SettingsPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(SettingsPanel.class);
    private static final String MIG_WRAP = "wrap";
    static final String RESTART_LEGEND_TEXT = "(*) Restart required for Gemini API Key and Max Chat History Messages";
    static final String SAVED_RESTART_REQUIRED_TEXT = "Settings saved. Please restart RoxyCode for API Key / Chat History changes to take effect.";
    static final String SAVED_APPLIED_TEXT = "Settings saved and applied.";

    private final transient SettingsService settingsService;
    private final JComboBox<String> themeCombo;
    private final JComboBox<GeminiModelConfig> modelCombo;
    private final JSpinner maxMessagesSpinner;
    private final JPasswordField geminiApiKeyField;
    private final JSpinner maxAgentToolTurnsSpinner;
    private final JButton saveButton;
    private final JLabel legendLabel;
    private final JLabel statusLabel;
    private final Timer statusTimer;
    private boolean updatingFromSettings = false;

    public SettingsPanel(SettingsService settingsService) {
        this.settingsService = settingsService;
        setLayout(new MigLayout("fillx, insets 20", "[left][fill, grow]", "[]20[]"));
        setOpaque(true);

        JLabel title = new JLabel("Settings");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        add(title, "span, gapbottom 15, " + MIG_WRAP);

        add(new JLabel("Theme:"));
        themeCombo = new JComboBox<>(
                new String[]{"FlatLaf Light", "FlatLaf Dark", "IntelliJ", "Darcula", "macOS Light", "macOS Dark"});
        themeCombo.setSelectedItem(settingsService.getSettings().getTheme());
        themeCombo.addActionListener(e -> updateTheme());
        add(themeCombo, MIG_WRAP);

        add(new JLabel("Gemini Model:"));
        modelCombo = new JComboBox<>();
        loadModels();
        add(modelCombo, MIG_WRAP);

        add(new JLabel("Gemini API Key (*):"));
        geminiApiKeyField = new JPasswordField(20);
        geminiApiKeyField.setText(settingsService.getSettings().getGeminiApiKey());
        add(geminiApiKeyField, MIG_WRAP);

        add(new JLabel("Max Agent Tool Turns:"));
        maxAgentToolTurnsSpinner = new JSpinner(
                new SpinnerNumberModel(settingsService.getSettings().getMaxAgentToolTurns(), 1, 50, 1));
        add(maxAgentToolTurnsSpinner, MIG_WRAP);

        add(new JLabel("Max Chat History Messages (*):"));
        maxMessagesSpinner = new JSpinner(
                new SpinnerNumberModel(settingsService.getSettings().getMaxChatMemoryMessages(), 1, 500, 5));
        add(maxMessagesSpinner, MIG_WRAP);

        legendLabel = new JLabel(RESTART_LEGEND_TEXT);
        legendLabel.setFont(legendLabel.getFont().deriveFont(Font.ITALIC, 11f));
        add(legendLabel, "span, gaptop 5, " + MIG_WRAP);

        saveButton = new JButton("Save Settings");
        saveButton.addActionListener(e -> saveSettings());
        add(saveButton, "span, split 2, gaptop 15");

        statusLabel = new JLabel("");
        statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Actions.Green");
        add(statusLabel, "gapleft 10");

        statusTimer = new Timer(3000, e -> statusLabel.setText(""));
        statusTimer.setRepeats(false);
    }

    private void loadModels() {
        List<GeminiModelConfig> modelList = settingsService.getAvailableModels();
        if (modelList == null)
            modelList = List.of();
        modelCombo.setModel(new DefaultComboBoxModel<>(modelList.toArray(new GeminiModelConfig[0])));

        String currentModelId = settingsService.getSettings().getGeminiModel();
        for (int i = 0; i < modelCombo.getItemCount(); i++) {
            if (modelCombo.getItemAt(i).apiName().equals(currentModelId)) {
                modelCombo.setSelectedIndex(i);
                break;
            }
        }
    }

    @EventListener
    @SuppressWarnings("PMD.UnusedAssignment")
    public void onSettingsChanged(SettingsChangedEvent event) {
        if (event == null || event.settings() == null)
            return;
        Edt.run(() -> {
            String theme = event.settings().getTheme();
            if (theme != null && !theme.equals(themeCombo.getSelectedItem())) {
                updatingFromSettings = true;
                try {
                    themeCombo.setSelectedItem(theme);
                } finally {
                    updatingFromSettings = false;
                }
            }
        });
    }

    private void updateTheme() {
        if (updatingFromSettings)
            return;
        String selectedTheme = (String) themeCombo.getSelectedItem();
        if (selectedTheme != null) {
            settingsService.getSettings().setTheme(selectedTheme);
            applyTheme(selectedTheme);
        }
    }

    private void saveSettings() {
        AppSettings settings = settingsService.getSettings();
        String oldApiKey = settings.getGeminiApiKey() != null ? settings.getGeminiApiKey() : "";
        String newApiKey = new String(geminiApiKeyField.getPassword());
        int oldMaxMessages = settings.getMaxChatMemoryMessages();
        int newMaxMessages = (Integer) maxMessagesSpinner.getValue();
        boolean restartRequiredChanged = !oldApiKey.equals(newApiKey) || oldMaxMessages != newMaxMessages;

        settings.setGeminiApiKey(newApiKey);
        GeminiModelConfig selectedModel = (GeminiModelConfig) modelCombo.getSelectedItem();
        if (selectedModel != null)
            settings.setGeminiModel(selectedModel.apiName());
        settings.setMaxAgentToolTurns((Integer) maxAgentToolTurnsSpinner.getValue());
        settings.setMaxChatMemoryMessages(newMaxMessages);
        settingsService.saveSettings();

        statusLabel.setText(restartRequiredChanged ? SAVED_RESTART_REQUIRED_TEXT : SAVED_APPLIED_TEXT);
        statusTimer.restart();
    }

    JLabel getLegendLabel() {
        return legendLabel;
    }

    JLabel getStatusLabel() {
        return statusLabel;
    }

    Timer getStatusTimer() {
        return statusTimer;
    }

    JSpinner getMaxMessagesSpinner() {
        return maxMessagesSpinner;
    }

    JSpinner getMaxAgentToolTurnsSpinner() {
        return maxAgentToolTurnsSpinner;
    }

    JComboBox<String> getThemeCombo() {
        return themeCombo;
    }

    public static boolean isDarkTheme(String themeName) {
        if (themeName != null && !themeName.isBlank()) {
            String lower = themeName.toLowerCase(Locale.ROOT);
            if (lower.contains("dark") || "darcula".equals(lower))
                return true;
            if (lower.contains("light") || "intellij".equals(lower))
                return false;
        }
        return FlatLaf.isLafDark();
    }

    public static void applyRSyntaxTheme(RSyntaxTextArea textArea, SettingsService settingsService) {
        applyRSyntaxTheme(textArea,
                settingsService != null && settingsService.getSettings() != null
                        ? settingsService.getSettings().getTheme()
                        : null);
    }

    private static volatile Theme cachedDarkRSyntaxTheme;
    private static volatile Theme cachedLightRSyntaxTheme;

    static Theme getRSyntaxTheme(boolean dark) throws IOException {
        Theme cached = dark ? cachedDarkRSyntaxTheme : cachedLightRSyntaxTheme;
        if (cached != null)
            return cached;
        synchronized (SettingsPanel.class) {
            cached = dark ? cachedDarkRSyntaxTheme : cachedLightRSyntaxTheme;
            if (cached != null)
                return cached;
            String themePath = dark
                    ? "/org/fife/ui/rsyntaxtextarea/themes/dark.xml"
                    : "/org/fife/ui/rsyntaxtextarea/themes/default.xml";
            try (InputStream in = SettingsPanel.class.getResourceAsStream(themePath)) {
                Theme loaded = Theme.load(in);
                if (dark)
                    cachedDarkRSyntaxTheme = loaded;
                else
                    cachedLightRSyntaxTheme = loaded;
                return loaded;
            }
        }
    }

    public static void applyRSyntaxTheme(RSyntaxTextArea textArea, String themeName) {
        if (textArea == null)
            return;
        try {
            getRSyntaxTheme(isDarkTheme(themeName)).apply(textArea);
        } catch (IOException ioe) {
            log.error("Failed to load RSyntaxTextArea theme: {}", ioe.getMessage());
        }
    }

    public static void applyTheme(String themeName) {
        Edt.runAndWait(() -> {
            try {
                UIManager.put("TabbedPane.tabType", "card");
                UIManager.put("TabbedPane.showTabSeparators", true);
                UIManager.setLookAndFeel(switch (themeName != null ? themeName : "") {
                    case "FlatLaf Dark" -> new FlatDarkLaf();
                    case "IntelliJ" -> new FlatIntelliJLaf();
                    case "Darcula" -> new FlatDarculaLaf();
                    case "macOS Light" -> new FlatMacLightLaf();
                    case "macOS Dark" -> new FlatMacDarkLaf();
                    default -> new FlatLightLaf();
                });
                FlatLaf.updateUI();
            } catch (Exception ex) {
                log.error("Failed to apply theme {}: {}", themeName, ex.getMessage());
            }
        });
    }
}
