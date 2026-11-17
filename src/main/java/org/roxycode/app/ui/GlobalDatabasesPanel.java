package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import com.formdev.flatlaf.util.SystemFileChooser;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.DbService;
import org.roxycode.app.ai.services.DbTestResult;
import org.roxycode.app.events.DatabaseConfigChangedEvent;
import org.roxycode.app.model.CredentialSource;
import org.roxycode.app.model.DatabaseConnectionConfig;
import org.roxycode.app.model.DatabaseType;
import org.roxycode.app.service.CredentialStoreService;
import org.roxycode.app.service.DatabaseConfigService;
import org.roxycode.app.util.Edt;
import org.roxycode.app.util.ExceptionUtils;
import org.roxycode.app.util.PathUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;

/**
 * Global read-only database connection pool management panel in Settings & Tools.
 */
@org.springframework.stereotype.Component
@Lazy
final class GlobalDatabasesPanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final String TITLE_TEXT = "Global Read-Only Databases";
    private static final String SUBTITLE_TEXT = "Configure reusable read-only database connections stored in ~/.roxycode/databases.toml. Passwords stay in your OS keyring.";
    private static final String GROW_FILL = "[grow, fill]";
    private static final String GROWX_WMIN_0 = "growx, wmin 0";
    private static final String ARC_8_STYLE = "arc: 8";
    private static final String ROW_CENTER = "[center]";
    private static final String TEST_CONNECTION_TEXT = "Test Read-Only Connection";
    private static final String TESTING_CONNECTION_TEXT = "Testing...";

    private final transient DatabaseConfigService databaseConfigService;
    private final transient CredentialStoreService credentialStoreService;
    private final transient DbService dbService;
    private final transient UiComponents.UiGuard formGuard = new UiComponents.UiGuard();

    final JTextField filterField;
    final DefaultListModel<DatabaseConnectionConfig> listModel = new DefaultListModel<>();
    final JList<DatabaseConnectionConfig> connectionList = new JList<>(listModel);
    final JButton newButton;

    final JPanel masterCard;
    final JPanel detailCard;
    final JTextField displayNameField = new JTextField();
    final JTextField idField = new JTextField();
    final JTextField descriptionField = new JTextField();
    final JComboBox<DatabaseType> typeCombo = new JComboBox<>(DatabaseType.values());
    final JLabel driverHintLabel = new JLabel();
    final JTextField urlField = new JTextField();
    final JTextField defaultSchemaField = new JTextField();
    final JTextField usernameField = new JTextField();
    final JComboBox<CredentialSource> credentialSourceCombo = new JComboBox<>(CredentialSource.values());
    final JLabel keyringStatusBadge = new JLabel();
    final JPasswordField passwordField = new JPasswordField();
    final JTextField passwordEnvField = new JTextField();
    final JLabel passwordRowLabel = new JLabel("Password:");
    final JLabel passwordEnvRowLabel = new JLabel("Env Var Name:");
    final JSpinner timeoutSpinner = new JSpinner(new SpinnerNumberModel(
            DatabaseConnectionConfig.DEFAULT_TIMEOUT_SECONDS, 1, DatabaseConnectionConfig.MAX_TIMEOUT_SECONDS, 1));
    final JSpinner maxRowsSpinner = new JSpinner(new SpinnerNumberModel(DatabaseConnectionConfig.DEFAULT_MAX_ROWS, 1,
            DatabaseConnectionConfig.MAX_ROWS_LIMIT, 10));
    final DefaultListModel<String> driverPathsModel = new DefaultListModel<>();
    final JList<String> driverPathsList = new JList<>(driverPathsModel);
    final JButton addJarButton;
    final JButton addFolderButton;
    final JButton removeDriverButton;
    final JTextField driverClassField = new JTextField();

    final UiComponents.WarningBanner testWarningBanner = new UiComponents.WarningBanner();
    final UiComponents.InlineConfirmBar confirmBar = new UiComponents.InlineConfirmBar();
    final JButton testConnectionButton;
    final JButton saveButton;
    final JButton revertButton;
    final JButton deleteButton;
    final JProgressBar testConnectionProgressBar = new JProgressBar();
    final JLabel statusLabel = new JLabel("");
    final Timer statusTimer;

    private transient List<DatabaseConnectionConfig> allConnections = List.of();
    private transient @Nullable DatabaseConnectionConfig selectedConnection;
    private boolean idEditedManually;
    private boolean creatingNew = true;
    private @Nullable File lastDriverDirectory;
    transient CompletableFuture<?> lastAsyncFuture = CompletableFuture.completedFuture(null);

    @Autowired
    GlobalDatabasesPanel(DatabaseConfigService databaseConfigService, CredentialStoreService credentialStoreService,
            @Nullable DbService dbService) {
        super(new MigLayout("fill, insets 20, hidemode 3", GROW_FILL, "[][]12" + GROW_FILL));
        this.databaseConfigService = databaseConfigService;
        this.credentialStoreService = credentialStoreService;
        this.dbService = dbService;
        setOpaque(true);

        JLabel titleLabel = new JLabel(TITLE_TEXT);
        JLabel subtitleLabel = new JLabel(SUBTITLE_TEXT);
        JPanel headerPanel = UiComponents.createPageHeader(titleLabel, subtitleLabel, null);
        add(headerPanel, "growx, wmin 0, wrap");

        filterField = new JTextField();
        filterField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Filter connections...");
        filterField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, Boolean.TRUE);
        newButton = UiComponents.createActionButton("New Connection", Codicons.ADD, new Insets(5, 10, 5, 10), false,
                "arc: 8; font: bold", e -> startNewConnection());

        connectionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        connectionList.setCellRenderer(new ConnectionCellRenderer());

        masterCard = buildMasterCard();

        addJarButton = UiComponents.createActionButton("Add JAR...", Codicons.FILE_ZIP, new Insets(4, 10, 4, 10), false,
                ARC_8_STYLE, e -> chooseDriverJar());
        addFolderButton = UiComponents.createActionButton("Add Folder...", Codicons.FOLDER_OPENED,
                new Insets(4, 10, 4, 10), false, ARC_8_STYLE, e -> chooseDriverFolder());
        removeDriverButton = UiComponents.createActionButton("Remove", Codicons.TRASH, new Insets(4, 10, 4, 10), false,
                ARC_8_STYLE, e -> removeSelectedDriverPath());

        testConnectionButton = UiComponents.createActionButton(TEST_CONNECTION_TEXT, Codicons.PLUG,
                new Insets(6, 12, 6, 12), false, "arc: 8; font: bold", e -> testCurrentConnection());
        testConnectionProgressBar.setVisible(false);
        testConnectionProgressBar.setPreferredSize(new Dimension(120, 6));
        saveButton = UiComponents.createActionButton("Save Connection", Codicons.SAVE, new Insets(6, 14, 6, 14), false,
                "arc: 8; font: bold; " + UiComponents.ACCENT_BUTTON_STYLE, e -> saveCurrentConnection());
        revertButton = UiComponents.createActionButton("Revert", Codicons.DISCARD, new Insets(6, 12, 6, 12), false,
                ARC_8_STYLE, e -> revertCurrentForm());
        deleteButton = UiComponents.createActionButton("Delete", Codicons.TRASH, new Insets(6, 12, 6, 12), false,
                ARC_8_STYLE, e -> promptDeleteCurrent());

        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 11.5f));
        statusLabel.setVisible(false);
        statusTimer = UiComponents.createStatusTimer(statusLabel, 4500);

        detailCard = buildDetailCard();

        JPanel splitBody = new JPanel(new MigLayout("fill, insets 0", "[260!, fill]14[grow, fill]", GROW_FILL));
        splitBody.setOpaque(false);
        splitBody.add(masterCard, "growy");
        splitBody.add(UiComponents.createBorderlessScrollPane(detailCard), "grow, wmin 0");
        add(splitBody, "grow, wmin 0");

        setupListeners();
        applyThemeStyling();
        updateCredentialRowVisibility();
        updateTypeHints();
        refresh();
    }

    private JPanel buildMasterCard() {
        JPanel card = new JPanel(new MigLayout("fill, insets 14, wrap 1, hidemode 3", GROW_FILL, "[]8[]8" + GROW_FILL));
        UiComponents.styleCardPanel(card);
        JPanel topBar = new JPanel(new MigLayout("fillx, insets 0", GROW_FILL, "[][]"));
        topBar.setOpaque(false);
        topBar.add(newButton, "growx, wrap");
        topBar.add(filterField, "growx, gaptop 6");
        card.add(topBar, GROWX_WMIN_0);

        JScrollPane listScroll = new JScrollPane(connectionList);
        listScroll.setBorder(SwingHtmlUtils.createThemeMatteBorder(1, 1, 1, 1));
        card.add(listScroll, "grow, push");
        return card;
    }

    private JPanel buildDetailCard() {
        JPanel card = new JPanel(
                new MigLayout("fillx, insets 16, wrap 2, hidemode 3", "[155!, right]12[grow, fill]", ""));
        UiComponents.styleCardPanel(card);

        displayNameField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "e.g. QA Billing Postgres");
        idField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "qa-billing-postgres");
        descriptionField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                "Optional purpose & key tables (e.g. Core order fulfillment, line items, and shipments)");
        defaultSchemaField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                "Optional default schema (e.g. public)");
        usernameField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "readonly_user");
        passwordField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Stored securely in OS keyring");
        passwordEnvField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "e.g. QA_DB_PASSWORD");
        driverClassField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                "Optional (auto-detected via ServiceLoader)");

        card.add(UiComponents.createSectionHeader("Connection Identity", Codicons.DATABASE, "font: bold +1"),
                "span 2, growx, wrap");
        card.add(new JLabel("Display Name:"));
        card.add(displayNameField, GROWX_WMIN_0);
        card.add(new JLabel("Connection ID:"));
        card.add(idField, GROWX_WMIN_0);
        card.add(new JLabel("Description:"));
        card.add(descriptionField, GROWX_WMIN_0);

        card.add(new JLabel("Database Type:"));
        JPanel typeRow = new JPanel(new MigLayout("insets 0, fillx", "[200!]10[grow, fill]", ROW_CENTER));
        typeRow.setOpaque(false);
        typeRow.add(typeCombo);
        typeRow.add(driverHintLabel, GROWX_WMIN_0);
        card.add(typeRow, GROWX_WMIN_0);

        card.add(new JLabel("JDBC URL:"));
        card.add(urlField, GROWX_WMIN_0);
        card.add(new JLabel("Default Schema:"));
        card.add(defaultSchemaField, GROWX_WMIN_0);

        card.add(UiComponents.createSectionHeader("Credentials & Limits", Codicons.LOCK, "font: bold +1"),
                "span 2, growx, gaptop 10, wrap");
        card.add(new JLabel("Username:"));
        card.add(usernameField, GROWX_WMIN_0);

        card.add(new JLabel("Credential Source:"));
        JPanel credSourceRow = new JPanel(new MigLayout("insets 0, fillx", "[200!]10[grow, fill]", ROW_CENTER));
        credSourceRow.setOpaque(false);
        credSourceRow.add(credentialSourceCombo);
        credSourceRow.add(keyringStatusBadge, GROWX_WMIN_0);
        card.add(credSourceRow, GROWX_WMIN_0);

        card.add(passwordRowLabel);
        card.add(passwordField, GROWX_WMIN_0);
        card.add(passwordEnvRowLabel);
        card.add(passwordEnvField, GROWX_WMIN_0);

        card.add(new JLabel("Limits:"));
        JPanel limitsRow = new JPanel(new MigLayout("insets 0", "[]6[90!]18[]6[90!]", ROW_CENTER));
        limitsRow.setOpaque(false);
        limitsRow.add(new JLabel("Timeout (s):"));
        limitsRow.add(timeoutSpinner, "w 90!");
        limitsRow.add(new JLabel("Max Rows:"));
        limitsRow.add(maxRowsSpinner, "w 90!");
        card.add(limitsRow, GROWX_WMIN_0);

        card.add(UiComponents.createSectionHeader("Custom Driver JARs (Optional — Oracle / DB2 / Custom)",
                Codicons.PACKAGE, "font: bold +1"), "span 2, growx, gaptop 10, wrap");
        card.add(new JLabel("Driver JARs / Dirs:"));
        JPanel driverBox = new JPanel(new MigLayout("insets 0, fillx", GROW_FILL, "[]6[]"));
        driverBox.setOpaque(false);
        driverPathsList.setVisibleRowCount(3);
        JScrollPane driverScroll = new JScrollPane(driverPathsList);
        driverScroll.setPreferredSize(new Dimension(200, 74));
        driverBox.add(driverScroll, "growx, wrap");
        JPanel driverButtons = new JPanel(new MigLayout("insets 0", "[]6[]6[]", "[]"));
        driverButtons.setOpaque(false);
        driverButtons.add(addJarButton);
        driverButtons.add(addFolderButton);
        driverButtons.add(removeDriverButton);
        driverBox.add(driverButtons, GROWX_WMIN_0);
        card.add(driverBox, GROWX_WMIN_0);

        card.add(new JLabel("Driver Class:"));
        card.add(driverClassField, GROWX_WMIN_0);

        card.add(testWarningBanner, "span 2, growx, gaptop 8, wrap");
        card.add(confirmBar, "span 2, growx, gaptop 6, wrap");

        JPanel actionRow = new JPanel(new MigLayout("insets 6 0 0 0, fillx", "[]8[]8[]push[]", ROW_CENTER));
        actionRow.setOpaque(false);
        actionRow.add(testConnectionButton);
        actionRow.add(saveButton);
        actionRow.add(revertButton);
        actionRow.add(deleteButton);
        card.add(actionRow, "span 2, growx, wrap");
        card.add(testConnectionProgressBar, "span 2, growx, gaptop 4, hidemode 3, wrap");
        card.add(statusLabel, "span 2, growx, wmin 0, gaptop 4");

        return card;
    }

    private void setupListeners() {
        SwingHtmlUtils.addChangeListener(filterField, this::applyFilter);
        connectionList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !formGuard.isActive()) {
                onConnectionSelected(connectionList.getSelectedValue());
            }
        });
        SwingHtmlUtils.addChangeListener(displayNameField, () -> {
            if (!formGuard.isActive()) {
                if (creatingNew && !idEditedManually) {
                    formGuard.run(
                            () -> idField.setText(PathUtils.sanitizeSlug(displayNameField.getText(), 48, "database")));
                }
                UiComponents.clearStatusMessage(statusLabel, statusTimer, true);
            }
        });
        SwingHtmlUtils.addChangeListener(idField, () -> {
            if (!formGuard.isActive()) {
                if (creatingNew) {
                    idEditedManually = !idField.getText().isBlank();
                }
                UiComponents.clearStatusMessage(statusLabel, statusTimer, true);
            }
        });
        Runnable clearStatusOnEdit = () -> {
            if (!formGuard.isActive()) {
                UiComponents.clearStatusMessage(statusLabel, statusTimer, true);
            }
        };
        SwingHtmlUtils.addChangeListener(descriptionField, clearStatusOnEdit);
        SwingHtmlUtils.addChangeListener(urlField, clearStatusOnEdit);
        SwingHtmlUtils.addChangeListener(defaultSchemaField, clearStatusOnEdit);
        SwingHtmlUtils.addChangeListener(usernameField, clearStatusOnEdit);
        SwingHtmlUtils.addChangeListener(passwordField, clearStatusOnEdit);
        SwingHtmlUtils.addChangeListener(passwordEnvField, clearStatusOnEdit);
        SwingHtmlUtils.addChangeListener(driverClassField, clearStatusOnEdit);
        typeCombo.addActionListener(e -> {
            if (!formGuard.isActive()) {
                updateTypeHints();
                UiComponents.clearStatusMessage(statusLabel, statusTimer, true);
            }
        });
        credentialSourceCombo.addActionListener(e -> {
            if (!formGuard.isActive()) {
                updateCredentialRowVisibility();
                UiComponents.clearStatusMessage(statusLabel, statusTimer, true);
            }
        });
    }

    @EventListener
    public void onDatabaseConfigChanged(DatabaseConfigChangedEvent event) {
        if (event != null && event.globalScope()) {
            Edt.run(this::refresh);
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (masterCard != null && detailCard != null) {
            applyThemeStyling();
        }
    }

    void applyThemeStyling() {
        UiComponents.styleCardPanel(masterCard);
        UiComponents.styleCardPanel(detailCard);
        updateKeyringBadge();
        UiComponents.refreshActionButtonIcons(this);
    }

    /**
     * Reloads global connections from disk and refreshes the list selection.
     */
    public CompletableFuture<List<DatabaseConnectionConfig>> refresh() {
        lastAsyncFuture = Edt.supplyAsync(databaseConfigService::listGlobalConnections, loaded -> {
            allConnections = loaded;
            applyFilter();
            if (selectedConnection != null) {
                DatabaseConnectionConfig refreshed = findById(loaded, selectedConnection.id());
                if (refreshed != null) {
                    populateForm(refreshed, false);
                } else {
                    startNewConnection();
                }
            } else if (!loaded.isEmpty() && creatingNew && displayNameField.getText().isBlank()) {
                connectionList.setSelectedIndex(0);
            }
            updateKeyringBadge();
        });
        return (CompletableFuture<List<DatabaseConnectionConfig>>) lastAsyncFuture;
    }

    @SuppressWarnings("PMD.NullAssignment")
    void startNewConnection() {
        creatingNew = true;
        selectedConnection = null;
        idEditedManually = false;
        formGuard.run(() -> {
            connectionList.clearSelection();
            displayNameField.setText("");
            idField.setText("");
            idField.setEditable(true);
            descriptionField.setText("");
            typeCombo.setSelectedItem(DatabaseType.POSTGRESQL);
            urlField.setText("");
            defaultSchemaField.setText("");
            usernameField.setText("");
            credentialSourceCombo.setSelectedItem(CredentialSource.KEYRING);
            passwordField.setText("");
            passwordEnvField.setText("");
            timeoutSpinner.setValue(DatabaseConnectionConfig.DEFAULT_TIMEOUT_SECONDS);
            maxRowsSpinner.setValue(DatabaseConnectionConfig.DEFAULT_MAX_ROWS);
            driverPathsModel.clear();
            driverClassField.setText("");
        });
        UiComponents.clearStatusMessage(statusLabel, statusTimer, true);
        testWarningBanner.setVisibleState(false, "");
        confirmBar.cancel();
        deleteButton.setEnabled(false);
        updateTypeHints();
        updateCredentialRowVisibility();
        displayNameField.requestFocusInWindow();
    }

    private void onConnectionSelected(@Nullable DatabaseConnectionConfig config) {
        if (config == null) {
            return;
        }
        populateForm(config, true);
    }

    private void populateForm(DatabaseConnectionConfig config, boolean clearStatus) {
        creatingNew = false;
        selectedConnection = config;
        idEditedManually = true;
        formGuard.run(() -> {
            displayNameField.setText(config.displayName());
            idField.setText(config.id());
            idField.setEditable(false);
            descriptionField.setText(config.description());
            typeCombo.setSelectedItem(config.resolveType());
            urlField.setText(config.url());
            defaultSchemaField.setText(config.defaultSchema());
            usernameField.setText(config.username());
            credentialSourceCombo.setSelectedItem(config.resolveCredentialSource());
            passwordField.setText("");
            boolean hasStored = credentialStoreService.hasStoredPassword(config.id());
            if (hasStored) {
                passwordField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                        "Password stored in keyring (leave blank to keep)");
            } else {
                passwordField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                        "Enter password to store in OS keyring");
            }
            passwordEnvField.setText(config.passwordEnv());
            timeoutSpinner.setValue(config.queryTimeoutSeconds());
            maxRowsSpinner.setValue(config.maxRows());
            driverPathsModel.clear();
            for (String path : config.driverPaths()) {
                driverPathsModel.addElement(path);
            }
            driverClassField.setText(config.driverClass());
        });
        if (clearStatus) {
            UiComponents.clearStatusMessage(statusLabel, statusTimer, true);
            testWarningBanner.setVisibleState(false, "");
        }
        confirmBar.cancel();
        deleteButton.setEnabled(true);
        updateTypeHints();
        updateCredentialRowVisibility();
    }

    private void applyFilter() {
        String query = StringUtils.defaultString(filterField.getText()).trim().toLowerCase(Locale.ROOT);
        List<DatabaseConnectionConfig> filtered = new ArrayList<>();
        for (DatabaseConnectionConfig cfg : allConnections) {
            if (query.isEmpty() || cfg.displayName().toLowerCase(Locale.ROOT).contains(query)
                    || cfg.id().toLowerCase(Locale.ROOT).contains(query)
                    || cfg.description().toLowerCase(Locale.ROOT).contains(query)
                    || cfg.type().toLowerCase(Locale.ROOT).contains(query)) {
                filtered.add(cfg);
            }
        }
        String selectedId = null;
        if (selectedConnection != null) {
            selectedId = selectedConnection.id();
        }
        String targetId = selectedId;
        formGuard.run(() -> UiComponents.replaceListItemsPreservingSelection(listModel, connectionList, filtered,
                DatabaseConnectionConfig::id, targetId, false));
    }

    private void updateTypeHints() {
        DatabaseType selected = (DatabaseType) typeCombo.getSelectedItem();
        if (selected == null) {
            selected = DatabaseType.CUSTOM;
        }
        urlField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, selected.getSampleUrl());
        if (selected.isBundledDriver()) {
            driverHintLabel.setText("Bundled OSS driver (" + selected.getDefaultDriverClass() + ")");
            driverHintLabel.setForeground(SwingHtmlUtils.getMutedForegroundColor());
        } else {
            driverHintLabel.setText("Requires custom JDBC JAR(s) below");
            driverHintLabel.setForeground(SwingHtmlUtils.getWarningColor());
        }
    }

    private void updateCredentialRowVisibility() {
        CredentialSource source = (CredentialSource) credentialSourceCombo.getSelectedItem();
        if (source == null) {
            source = CredentialSource.KEYRING;
        }
        boolean showPassword = source == CredentialSource.KEYRING;
        boolean showEnv = source == CredentialSource.ENV;
        passwordRowLabel.setVisible(showPassword);
        passwordField.setVisible(showPassword);
        passwordEnvRowLabel.setVisible(showEnv);
        passwordEnvField.setVisible(showEnv);
        updateKeyringBadge();
        revalidate();
    }

    private void updateKeyringBadge() {
        boolean available = credentialStoreService.isNativeKeyringAvailable();
        if (available) {
            keyringStatusBadge.setText("OS Keyring Ready");
            Color green = SwingHtmlUtils.getUiColor(new Color(0x28, 0xa7, 0x45), "Actions.Green");
            keyringStatusBadge.setForeground(green);
            keyringStatusBadge.setIcon(FontIcon.of(Codicons.PASS, 13, green));
        } else {
            keyringStatusBadge.setText("Session-Only Fallback (No OS Keyring)");
            Color warn = SwingHtmlUtils.getWarningColor();
            keyringStatusBadge.setForeground(warn);
            keyringStatusBadge.setIcon(FontIcon.of(Codicons.WARNING, 13, warn));
        }
    }

    private void chooseDriverJar() {
        File chosen = UiComponents.chooseOpenFile(this, "Select JDBC Driver JAR",
                new SystemFileChooser.FileNameExtensionFilter("JAR Archives (*.jar)", "jar"));
        if (chosen != null) {
            lastDriverDirectory = chosen.getParentFile();
            addDriverPathIfMissing(chosen.getAbsolutePath());
        }
    }

    private void chooseDriverFolder() {
        java.nio.file.Path initialDir = null;
        if (lastDriverDirectory != null) {
            initialDir = lastDriverDirectory.toPath();
        }
        File chosen = UiComponents.chooseDirectory(this, "Select Folder Containing JDBC Driver JARs", initialDir);
        if (chosen != null) {
            lastDriverDirectory = chosen;
            addDriverPathIfMissing(chosen.getAbsolutePath());
        }
    }

    private void addDriverPathIfMissing(String path) {
        for (int i = 0; i < driverPathsModel.size(); i++) {
            if (Objects.equals(driverPathsModel.get(i), path)) {
                return;
            }
        }
        driverPathsModel.addElement(path);
    }

    private void removeSelectedDriverPath() {
        int idx = driverPathsList.getSelectedIndex();
        if (idx >= 0) {
            driverPathsModel.remove(idx);
        }
    }

    DatabaseConnectionConfig buildConfigFromForm() {
        DatabaseType type = (DatabaseType) typeCombo.getSelectedItem();
        if (type == null) {
            type = DatabaseType.CUSTOM;
        }
        CredentialSource credSource = (CredentialSource) credentialSourceCombo.getSelectedItem();
        if (credSource == null) {
            credSource = CredentialSource.KEYRING;
        }
        List<String> driverPaths = new ArrayList<>(driverPathsModel.size());
        for (int i = 0; i < driverPathsModel.size(); i++) {
            driverPaths.add(driverPathsModel.get(i));
        }
        return new DatabaseConnectionConfig(idField.getText(), displayNameField.getText(), descriptionField.getText(),
                type.getId(), urlField.getText(), usernameField.getText(), credSource.getId(),
                passwordEnvField.getText(), driverPaths, driverClassField.getText(), defaultSchemaField.getText(),
                ((Number) timeoutSpinner.getValue()).intValue(), ((Number) maxRowsSpinner.getValue()).intValue());
    }

    CompletableFuture<DbTestResult> testCurrentConnection() {
        DatabaseConnectionConfig cfg = buildConfigFromForm();
        if (cfg.url().isBlank()) {
            UiComponents.showStatusMessage(statusLabel, statusTimer, "Enter a JDBC URL before testing.", false);
            return CompletableFuture
                    .completedFuture(new DbTestResult(false, false, "", "JDBC URL must not be blank.", 0L));
        }
        char[] chars = passwordField.getPassword();
        String typedPassword = null;
        if (chars.length > 0) {
            typedPassword = new String(chars);
        }
        String finalTypedPassword = typedPassword;
        UiComponents.clearStatusMessage(statusLabel, statusTimer, true);
        testConnectionButton.setEnabled(false);
        testConnectionButton.setText(TESTING_CONNECTION_TEXT);
        testConnectionProgressBar.setIndeterminate(true);
        testConnectionProgressBar.setVisible(true);
        testWarningBanner.setVisibleState(false, "");

        CompletableFuture<DbTestResult> future = Edt.supplyAsync(() -> {
            if (dbService == null) {
                return new DbTestResult(false, false, "", "DbService is unavailable.", 0L);
            }
            return dbService.testConnection(cfg, finalTypedPassword);
        }, result -> {
            restoreTestConnectionButtonState();
            if (!result.success()) {
                UiComponents.showStatusMessage(statusLabel, statusTimer, result.message(), false);
                return;
            }
            if (result.writePrivilegeWarning()) {
                testWarningBanner.setVisibleState(true,
                        "Security Notice: Connected in read-only transaction mode (" + result.serverSummary()
                                + "), but this database user still appears to hold write privileges on the server. "
                                + "Consider using a dedicated read-only DB role.");
            }
            String summary = "";
            if (!result.serverSummary().isBlank()) {
                summary = " [" + result.serverSummary() + "]";
            }
            UiComponents.showStatusMessage(statusLabel, statusTimer,
                    "Connected in " + result.durationMs() + " ms" + summary + ".", true);
        }, error -> {
            restoreTestConnectionButtonState();
            UiComponents.showStatusMessage(statusLabel, statusTimer,
                    "Test failed: " + ExceptionUtils.getRootCauseMessage(error), false);
        });
        lastAsyncFuture = future;
        return future;
    }

    private void restoreTestConnectionButtonState() {
        testConnectionButton.setEnabled(true);
        testConnectionButton.setText(TEST_CONNECTION_TEXT);
        testConnectionProgressBar.setIndeterminate(false);
        testConnectionProgressBar.setVisible(false);
    }

    CompletableFuture<DatabaseConnectionConfig> saveCurrentConnection() {
        DatabaseConnectionConfig cfg = buildConfigFromForm();
        if (cfg.url().isBlank()) {
            UiComponents.showStatusMessage(statusLabel, statusTimer, "JDBC URL is required.", false);
            return CompletableFuture.completedFuture(cfg);
        }
        char[] chars = passwordField.getPassword();
        String typedPassword = null;
        if (chars.length > 0) {
            typedPassword = new String(chars);
        }
        String finalTypedPassword = typedPassword;
        saveButton.setEnabled(false);

        CompletableFuture<DatabaseConnectionConfig> future = Edt.supplyAsync(() -> {
            return databaseConfigService.saveGlobalConnection(cfg, finalTypedPassword, false);
        }, saved -> {
            saveButton.setEnabled(true);
            selectedConnection = saved;
            creatingNew = false;
            passwordField.setText("");
            UiComponents.showStatusMessage(statusLabel, statusTimer,
                    "Saved connection '" + saved.displayName() + "' (" + saved.id() + ").", true);
            refresh();
        }, error -> {
            saveButton.setEnabled(true);
            UiComponents.showStatusMessage(statusLabel, statusTimer, "Save failed: " + error.getMessage(), false);
        });
        lastAsyncFuture = future;
        return future;
    }

    private void revertCurrentForm() {
        if (selectedConnection != null) {
            populateForm(selectedConnection, true);
        } else {
            startNewConnection();
        }
    }

    void promptDeleteCurrent() {
        if (selectedConnection == null) {
            return;
        }
        DatabaseConnectionConfig target = selectedConnection;
        confirmBar.prompt("Delete global database connection '" + target.displayName() + "' (" + target.id() + ")?",
                "Delete", true, () -> performDelete(target.id()));
    }

    @SuppressWarnings("PMD.NullAssignment")
    CompletableFuture<Boolean> performDelete(String connectionId) {
        CompletableFuture<Boolean> future = Edt
                .supplyAsync(() -> databaseConfigService.deleteGlobalConnection(connectionId), deleted -> {
                    if (Boolean.TRUE.equals(deleted)) {
                        selectedConnection = null;
                        startNewConnection();
                        UiComponents.showStatusMessage(statusLabel, statusTimer,
                                "Deleted connection '" + connectionId + "'.", true);
                        refresh();
                    }
                }, error -> UiComponents.showStatusMessage(statusLabel, statusTimer,
                        "Delete failed: " + error.getMessage(), false));
        lastAsyncFuture = future;
        return future;
    }

    private static @Nullable DatabaseConnectionConfig findById(List<DatabaseConnectionConfig> list, String id) {
        for (DatabaseConnectionConfig item : list) {
            if (item.id().equals(id)) {
                return item;
            }
        }
        return null;
    }

    private static final class ConnectionCellRenderer extends JPanel
            implements
                ListCellRenderer<DatabaseConnectionConfig> {
        private static final long serialVersionUID = 1L;
        private final JLabel titleLabel = new JLabel();
        private final JLabel metaLabel = new JLabel();
        private final JLabel descriptionLabel = new JLabel();

        private ConnectionCellRenderer() {
            setLayout(new MigLayout("fillx, insets 6 8 6 8, wrap 1, hidemode 3", GROW_FILL, "[]2[]2[]"));
            setOpaque(true);
            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 12.5f));
            metaLabel.setFont(metaLabel.getFont().deriveFont(Font.PLAIN, 11f));
            descriptionLabel.setFont(descriptionLabel.getFont().deriveFont(Font.PLAIN, 11f));
            add(titleLabel, "growx");
            add(metaLabel, "growx");
            add(descriptionLabel, "growx");
            setBorder(new EmptyBorder(1, 0, 1, 0));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends DatabaseConnectionConfig> list,
                DatabaseConnectionConfig value, int index, boolean isSelected, boolean cellHasFocus) {
            UiComponents.ListCellColors colors = UiComponents.resolveListCellColors(list, isSelected);
            setBackground(colors.background());
            titleLabel.setForeground(colors.foreground());
            metaLabel.setForeground(colors.muted());
            descriptionLabel.setForeground(colors.muted());
            if (value != null) {
                titleLabel.setText(value.displayName());
                metaLabel.setText(value.id() + "  •  " + value.resolveType().getDisplayName());
                titleLabel.setIcon(FontIcon.of(Codicons.DATABASE, 14, colors.iconColor()));
                String desc = value.description();
                if (desc != null && !desc.isBlank()) {
                    descriptionLabel.setText(desc);
                    descriptionLabel.setVisible(true);
                } else {
                    descriptionLabel.setText("");
                    descriptionLabel.setVisible(false);
                }
            }
            return this;
        }
    }
}
