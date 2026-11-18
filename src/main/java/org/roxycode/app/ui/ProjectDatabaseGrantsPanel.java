package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Font;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.Timer;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.roxycode.app.model.DatabaseConnectionConfig;
import org.roxycode.app.model.ProjectDatabaseGrant;
import org.roxycode.app.service.DatabaseConfigService;
import org.roxycode.app.util.Edt;
import org.springframework.lang.Nullable;

/**
 * Per-project read-only database grants tab panel hosted inside {@link BuildConfigPanel}.
 */
final class ProjectDatabaseGrantsPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final String GROW_FILL = "[grow, fill]";
    private static final String GROWX_WMIN_0 = "growx, wmin 0";
    private static final String SAVE_BUTTON_BASE_STYLE = "font: bold; arc: 8; ";

    private final transient @Nullable DatabaseConfigService databaseConfigService;
    private final transient IntConsumer grantedCountListener;
    private final transient @Nullable java.util.function.Consumer<CompletableFuture<?>> asyncFutureTracker;
    private transient Runnable onManageGlobalDatabasesCallback;

    final JPanel databasesCardPanel;
    final JPanel grantRowsContainer;
    final JButton manageGlobalDatabasesButton;
    final JButton saveGrantsButton;
    final JLabel grantsStatusLabel;
    final Timer grantsStatusTimer;
    final List<GrantRowControls> grantRowControls = new ArrayList<>();
    transient CompletableFuture<?> lastAsyncFuture = CompletableFuture.completedFuture(null);

    record GrantDataSnapshot(List<DatabaseConnectionConfig> globals, List<ProjectDatabaseGrant> grants) {
    }

    static final class GrantRowControls {
        final DatabaseConnectionConfig config;
        final JCheckBox grantCheckBox;
        final JTextField schemaOverrideField;
        final JTextField notesField;

        GrantRowControls(DatabaseConnectionConfig config, JCheckBox grantCheckBox, JTextField schemaOverrideField,
                JTextField notesField) {
            this.config = config;
            this.grantCheckBox = grantCheckBox;
            this.schemaOverrideField = schemaOverrideField;
            this.notesField = notesField;
        }
    }

    ProjectDatabaseGrantsPanel(@Nullable DatabaseConfigService databaseConfigService, IntConsumer grantedCountListener,
            @Nullable java.util.function.Consumer<CompletableFuture<?>> asyncFutureTracker) {
        super(new MigLayout("fill, insets 20, hidemode 3", GROW_FILL, "[][]" + GROW_FILL));
        this.databaseConfigService = databaseConfigService;
        this.grantedCountListener = grantedCountListener;
        this.asyncFutureTracker = asyncFutureTracker;
        setOpaque(true);

        JLabel dbTitleLabel = new JLabel("Project Database Grants");
        JLabel dbSubtitleLabel = new JLabel(
                "Select global read-only databases to expose to RoxyCode (dbService) for the active project.");
        manageGlobalDatabasesButton = UiComponents.createActionButton("Manage Global Databases...",
                Codicons.SETTINGS_GEAR, new Insets(6, 12, 6, 12), false, "arc: 8", e -> {
                    if (onManageGlobalDatabasesCallback != null) {
                        onManageGlobalDatabasesCallback.run();
                    }
                });
        saveGrantsButton = UiComponents.createActionButton("Save Database Grants", Codicons.SAVE,
                new Insets(6, 14, 6, 14), false, SAVE_BUTTON_BASE_STYLE + UiComponents.ACCENT_BUTTON_STYLE,
                e -> saveDatabaseGrants());
        grantsStatusLabel = new JLabel("");
        grantsStatusLabel.setFont(grantsStatusLabel.getFont().deriveFont(Font.BOLD, 11.5f));
        grantsStatusLabel.setVisible(false);
        grantsStatusTimer = UiComponents.createStatusTimer(grantsStatusLabel, 3500);

        JPanel dbActionPanel = new JPanel(new MigLayout("insets 0", "[]8[]", "[center]"));
        dbActionPanel.setOpaque(false);
        dbActionPanel.add(manageGlobalDatabasesButton);
        dbActionPanel.add(saveGrantsButton);
        add(UiComponents.createPageHeader(dbTitleLabel, dbSubtitleLabel, dbActionPanel), GROWX_WMIN_0 + ", wrap");
        add(grantsStatusLabel, "growx, wmin 0, hidemode 3, wrap");

        databasesCardPanel = new JPanel(new MigLayout("fillx, wrap 1, insets 16, gapy 10", GROW_FILL, ""));
        UiComponents.styleCardPanel(databasesCardPanel);
        grantRowsContainer = new JPanel(new MigLayout("fillx, wrap 1, insets 0, gapy 8", GROW_FILL, ""));
        grantRowsContainer.setOpaque(false);
        databasesCardPanel.add(grantRowsContainer, GROWX_WMIN_0);

        JPanel dbCardsScroller = UiComponents
                .createScrollableCardsPanel(new MigLayout("fillx, wrap 1, insets 4 2 16 6, gapy 14", GROW_FILL, ""));
        dbCardsScroller.add(databasesCardPanel, GROWX_WMIN_0);
        add(UiComponents.createBorderlessScrollPane(dbCardsScroller), "grow, wmin 0");
    }

    void setOnManageGlobalDatabasesCallback(@Nullable Runnable callback) {
        this.onManageGlobalDatabasesCallback = callback;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (databasesCardPanel != null) {
            UiComponents.styleCardPanel(databasesCardPanel);
        }
    }

    CompletableFuture<GrantDataSnapshot> loadDatabaseGrants() {
        if (databaseConfigService == null) {
            notifyGrantedCount(0);
            return CompletableFuture.completedFuture(new GrantDataSnapshot(List.of(), List.of()));
        }
        CompletableFuture<GrantDataSnapshot> future = Edt
                .supplyAsync(() -> new GrantDataSnapshot(databaseConfigService.listGlobalConnections(),
                        databaseConfigService.listProjectGrants()), this::applyDatabaseGrantSnapshot);
        trackAsyncFuture(future);
        return future;
    }

    private void applyDatabaseGrantSnapshot(GrantDataSnapshot snapshot) {
        grantRowsContainer.removeAll();
        grantRowControls.clear();
        if (snapshot.globals().isEmpty()) {
            JLabel emptyLabel = new JLabel(
                    "No global read-only databases configured yet. Click 'Manage Global Databases...' to add one.");
            emptyLabel.setForeground(SwingHtmlUtils.getMutedForegroundColor());
            grantRowsContainer.add(emptyLabel, GROWX_WMIN_0);
            notifyGrantedCount(0);
            grantRowsContainer.revalidate();
            grantRowsContainer.repaint();
            return;
        }
        Map<String, ProjectDatabaseGrant> grantById = new LinkedHashMap<>();
        for (ProjectDatabaseGrant grant : snapshot.grants()) {
            grantById.put(grant.connectionId(), grant);
        }
        int grantedCount = 0;
        for (DatabaseConnectionConfig cfg : snapshot.globals()) {
            ProjectDatabaseGrant existing = grantById.get(cfg.id());
            boolean granted = existing != null;
            if (granted) {
                grantedCount++;
            }
            JPanel rowCard = new JPanel(
                    new MigLayout("fillx, insets 10 12 10 12, wrap 2", "[140!, right]10[grow, fill]", ""));
            UiComponents.styleCardPanel(rowCard);

            JCheckBox checkBox = new JCheckBox(cfg.displayName(), granted);
            checkBox.setFont(checkBox.getFont().deriveFont(Font.BOLD, 12.5f));
            JLabel metaBadge = new JLabel(cfg.id() + "  •  " + cfg.resolveType().getDisplayName());
            UiComponents.styleCodePillBadge(metaBadge);

            JPanel topRow = new JPanel(new MigLayout("insets 0, fillx", "[grow, fill]push[]", "[center]"));
            topRow.setOpaque(false);
            topRow.add(checkBox);
            topRow.add(metaBadge);
            rowCard.add(topRow, "span 2, growx, wrap");
            if (!cfg.description().isBlank()) {
                JLabel descriptionLabel = new JLabel(UiComponents.formatWrappingHtml(cfg.description()));
                descriptionLabel.setFont(descriptionLabel.getFont().deriveFont(Font.PLAIN, 11.5f));
                descriptionLabel.setForeground(SwingHtmlUtils.getMutedForegroundColor());
                rowCard.add(descriptionLabel, "span 2, growx, gapbottom 4, wrap");
            }

            JTextField schemaField = new JTextField();
            if (existing != null) {
                schemaField.setText(existing.defaultSchema());
            }
            String schemaPlaceholder = "Optional schema override";
            if (!cfg.defaultSchema().isBlank()) {
                schemaPlaceholder = "Default: " + cfg.defaultSchema();
            }
            schemaField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, schemaPlaceholder);

            JTextField notesField = new JTextField();
            if (existing != null) {
                notesField.setText(existing.notes());
            }
            notesField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                    "Optional project domain notes (injected into ## CONFIGURED DATABASES)");

            rowCard.add(new JLabel("Schema Override:"));
            rowCard.add(schemaField, GROWX_WMIN_0);
            rowCard.add(new JLabel("Context Notes:"));
            rowCard.add(notesField, GROWX_WMIN_0);

            schemaField.setEnabled(granted);
            notesField.setEnabled(granted);
            checkBox.addActionListener(e -> {
                boolean selected = checkBox.isSelected();
                schemaField.setEnabled(selected);
                notesField.setEnabled(selected);
            });

            grantRowControls.add(new GrantRowControls(cfg, checkBox, schemaField, notesField));
            grantRowsContainer.add(rowCard, GROWX_WMIN_0);
        }
        notifyGrantedCount(grantedCount);
        grantRowsContainer.revalidate();
        grantRowsContainer.repaint();
    }

    CompletableFuture<List<ProjectDatabaseGrant>> saveDatabaseGrants() {
        if (databaseConfigService == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<ProjectDatabaseGrant> selectedGrants = new ArrayList<>();
        for (GrantRowControls row : grantRowControls) {
            if (row.grantCheckBox.isSelected()) {
                selectedGrants.add(new ProjectDatabaseGrant(row.config.id(), row.schemaOverrideField.getText(),
                        row.notesField.getText()));
            }
        }
        saveGrantsButton.setEnabled(false);
        CompletableFuture<List<ProjectDatabaseGrant>> future = Edt
                .supplyAsync(() -> databaseConfigService.saveProjectGrants(selectedGrants), saved -> {
                    saveGrantsButton.setEnabled(true);
                    notifyGrantedCount(saved.size());
                    UiComponents.showStatusMessage(grantsStatusLabel, grantsStatusTimer,
                            "Saved " + saved.size() + " database grant(s) for active project.", true);
                }, error -> {
                    saveGrantsButton.setEnabled(true);
                    UiComponents.showStatusMessage(grantsStatusLabel, grantsStatusTimer,
                            "Failed to save database grants: " + error.getMessage(), false);
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

    private void notifyGrantedCount(int grantedCount) {
        if (grantedCountListener != null) {
            grantedCountListener.accept(grantedCount);
        }
    }
}
