package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.model.AgentProfile;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.util.Edt;
import org.roxycode.app.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

/**
 * Project-level AI Model Override tab panel hosted inside {@link BuildConfigPanel}.
 */
final class ProjectModelOverridePanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(ProjectModelOverridePanel.class);
    private static final String GROW_FILL = "[grow, fill]";
    private static final String GROWX_WMIN_0 = "growx, wmin 0";
    private static final String WMIN_0 = "wmin 0";
    private static final String ROW_CENTER = "[center]";
    private static final String HEADER_FONT_STYLE = "font: bold +2";
    private static final String SAVE_BUTTON_BASE_STYLE = "font: bold; arc: 8; ";
    private static final String BORDER_BG_STYLE = ",1,10; background: ";

    private final transient SettingsService settingsService;
    private final transient ProjectService projectService;
    private final transient UiComponents.UiGuard updateGuard = new UiComponents.UiGuard();
    private final transient Consumer<String> tabTitleUpdater;
    private final transient @Nullable Consumer<CompletableFuture<?>> asyncFutureTracker;

    final JLabel modelTitleLabel;
    final JLabel modelSubtitleLabel;
    final JPanel modelCardPanel;
    final JLabel modelSectionHeaderLabel;
    final JLabel modelOverrideStatusPill;
    final JLabel profileInfoIcon;
    final GlobalDefaultCard globalDefaultProfileCard;
    final JLabel overrideSubheaderLabel;
    final Map<AgentProfile, UiComponents.AgentProfileCard> profileCards = new EnumMap<>(AgentProfile.class);
    final JButton modelSaveButton;
    final JLabel modelStatusLabel;
    final Timer modelStatusTimer;
    final Timer modelSaveFeedbackTimer;

    private String savedProfileId = "";
    String selectedProfileId = "";
    transient CompletableFuture<?> lastAsyncFuture = CompletableFuture.completedFuture(null);

    ProjectModelOverridePanel(SettingsService settingsService, ProjectService projectService,
            Consumer<String> tabTitleUpdater, @Nullable Consumer<CompletableFuture<?>> asyncFutureTracker) {
        super(new MigLayout("fill, insets 20, hidemode 3", GROW_FILL, "[]14" + GROW_FILL));
        this.settingsService = settingsService;
        this.projectService = projectService;
        this.tabTitleUpdater = tabTitleUpdater;
        this.asyncFutureTracker = asyncFutureTracker;
        setOpaque(true);

        modelTitleLabel = new JLabel(BuildConfigPanel.MODEL_TITLE_TEXT);
        modelSubtitleLabel = new JLabel(BuildConfigPanel.MODEL_SUBTITLE_TEXT);
        modelOverrideStatusPill = new JLabel();
        modelStatusLabel = new JLabel("");
        modelStatusLabel.setFont(modelStatusLabel.getFont().deriveFont(Font.BOLD, 11.5f));
        modelStatusTimer = UiComponents.createStatusTimer(modelStatusLabel, 3000);

        modelSaveButton = UiComponents.createActionButton(BuildConfigPanel.MODEL_SAVE_BUTTON_TEXT, Codicons.SAVE,
                new Insets(6, 14, 6, 14), false, SAVE_BUTTON_BASE_STYLE + UiComponents.ACCENT_BUTTON_STYLE,
                e -> saveModelConfig());
        modelSaveButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        modelSaveButton.setIconTextGap(6);

        modelSaveFeedbackTimer = new Timer(2500, null);
        modelSaveFeedbackTimer.setRepeats(false);
        modelSaveFeedbackTimer.addActionListener(e -> {
            modelSaveFeedbackTimer.stop();
            updateModelSaveButtonState();
        });

        JPanel modelHeaderActions = new JPanel(new MigLayout("insets 0, gapx 10", "[][][]", ROW_CENTER));
        modelHeaderActions.setOpaque(false);
        modelHeaderActions.add(modelOverrideStatusPill);
        modelHeaderActions.add(modelStatusLabel);
        modelHeaderActions.add(modelSaveButton);

        add(UiComponents.createPageHeader(modelTitleLabel, modelSubtitleLabel, modelHeaderActions),
                "growx, wmin 0, wrap");

        modelCardPanel = new JPanel(new MigLayout("fillx, wrap 1, insets 16 18 16 18, gapy 10", GROW_FILL, ""));
        UiComponents.styleCardPanel(modelCardPanel);

        modelSectionHeaderLabel = UiComponents.createSectionHeader(BuildConfigPanel.SECTION_AI_MODEL_TITLE,
                Codicons.HUBOT, HEADER_FONT_STYLE);
        profileInfoIcon = UiComponents.createInfoIcon(UiComponents.AGENT_PROFILE_TOOLTIP_HTML);
        JPanel modelSectionRow = new JPanel(new MigLayout("insets 0, gapx 6", "[][]", ROW_CENTER));
        modelSectionRow.setOpaque(false);
        modelSectionRow.add(modelSectionHeaderLabel);
        modelSectionRow.add(profileInfoIcon);
        modelCardPanel.add(modelSectionRow, "growx, wmin 0, gapbottom 2");

        globalDefaultProfileCard = new GlobalDefaultCard();
        modelCardPanel.add(globalDefaultProfileCard, GROWX_WMIN_0);

        overrideSubheaderLabel = new JLabel("OR OVERRIDE SPECIFICALLY FOR THIS PROJECT:");
        overrideSubheaderLabel.putClientProperty(FlatClientProperties.STYLE,
                "font: bold -2; foreground: $Label.disabledForeground");
        modelCardPanel.add(overrideSubheaderLabel, "growx, wmin 0, gaptop 4");

        JPanel profileGrid = UiComponents.createAgentProfileGrid(profileCards,
                selected -> selectProfileOverride(selected.getId()));
        modelCardPanel.add(profileGrid, GROWX_WMIN_0);

        JPanel modelCardsContainer = UiComponents
                .createScrollableCardsPanel(new MigLayout("fillx, wrap 1, insets 4 2 16 6, gapy 14", GROW_FILL, ""));
        modelCardsContainer.add(modelCardPanel, GROWX_WMIN_0);
        add(UiComponents.createBorderlessScrollPane(modelCardsContainer), "grow, wmin 0");

        applyThemeStyling();
    }

    void applyThemeStyling() {
        if (modelCardPanel != null) {
            UiComponents.styleCardPanel(modelCardPanel);
        }
        updateModelSaveButtonState();
        updateModelOverrideIndicators();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (modelCardPanel != null) {
            applyThemeStyling();
        }
    }

    void loadProfileOptions() {
        AgentProfile globalProfile = SettingsService.resolveGlobalAgentProfile(settingsService);
        String projectProfile = "";
        if (projectService != null && projectService.hasActiveProject()) {
            projectProfile = StringUtils.defaultString(projectService.getProjectAgentProfile());
        }
        final String targetProfile = normalizeProfileId(projectProfile);
        updateGuard.run(() -> {
            selectProfileOverride(targetProfile);
            savedProfileId = selectedProfileId;
        });
        globalDefaultProfileCard.updateGlobalProfileDisplay(globalProfile);
        updateModelSaveButtonState();
    }

    void selectProfileOverride(@Nullable String profileId) {
        String normalized = normalizeProfileId(profileId);
        selectedProfileId = normalized;
        globalDefaultProfileCard.setSelected(normalized.isEmpty());
        for (Map.Entry<AgentProfile, UiComponents.AgentProfileCard> entry : profileCards.entrySet()) {
            boolean matches = entry.getKey().getId().equalsIgnoreCase(normalized);
            entry.getValue().setSelected(matches);
        }
        updateModelOverrideIndicators();
        if (!updateGuard.isActive()) {
            UiComponents.stopTimer(modelSaveFeedbackTimer);
            clearModelStatus();
            updateModelSaveButtonState();
        }
    }

    private static String normalizeProfileId(@Nullable String profileId) {
        String trimmed = StringUtils.trimToEmpty(profileId);
        if (trimmed.isEmpty()) {
            return "";
        }
        return AgentProfile.fromString(trimmed).getId();
    }

    void updateModelOverrideIndicators() {
        AgentProfile globalProfile = SettingsService.resolveGlobalAgentProfile(settingsService);
        String pillText;
        String tabTitle;
        boolean overridden = StringUtils.isNotBlank(selectedProfileId);
        if (overridden) {
            AgentProfile overrideProfile = AgentProfile.fromString(selectedProfileId);
            pillText = "Override: " + overrideProfile.getDisplayName();
            tabTitle = BuildConfigPanel.TAB_AI_MODEL_TITLE + " (" + overrideProfile.getDisplayName() + ")";
        } else {
            pillText = "Global (" + globalProfile.getDisplayName() + ")";
            tabTitle = BuildConfigPanel.TAB_AI_MODEL_TITLE + " (Global)";
        }
        if (modelOverrideStatusPill != null) {
            modelOverrideStatusPill.setText(pillText);
            Color accentColor = SwingHtmlUtils.getAccentColor();
            Color mutedColor = SwingHtmlUtils.getMutedForegroundColor();
            String borderHex = UiComponents.getCardBorderHex();
            String pillBg = UiComponents.getPillBackgroundDirective();
            if (overridden) {
                String accentHex = SwingHtmlUtils.toHex(accentColor);
                modelOverrideStatusPill.setForeground(accentColor);
                modelOverrideStatusPill.setIcon(FontIcon.of(Codicons.HUBOT, 13, accentColor));
                modelOverrideStatusPill.putClientProperty(FlatClientProperties.STYLE,
                        "font: bold -1; arc: 10; border: 3,9,3,9," + accentHex + BORDER_BG_STYLE + "fade(" + accentHex
                                + ", 10%)");
            } else {
                modelOverrideStatusPill.setForeground(mutedColor);
                modelOverrideStatusPill.setIcon(FontIcon.of(Codicons.GLOBE, 13, mutedColor));
                modelOverrideStatusPill.putClientProperty(FlatClientProperties.STYLE,
                        "font: bold -1; arc: 10; border: 3,9,3,9," + borderHex + BORDER_BG_STYLE + pillBg);
            }
        }
        if (tabTitleUpdater != null) {
            tabTitleUpdater.accept(tabTitle);
        }
    }

    boolean hasUnsavedModelChanges() {
        return !Objects.equals(selectedProfileId, savedProfileId);
    }

    void updateModelSaveButtonState() {
        if (modelSaveButton == null) {
            return;
        }
        if (modelSaveFeedbackTimer != null && modelSaveFeedbackTimer.isRunning()) {
            BuildConfigPanel.applyButtonPresentation(modelSaveButton, BuildConfigPanel.MODEL_SAVED_FEEDBACK_BUTTON_TEXT,
                    Codicons.CHECK, SAVE_BUTTON_BASE_STYLE + UiComponents.GREEN_BUTTON_STYLE);
        } else {
            BuildConfigPanel.applyButtonPresentation(modelSaveButton, BuildConfigPanel.MODEL_SAVE_BUTTON_TEXT,
                    Codicons.SAVE, SAVE_BUTTON_BASE_STYLE + UiComponents.ACCENT_BUTTON_STYLE);
        }
    }

    void showModelStatus(String message, boolean success) {
        UiComponents.showStatusMessage(modelStatusLabel, modelStatusTimer, message, success);
    }

    void clearModelStatus() {
        UiComponents.clearStatusMessage(modelStatusLabel, modelStatusTimer, false);
    }

    CompletableFuture<Void> saveModelConfig() {
        UiComponents.stopTimer(modelSaveFeedbackTimer);
        if (projectService == null) {
            CompletableFuture<Void> done = CompletableFuture.completedFuture(null);
            trackAsyncFuture(done);
            return done;
        }
        final String profileOverrideToSave = selectedProfileId;
        modelSaveButton.setEnabled(false);
        CompletableFuture<Void> future = Edt.runAsync(() -> {
            if (projectService.hasActiveProject()) {
                projectService.setProjectAgentProfile(profileOverrideToSave);
            }
        }, () -> {
            modelSaveButton.setEnabled(true);
            savedProfileId = profileOverrideToSave;
            modelSaveFeedbackTimer.restart();
            updateModelSaveButtonState();
            showModelStatus(BuildConfigPanel.MODEL_SAVED_STATUS_TEXT, true);
        }, ex -> {
            modelSaveButton.setEnabled(true);
            updateModelSaveButtonState();
            Throwable cause = ExceptionUtils.getRootCause(ex);
            log.error("Failed to save project model override: {}", cause.getMessage());
            showModelStatus("Error saving model settings: " + cause.getMessage(), false);
        });
        trackAsyncFuture(future);
        return future;
    }

    private void trackAsyncFuture(CompletableFuture<?> future) {
        lastAsyncFuture = future;
        if (asyncFutureTracker != null) {
            asyncFutureTracker.accept(future);
        }
    }

    final class GlobalDefaultCard extends JPanel {
        private static final long serialVersionUID = 1L;

        final JLabel radioIconLabel;
        final JLabel cardTitleLabel;
        final JLabel currentGlobalBadge;
        final JLabel descriptionLabel;
        private boolean selected;

        GlobalDefaultCard() {
            super(new MigLayout("fillx, wrap 1, insets 12 14 12 14, gapy 4", GROW_FILL, ""));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setOpaque(true);

            JPanel headerRow = new JPanel(
                    new MigLayout("fillx, insets 0, gapx 6", "[][grow, fill][right]", ROW_CENTER));
            headerRow.setOpaque(false);

            radioIconLabel = new JLabel();
            headerRow.add(radioIconLabel);

            cardTitleLabel = new JLabel("Use Global Default Profile");
            cardTitleLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold +1");
            headerRow.add(cardTitleLabel, WMIN_0);

            currentGlobalBadge = new JLabel();
            headerRow.add(currentGlobalBadge, "right");
            add(headerRow, GROWX_WMIN_0);

            descriptionLabel = new JLabel(
                    "Inherits the Agent Profile configured in Global Settings (Settings & Tools).");
            add(descriptionLabel, GROWX_WMIN_0);

            MouseAdapter clickListener = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    selectProfileOverride("");
                }
            };
            addMouseListener(clickListener);
            headerRow.addMouseListener(clickListener);
            descriptionLabel.addMouseListener(clickListener);

            applyCardTheme();
        }

        void updateGlobalProfileDisplay(AgentProfile globalProfile) {
            currentGlobalBadge.setText(
                    "Currently: " + globalProfile.getDisplayName() + " (" + globalProfile.getBadgeSummary() + ")");
        }

        void setSelected(boolean selected) {
            this.selected = selected;
            applyCardTheme();
        }

        boolean isSelected() {
            return selected;
        }

        void applyCardTheme() {
            if (cardTitleLabel == null) {
                return;
            }
            UiComponents.applySelectableCardTheme(this, radioIconLabel, cardTitleLabel, descriptionLabel, selected);

            Color mutedColor = SwingHtmlUtils.getMutedForegroundColor();
            String borderHex = UiComponents.getCardBorderHex();
            String pillBg = UiComponents.getPillBackgroundDirective();
            currentGlobalBadge.setForeground(mutedColor);
            currentGlobalBadge.putClientProperty(FlatClientProperties.STYLE,
                    "font: $monospaced.font -1; arc: 6; border: 3,8,3,8," + borderHex + ",1,6; background: " + pillBg);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            applyCardTheme();
        }
    }
}
