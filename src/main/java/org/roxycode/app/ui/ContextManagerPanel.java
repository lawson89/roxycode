package org.roxycode.app.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.service.PromptService;
import org.roxycode.app.service.PromptService.ContextFileItem;
import org.roxycode.app.util.Edt;
import org.roxycode.app.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Lazy
public final class ContextManagerPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    static final String PAGE_TITLE = "Context Folders";
    static final String PAGE_SUBTITLE = "Top-level Markdown and text files in these folders are automatically injected into every AI system prompt as coding guidelines and reference docs.";
    static final String GLOBAL_SUBTITLE = "Shared across all projects";
    static final String PROJECT_SUBTITLE = "Scoped to active project";

    final JLabel titleLabel;
    final JLabel subtitleLabel;
    final ContextScopeColumn globalColumn;
    final ContextScopeColumn projectColumn;
    final JLabel statusLabel;
    private final Timer statusTimer;

    public ContextManagerPanel(PromptService promptService) {
        setLayout(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        this.titleLabel = new JLabel(PAGE_TITLE);
        this.subtitleLabel = new JLabel(PAGE_SUBTITLE);
        add(UiComponents.createPageHeader(titleLabel, subtitleLabel, null), BorderLayout.NORTH);

        this.statusLabel = new JLabel("");
        this.statusTimer = UiComponents.createStatusTimer(statusLabel, 4000);

        this.globalColumn = new ContextScopeColumn(promptService, "Global Context", true, this::showError);
        this.projectColumn = new ContextScopeColumn(promptService, "Project Context", false, this::showError);

        JPanel columnsPanel = new JPanel(new GridLayout(1, 2, 12, 0));
        columnsPanel.setOpaque(false);
        columnsPanel.add(globalColumn);
        columnsPanel.add(projectColumn);
        add(columnsPanel, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        Edt.run(globalColumn::refreshFoldersAndFiles);
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        Edt.run(() -> {
            projectColumn.clearSelection();
            projectColumn.refreshFoldersAndFiles();
        });
    }

    void showError(String message) {
        UiComponents.showStatusMessage(statusLabel, statusTimer, message, false);
    }

    static final class ContextScopeColumn extends JPanel {
        private static final long serialVersionUID = 1L;
        private static final Logger log = LoggerFactory.getLogger(ContextScopeColumn.class);

        private final transient PromptService promptService;
        private final boolean globalScope;
        private final transient Consumer<String> errorReporter;

        final JPanel headerBanner;
        final JLabel headerLabel;
        final JLabel subtitleLabel;
        final JLabel foldersSectionLabel;
        final JLabel filesSectionLabel;

        final DefaultListModel<String> folderListModel = new DefaultListModel<>();
        final JList<String> folderList = new JList<>(folderListModel);
        final JButton btnAddFolder = new JButton("Add Folder...");
        final JButton btnRemoveFolder = new JButton("Remove Folder");

        final DefaultListModel<ContextFileItem> fileListModel = new DefaultListModel<>();
        final JList<ContextFileItem> fileList = new JList<>(fileListModel);
        transient CompletableFuture<FolderAndFilesData> lastRefreshFuture = CompletableFuture
                .completedFuture(new FolderAndFilesData(List.of(), List.of()));

        record FolderAndFilesData(List<String> folders, List<ContextFileItem> files) {
        }

        ContextScopeColumn(PromptService promptService, String sectionTitle, boolean globalScope,
                Consumer<String> errorReporter) {
            this.promptService = promptService;
            this.globalScope = globalScope;
            this.errorReporter = errorReporter;

            setLayout(new BorderLayout(0, 8));

            headerLabel = new JLabel(sectionTitle);
            headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD, 14f));
            headerLabel.setIconTextGap(8);

            String subtitleText;
            if (globalScope) {
                subtitleText = GLOBAL_SUBTITLE;
            } else {
                subtitleText = PROJECT_SUBTITLE;
            }
            subtitleLabel = new JLabel(subtitleText);
            subtitleLabel.setFont(subtitleLabel.getFont().deriveFont(Font.PLAIN, 11f));

            headerBanner = new JPanel(new BorderLayout(8, 0));
            headerBanner.add(headerLabel, BorderLayout.WEST);
            headerBanner.add(subtitleLabel, BorderLayout.EAST);
            add(headerBanner, BorderLayout.NORTH);

            folderList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            folderList.setCellRenderer(createCellRenderer(Codicons.FOLDER));

            btnAddFolder.setIconTextGap(6);
            btnRemoveFolder.setIconTextGap(6);
            btnRemoveFolder.setEnabled(false);

            JPanel folderButtons = new JPanel(new GridLayout(1, 2, 5, 5));
            folderButtons.add(btnAddFolder);
            folderButtons.add(btnRemoveFolder);

            foldersSectionLabel = new JLabel(sectionTitle + " Folders");
            foldersSectionLabel.setIconTextGap(6);

            JPanel foldersPanel = new JPanel(new BorderLayout(0, 5));
            foldersPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
            foldersPanel.add(foldersSectionLabel, BorderLayout.NORTH);
            foldersPanel.add(new JScrollPane(folderList), BorderLayout.CENTER);
            foldersPanel.add(folderButtons, BorderLayout.SOUTH);

            fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            fileList.setCellRenderer(createCellRenderer(Codicons.FILE));

            filesSectionLabel = new JLabel("Discovered Text Files (Top-Level)");
            filesSectionLabel.setIconTextGap(6);

            JPanel filesPanel = new JPanel(new BorderLayout(0, 5));
            filesPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
            filesPanel.add(filesSectionLabel, BorderLayout.NORTH);
            filesPanel.add(new JScrollPane(fileList), BorderLayout.CENTER);

            JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, foldersPanel, filesPanel);
            splitPane.setDividerLocation(220);
            splitPane.setResizeWeight(0.45);
            add(splitPane, BorderLayout.CENTER);

            applyScopeStyling();
            setupListeners();
            refreshFoldersAndFiles();
        }

        private ListCellRenderer<Object> createCellRenderer(Codicons icon) {
            return new DefaultListCellRenderer() {
                private static final long serialVersionUID = 1L;

                @Override
                public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                        boolean isSelected, boolean cellHasFocus) {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    Color iconColor;
                    if (isSelected) {
                        iconColor = getForeground();
                    } else {
                        iconColor = resolveAccentColor();
                    }
                    setIcon(FontIcon.of(icon, 14, iconColor));
                    setIconTextGap(6);
                    if (value instanceof ContextFileItem item) {
                        setText(item.fileName());
                        if (item.filePath() != null) {
                            setToolTipText(item.filePath().toString());
                        } else {
                            setToolTipText(null);
                        }
                    } else if (value != null) {
                        setToolTipText(value.toString());
                    }
                    return this;
                }
            };
        }

        @Override
        public void updateUI() {
            super.updateUI();
            if (headerBanner != null) {
                applyScopeStyling();
            }
        }

        Color resolveAccentColor() {
            if (globalScope) {
                return SwingHtmlUtils.getAccentColor();
            }
            return SwingHtmlUtils.getUiColor(new Color(0x2e, 0x7d, 0x32), "Actions.Green");
        }

        void applyScopeStyling() {
            Color accent = resolveAccentColor();
            Color borderColor = SwingHtmlUtils.getUiColor(new Color(0xcc, 0xcc, 0xcc), "Component.borderColor");
            Color buttonFg = SwingHtmlUtils.getUiColor(new Color(0x33, 0x33, 0x33), "Button.foreground",
                    "Label.foreground");

            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(3, 0, 0, 0, accent),
                            BorderFactory.createMatteBorder(0, 1, 1, 1, borderColor)),
                    BorderFactory.createEmptyBorder(8, 8, 8, 8)));

            SwingHtmlUtils.applyTintedBannerStyle(headerBanner, accent, 6, 10, 10);
            Codicons headerIcon;
            if (globalScope) {
                headerIcon = Codicons.GLOBE;
            } else {
                headerIcon = Codicons.FOLDER_OPENED;
            }
            headerLabel.setIcon(FontIcon.of(headerIcon, 18, accent));
            subtitleLabel.setForeground(SwingHtmlUtils.getMutedForegroundColor());

            foldersSectionLabel.setIcon(FontIcon.of(Codicons.FOLDER, 14, accent));
            filesSectionLabel.setIcon(FontIcon.of(Codicons.FILE, 14, accent));

            btnAddFolder.setIcon(FontIcon.of(Codicons.ADD, 14, accent));
            btnRemoveFolder.setIcon(FontIcon.of(Codicons.TRASH, 14, buttonFg));

            folderList.repaint();
            fileList.repaint();
        }

        void clearSelection() {
            folderList.clearSelection();
            fileList.clearSelection();
            btnRemoveFolder.setEnabled(false);
        }

        private void setupListeners() {
            folderList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    btnRemoveFolder.setEnabled(folderList.getSelectedValue() != null);
                }
            });
            btnAddFolder.addActionListener(e -> chooseAndAddFolder());
            btnRemoveFolder.addActionListener(e -> {
                String selectedFolder = folderList.getSelectedValue();
                if (selectedFolder != null) {
                    removeFolderAsync(selectedFolder);
                }
            });
        }

        private void chooseAndAddFolder() {
            String dialogTitle;
            if (globalScope) {
                dialogTitle = "Select Global Context Folder";
            } else {
                dialogTitle = "Select Project Context Folder";
            }
            File selectedFolder = UiComponents.chooseDirectory(this, dialogTitle, null);
            if (selectedFolder != null) {
                addFolderAsync(selectedFolder.getAbsolutePath());
            }
        }

        CompletableFuture<FolderAndFilesData> addFolderAsync(String folderPath) {
            return mutateFoldersAsync(updated -> {
                if (folderPath == null || folderPath.isBlank()) {
                    throw new IllegalArgumentException("Folder path must not be blank");
                }
                String trimmed = folderPath.trim();
                if (!updated.contains(trimmed)) {
                    updated.add(trimmed);
                }
            }, null, "add", "adding", folderPath);
        }

        CompletableFuture<FolderAndFilesData> removeFolderAsync(String folderPath) {
            return mutateFoldersAsync(updated -> updated.remove(folderPath), this::clearSelection, "remove", "removing",
                    folderPath);
        }

        private CompletableFuture<FolderAndFilesData> mutateFoldersAsync(Consumer<List<String>> mutator,
                Runnable onEdtSuccess, String logVerb, String errorGerund, String folderPath) {
            CompletableFuture<FolderAndFilesData> future = Edt.runAsync(() -> {
                List<String> updated = new ArrayList<>(loadConfiguredFolders());
                mutator.accept(updated);
                saveConfiguredFolders(updated);
            }, onEdtSuccess, ex -> {
                log.warn("Failed to {} context folder {}: {}", logVerb, folderPath, ex.getMessage());
                if (errorReporter != null) {
                    errorReporter.accept("Error " + errorGerund + " context folder " + folderPath + ": "
                            + ExceptionUtils.getRootCauseMessage(ex));
                }
            }).thenCompose(v -> refreshFoldersAndFiles());
            lastRefreshFuture = future;
            return future;
        }

        CompletableFuture<FolderAndFilesData> refreshFoldersAndFiles() {
            CompletableFuture<FolderAndFilesData> future = Edt.supplyAsync(
                    () -> new FolderAndFilesData(Objects.requireNonNullElse(loadConfiguredFolders(), List.of()),
                            Objects.requireNonNullElse(loadDiscoveredFiles(), List.of())),
                    data -> {
                        folderListModel.clear();
                        data.folders().forEach(folderListModel::addElement);
                        fileListModel.clear();
                        data.files().forEach(fileListModel::addElement);
                        btnRemoveFolder.setEnabled(folderList.getSelectedValue() != null);
                    }, ex -> {
                        log.warn("Failed to refresh context folders and files: {}", ex.getMessage());
                        if (errorReporter != null) {
                            errorReporter.accept(
                                    "Error refreshing context folders: " + ExceptionUtils.getRootCauseMessage(ex));
                        }
                    });
            lastRefreshFuture = future;
            return future;
        }

        private List<String> loadConfiguredFolders() {
            if (globalScope) {
                return promptService.getGlobalContextFolders();
            }
            return promptService.getProjectContextFolders();
        }

        private void saveConfiguredFolders(List<String> folders) {
            if (globalScope) {
                promptService.setGlobalContextFolders(folders);
            } else {
                promptService.setProjectContextFolders(folders);
            }
        }

        private List<ContextFileItem> loadDiscoveredFiles() {
            if (globalScope) {
                return promptService.listGlobalContextFiles();
            }
            return promptService.listProjectContextFiles();
        }
    }
}
