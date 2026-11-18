package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.events.DatabaseConfigChangedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.model.AgentProfile;
import org.roxycode.app.model.ProjectDatabaseGrant;
import org.roxycode.app.service.DatabaseConfigService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.util.Edt;
import org.roxycode.app.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Component
@Lazy
final class BuildConfigPanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(BuildConfigPanel.class);
    static final int TAB_INDEX_BUILD_CONFIG = 0;
    static final int TAB_INDEX_AI_MODEL = 1;
    static final int TAB_INDEX_DATABASES = 2;
    static final String TAB_BUILD_CONFIG_TITLE = "Build Configuration";
    static final String TAB_AI_MODEL_TITLE = "AI Model Override";
    static final String TAB_DATABASES_TITLE = "Databases";
    static final String TITLE_TEXT = "Build Configuration (build.toml)";
    static final String SUBTITLE_TEXT = "Configure project-specific build, test, lint, and sandbox execution commands (build.toml).";
    static final String MODEL_TITLE_TEXT = "AI Model Override";
    static final String MODEL_SUBTITLE_TEXT = "Override the default Global Settings Agent Profile specifically for the active project.";
    static final String SECTION_AI_MODEL_TITLE = "AI Model Override";
    static final String COPY_TOML_TEXT = "Copy";
    static final String SAVE_BUTTON_TEXT = "Confirm & Save Settings";
    static final String SAVED_FEEDBACK_BUTTON_TEXT = "Confirmed & Saved!";
    static final String CONFIRMED_BUTTON_TEXT = "Settings Confirmed";
    static final String MODEL_SAVE_BUTTON_TEXT = "Save Model Settings";
    static final String MODEL_SAVED_FEEDBACK_BUTTON_TEXT = "Saved!";
    static final String MODEL_SAVED_STATUS_TEXT = "Model override settings saved!";
    private static final String SAVE_BUTTON_BASE_STYLE = "arc: 8; font: bold; ";
    private static final String GROW_FILL = "[grow, fill]";
    private static final String ROW_CENTER = "[center]";
    static final String UNCONFIRMED_BUILD_NOTICE_TEXT = "Build configuration was initialized from the auto-detected template. Please review the commands below and click 'Confirm & Save Settings'.";
    static final String BUILD_TOML_GUIDE_TOOLTIP_HTML = """
            <html><div style='width: 390px;'>
            <b>build.toml Quick Reference</b><br><br>
            &bull; <b><code>[commands]</code></b>: Shell commands for <code>install</code>, <code>clean</code>, <code>compile</code>, <code>build</code>, <code>test_all</code>, <code>test_single</code> (uses <code>{testName}</code> placeholder), <code>lint</code>, and <code>format</code>.<br>
            &bull; <b><code>[env]</code></b>: Key-value environment variables passed to all build processes (e.g., <code>JAVA_HOME</code>).<br>
            &bull; <b><code>[parse]</code></b>: <code>error_regex</code>, <code>test_failure_regex</code>, <code>context_lines</code>, and optional <code>junit_xml_paths</code> for structured failure extraction.<br>
            &bull; <b><code>[sandbox]</code></b>: <code>mode</code> (<code>"none"</code>, <code>"native"</code> for Linux <code>bwrap</code> / macOS <code>sandbox-exec</code>, or <code>"docker"</code>), <code>readable_paths</code>, <code>writable_paths</code>, <code>image</code>, and <code>workdir</code>.
            </div></html>""";

    private final transient GenericBuildToolService buildToolService;
    private final transient SettingsService settingsService;
    private final transient ProjectService projectService;
    private final transient @Nullable DatabaseConfigService databaseConfigService;
    final JTabbedPane settingsTabs;
    final JPanel buildTabPanel;
    final ProjectModelOverridePanel modelTabPanel;
    final ProjectDatabaseGrantsPanel databasesTabPanel;
    final JPanel grantRowsContainer;
    final JButton saveGrantsButton;
    final JLabel grantsStatusLabel;
    final List<ProjectDatabaseGrantsPanel.GrantRowControls> grantRowControls;
    final JLabel titleLabel;
    final JLabel subtitleLabel;
    final JPanel buildCardPanel;
    final ProjectModelOverridePanel.GlobalDefaultCard globalDefaultProfileCard;
    final Map<AgentProfile, UiComponents.AgentProfileCard> profileCards;
    final JButton modelSaveButton;
    final JLabel modelStatusLabel;
    final JLabel modelOverrideStatusPill;
    final JLabel profileInfoIcon;
    final Timer modelSaveFeedbackTimer;
    final JLabel buildTomlFileBadge;
    final JLabel buildConfigInfoIcon;
    final JButton copyTomlButton;
    final UiComponents.WarningBanner unconfirmedBanner;
    final RSyntaxTextArea textArea;
    final JButton saveButton;
    final JLabel statusLabel;
    final Timer statusTimer;
    final Timer saveFeedbackTimer;
    private Timer copyTomlTimer;

    private final transient UiComponents.UiGuard textUpdateGuard = new UiComponents.UiGuard();
    private boolean textEditedByUser = false;
    private boolean lastStatusSuccess = true;
    private String savedTomlContent = "";
    private transient Runnable onConfirmSavedCallback;
    transient CompletableFuture<?> lastAsyncFuture = CompletableFuture.completedFuture(null);

    public BuildConfigPanel(GenericBuildToolService buildToolService, SettingsService settingsService,
            @Nullable ProjectService projectService) {
        this(buildToolService, settingsService, projectService, null);
    }

    @Autowired
    public BuildConfigPanel(GenericBuildToolService buildToolService, SettingsService settingsService,
            @Nullable ProjectService projectService, @Nullable DatabaseConfigService databaseConfigService) {
        super(new BorderLayout());
        this.buildToolService = buildToolService;
        this.settingsService = settingsService;
        this.projectService = projectService;
        this.databaseConfigService = databaseConfigService;
        setOpaque(true);

        // --- Tab 1: Build Configuration (build.toml) ---
        buildTabPanel = new JPanel(new MigLayout("fill, insets 20, hidemode 3", GROW_FILL, "[]14" + GROW_FILL));
        buildTabPanel.setOpaque(true);

        titleLabel = new JLabel(TITLE_TEXT);
        subtitleLabel = new JLabel(SUBTITLE_TEXT);
        statusLabel = new JLabel("");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 11.5f));
        statusLabel.setVisible(false);

        saveButton = UiComponents.createActionButton(SAVE_BUTTON_TEXT, Codicons.SAVE, new Insets(6, 14, 6, 14), false,
                SAVE_BUTTON_BASE_STYLE + UiComponents.ACCENT_BUTTON_STYLE, e -> saveConfig());
        saveButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        saveButton.setIconTextGap(6);

        saveFeedbackTimer = createFeedbackTimer(this::updateSaveButtonState);

        buildTabPanel.add(UiComponents.createPageHeader(titleLabel, subtitleLabel, saveButton), "growx, wmin 0, wrap");

        buildCardPanel = new JPanel(
                new MigLayout("fill, insets 14 18 16 18, hidemode 3", GROW_FILL, "[][]" + GROW_FILL));
        UiComponents.styleCardPanel(buildCardPanel);

        buildTomlFileBadge = new JLabel("build.toml");
        UiComponents.styleCodePillBadge(buildTomlFileBadge);
        buildConfigInfoIcon = UiComponents.createInfoIcon(BUILD_TOML_GUIDE_TOOLTIP_HTML);

        copyTomlButton = UiComponents.createActionButton(COPY_TOML_TEXT, Codicons.CLIPPY, new Insets(4, 10, 4, 10),
                false, "arc: 8; font: -1", e -> copyTomlToClipboard());
        copyTomlButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        copyTomlButton.setToolTipText("Copy build.toml content to clipboard");

        JPanel buildToolbarRow = new JPanel(
                new MigLayout("fillx, insets 0, gapx 6, hidemode 3", "[][][0:0, grow, right][]", ROW_CENTER));
        buildToolbarRow.setOpaque(false);
        buildToolbarRow.add(buildTomlFileBadge);
        buildToolbarRow.add(buildConfigInfoIcon, "gapright 6");
        buildToolbarRow.add(statusLabel, "cell 2 0, wmin 0, hidemode 3");
        buildToolbarRow.add(copyTomlButton, "cell 3 0");
        buildCardPanel.add(buildToolbarRow, "growx, wmin 0, wrap, gapbottom 8");
        unconfirmedBanner = new UiComponents.WarningBanner(UNCONFIRMED_BUILD_NOTICE_TEXT);
        buildCardPanel.add(unconfirmedBanner, "growx, wmin 0, wrap, gapbottom 8, hidemode 3");

        textArea = new RSyntaxTextArea();
        UiComponents.configureSyntaxTextArea(textArea, SyntaxConstants.SYNTAX_STYLE_INI, true, false);
        SwingHtmlUtils.addChangeListener(textArea, this::onTomlTextEdited);
        SwingHtmlUtils.applyRSyntaxTheme(textArea, settingsService);

        buildCardPanel.add(new RTextScrollPane(textArea), "grow, push");
        buildTabPanel.add(buildCardPanel, "grow, push, wmin 0");

        settingsTabs = UiComponents.createUnderlinedTabbedPane(null);

        // --- Tab 2: AI Model Override ---
        modelTabPanel = new ProjectModelOverridePanel(settingsService, projectService, title -> {
            if (settingsTabs.getTabCount() > TAB_INDEX_AI_MODEL) {
                settingsTabs.setTitleAt(TAB_INDEX_AI_MODEL, title);
            }
        }, this::setLastAsyncFuture);
        this.globalDefaultProfileCard = modelTabPanel.globalDefaultProfileCard;
        this.profileCards = modelTabPanel.profileCards;
        this.modelSaveButton = modelTabPanel.modelSaveButton;
        this.modelStatusLabel = modelTabPanel.modelStatusLabel;
        this.modelOverrideStatusPill = modelTabPanel.modelOverrideStatusPill;
        this.profileInfoIcon = modelTabPanel.profileInfoIcon;
        this.modelSaveFeedbackTimer = modelTabPanel.modelSaveFeedbackTimer;

        // --- Tab 3: Databases (Per-Project Grants) ---
        databasesTabPanel = new ProjectDatabaseGrantsPanel(databaseConfigService, this::updateDatabasesTabTitle,
                this::setLastAsyncFuture);
        this.grantRowsContainer = databasesTabPanel.grantRowsContainer;
        this.saveGrantsButton = databasesTabPanel.saveGrantsButton;
        this.grantsStatusLabel = databasesTabPanel.grantsStatusLabel;
        this.grantRowControls = databasesTabPanel.grantRowControls;

        settingsTabs.addTab(TAB_BUILD_CONFIG_TITLE, FontIcon.of(Codicons.PACKAGE, 15, SwingHtmlUtils.getAccentColor()),
                buildTabPanel);
        settingsTabs.addTab(TAB_AI_MODEL_TITLE,
                FontIcon.of(Codicons.HUBOT, 15, SwingHtmlUtils.getMutedForegroundColor()), modelTabPanel);
        settingsTabs.addTab(TAB_DATABASES_TITLE,
                FontIcon.of(Codicons.DATABASE, 15, SwingHtmlUtils.getMutedForegroundColor()), databasesTabPanel);

        loadProfileOptions();

        add(settingsTabs, BorderLayout.CENTER);

        statusTimer = new Timer(3000, e -> clearBuildStatus());
        statusTimer.setRepeats(false);

        applySectionStyling();
        lastAsyncFuture = CompletableFuture.allOf(loadConfig(), loadDatabaseGrants());
        updateBannerState();
    }

    private void copyTomlToClipboard() {
        String content = "";
        if (textArea != null) {
            content = StringUtils.defaultString(textArea.getText());
        }
        copyTomlTimer = UiComponents.copyWithFeedback(copyTomlButton, copyTomlTimer, content, COPY_TOML_TEXT, 2000);
    }

    void selectBuildConfigTab() {
        UiComponents.selectTabSafely(settingsTabs, TAB_INDEX_BUILD_CONFIG);
    }

    void selectAiModelTab() {
        UiComponents.selectTabSafely(settingsTabs, TAB_INDEX_AI_MODEL);
    }

    void selectDatabasesTab() {
        UiComponents.selectTabSafely(settingsTabs, TAB_INDEX_DATABASES);
        loadDatabaseGrants();
    }

    private void setEditorContentProgrammatically(String content, boolean updateSavedBaseline) {
        String normalizedContent = StringUtils.defaultString(content);
        textUpdateGuard.run(() -> {
            textArea.setText(normalizedContent);
            textArea.setCaretPosition(0);
            if (updateSavedBaseline) {
                savedTomlContent = normalizedContent;
                textEditedByUser = false;
            } else {
                textEditedByUser = !Objects.equals(normalizedContent, savedTomlContent);
            }
        });
        updateBannerState();
    }

    void showBuildStatus(String message, boolean success) {
        lastStatusSuccess = success;
        statusLabel.setVisible(true);
        UiComponents.showStatusMessage(statusLabel, statusTimer, message, success);
        statusLabel.setToolTipText(message);
    }

    void clearBuildStatus() {
        UiComponents.clearStatusMessage(statusLabel, statusTimer, true);
    }

    void showModelStatus(String message, boolean success) {
        modelTabPanel.showModelStatus(message, success);
    }

    void clearModelStatus() {
        modelTabPanel.clearModelStatus();
    }

    void loadProfileOptions() {
        modelTabPanel.loadProfileOptions();
    }

    void selectProfileOverride(@Nullable String profileId) {
        modelTabPanel.selectProfileOverride(profileId);
    }

    boolean hasUnsavedChanges() {
        String currentText = "";
        if (textArea != null) {
            currentText = StringUtils.defaultString(textArea.getText());
        }
        return textEditedByUser || !Objects.equals(currentText, savedTomlContent);
    }

    boolean hasUnsavedModelChanges() {
        return modelTabPanel.hasUnsavedModelChanges();
    }

    private static Timer createFeedbackTimer(Runnable onExpire) {
        Timer timer = new Timer(2500, null);
        timer.setRepeats(false);
        timer.addActionListener(e -> {
            timer.stop();
            onExpire.run();
        });
        return timer;
    }

    void updateSaveButtonState() {
        if (saveButton == null) {
            return;
        }
        if (saveFeedbackTimer != null && saveFeedbackTimer.isRunning()) {
            applyButtonPresentation(saveButton, SAVED_FEEDBACK_BUTTON_TEXT, Codicons.CHECK,
                    SAVE_BUTTON_BASE_STYLE + UiComponents.GREEN_BUTTON_STYLE);
            return;
        }
        if (hasUnsavedChanges()) {
            applyButtonPresentation(saveButton, SAVE_BUTTON_TEXT, Codicons.SAVE,
                    SAVE_BUTTON_BASE_STYLE + UiComponents.ACCENT_BUTTON_STYLE);
            return;
        }
        boolean confirmed = projectService != null && projectService.hasActiveProject()
                && projectService.isBuildConfirmed();
        if (confirmed) {
            applyButtonPresentation(saveButton, CONFIRMED_BUTTON_TEXT, Codicons.CHECK,
                    SAVE_BUTTON_BASE_STYLE + UiComponents.SUBTLE_GREEN_BUTTON_STYLE);
        } else {
            applyButtonPresentation(saveButton, SAVE_BUTTON_TEXT, Codicons.SAVE,
                    SAVE_BUTTON_BASE_STYLE + UiComponents.ACCENT_BUTTON_STYLE);
        }
    }

    static void applyButtonPresentation(JButton button, String text, Codicons icon, String style) {
        button.setText(text);
        button.putClientProperty(FlatClientProperties.STYLE, style);
        button.setIcon(FontIcon.of(icon, 16, UiComponents.resolveActionButtonIconColor(style)));
    }

    private void applySectionStyling() {
        UiComponents.styleCardPanel(buildCardPanel);
        UiComponents.styleCodePillBadge(buildTomlFileBadge);
        updateSaveButtonState();
        if (modelTabPanel != null) {
            modelTabPanel.applyThemeStyling();
        }

        if (lastStatusSuccess) {
            statusLabel.setForeground(SwingHtmlUtils.getUiColor(new Color(0x28, 0xa7, 0x45), "Actions.Green"));
        } else {
            statusLabel.setForeground(SwingHtmlUtils.getUiColor(new Color(0xdc, 0x35, 0x45), "Actions.Red"));
        }
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        Edt.run(() -> {
            SwingHtmlUtils.applyRSyntaxTheme(textArea, settingsService);
            applySectionStyling();
            loadProfileOptions();
            updateBannerState();
        });
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        Edt.run(() -> {
            UiComponents.stopTimer(saveFeedbackTimer);
            UiComponents.stopTimer(modelTabPanel.modelSaveFeedbackTimer);
            clearBuildStatus();
            clearModelStatus();
            loadProfileOptions();
            loadConfig();
            loadDatabaseGrants();
            updateBannerState();
        });
    }

    @EventListener
    public void onDatabaseConfigChanged(DatabaseConfigChangedEvent event) {
        Edt.run(this::loadDatabaseGrants);
    }

    private void onTomlTextEdited() {
        if (!textUpdateGuard.isActive()) {
            String currentText = StringUtils.defaultString(textArea.getText());
            boolean wasEdited = textEditedByUser;
            textEditedByUser = !Objects.equals(currentText, savedTomlContent);
            UiComponents.stopTimer(saveFeedbackTimer);
            clearBuildStatus();
            if (textEditedByUser) {
                updateSaveButtonState();
            } else if (wasEdited) {
                updateBannerState();
            }

        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (buildCardPanel != null) {
            applySectionStyling();
        }
        if (unconfirmedBanner != null) {
            updateBannerState();
        }
    }

    void updateBannerState() {
        boolean showUnconfirmedNotice = projectService != null && projectService.hasActiveProject()
                && !projectService.isBuildConfirmed();
        unconfirmedBanner.setVisibleState(showUnconfirmedNotice, UNCONFIRMED_BUILD_NOTICE_TEXT);
        updateSaveButtonState();
    }

    CompletableFuture<String> loadConfig() {
        if (buildToolService == null) {
            CompletableFuture<String> done = CompletableFuture.completedFuture("");
            lastAsyncFuture = done;
            return done;
        }
        CompletableFuture<String> future = Edt.supplyAsync(() -> {
            try {
                return StringUtils.defaultString(buildToolService.getConfigContent());
            } catch (IOException e) {
                log.error("Failed to load build config", e);
                return "# Error loading config: " + e.getMessage();
            }
        }, content -> setEditorContentProgrammatically(content, true));
        lastAsyncFuture = future;
        return future;
    }

    CompletableFuture<Void> saveConfig() {
        UiComponents.stopTimer(saveFeedbackTimer);

        if (buildToolService == null && projectService == null) {
            CompletableFuture<Void> done = CompletableFuture.completedFuture(null);
            lastAsyncFuture = done;
            return done;
        }
        String contentToSave = StringUtils.defaultString(textArea.getText());
        final boolean wasBuildConfirmed = projectService != null && projectService.isBuildConfirmed();
        saveButton.setEnabled(false);
        CompletableFuture<Void> future = Edt.runAsync(() -> {
            try {
                if (buildToolService != null) {
                    buildToolService.saveConfigContent(contentToSave);
                }
                if (projectService != null && projectService.hasActiveProject()) {
                    projectService.setBuildConfirmed(true);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, () -> {
            saveButton.setEnabled(true);
            savedTomlContent = contentToSave;
            textEditedByUser = false;
            saveFeedbackTimer.restart();
            updateBannerState();

            showBuildStatus("Configuration saved successfully!", true);
            if (!wasBuildConfirmed && onConfirmSavedCallback != null) {
                onConfirmSavedCallback.run();
            }
        }, ex -> {
            saveButton.setEnabled(true);
            updateSaveButtonState();
            Throwable cause = ExceptionUtils.getRootCause(ex);
            log.error("Failed to save build config: {}", cause.getMessage());
            showBuildStatus("Error saving config: " + cause.getMessage(), false);
        });
        lastAsyncFuture = future;
        return future;
    }

    CompletableFuture<Void> saveModelConfig() {
        CompletableFuture<Void> future = modelTabPanel.saveModelConfig();
        lastAsyncFuture = future;
        return future;
    }

    private void setLastAsyncFuture(CompletableFuture<?> future) {
        this.lastAsyncFuture = future;
    }

    void setOnConfirmSavedCallback(Runnable callback) {
        this.onConfirmSavedCallback = callback;
    }

    void setOnManageGlobalDatabasesCallback(Runnable callback) {
        databasesTabPanel.setOnManageGlobalDatabasesCallback(callback);
    }

    CompletableFuture<ProjectDatabaseGrantsPanel.GrantDataSnapshot> loadDatabaseGrants() {
        CompletableFuture<ProjectDatabaseGrantsPanel.GrantDataSnapshot> future = databasesTabPanel.loadDatabaseGrants();
        lastAsyncFuture = future;
        return future;
    }

    CompletableFuture<List<ProjectDatabaseGrant>> saveDatabaseGrants() {
        CompletableFuture<List<ProjectDatabaseGrant>> future = databasesTabPanel.saveDatabaseGrants();
        lastAsyncFuture = future;
        return future;
    }

    private void updateDatabasesTabTitle(int grantedCount) {
        if (settingsTabs == null || settingsTabs.getTabCount() <= TAB_INDEX_DATABASES) {
            return;
        }
        if (grantedCount > 0) {
            settingsTabs.setTitleAt(TAB_INDEX_DATABASES, TAB_DATABASES_TITLE + " (" + grantedCount + " Granted)");
        } else {
            settingsTabs.setTitleAt(TAB_INDEX_DATABASES, TAB_DATABASES_TITLE);
        }
    }

}
