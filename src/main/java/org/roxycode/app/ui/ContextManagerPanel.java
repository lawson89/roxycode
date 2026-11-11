package org.roxycode.app.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
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
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.ProjectService.ReferenceFolderMount;
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
final class ContextManagerPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    static final String PAGE_TITLE = "Context Folders";
    static final String PAGE_SUBTITLE = "Manage auto-injected Context Folders (top-level Markdown/text docs) and read-only Reference Folders (@alias source directories).";
    static final String GLOBAL_SUBTITLE = "Shared across all projects";
    static final String PROJECT_SUBTITLE = "Scoped to active project";
    static final String REFERENCE_FOLDERS_SECTION_TITLE = "Reference Folders (Read-Only @alias)";
    static final String CONTEXT_FOLDERS_TOOLTIP_HTML = "<html><body style='width: 280px; padding: 4px;'>"
            + "<b>Context Folders (Auto-Injected Docs)</b><br>"
            + "Top-level non-binary text and Markdown files inside these folders are automatically injected into Roxy's stable system prompt on every turn.<br><br>"
            + "Use this for coding guidelines, architecture conventions, and shared documentation." + "</body></html>";
    static final String REFERENCE_FOLDERS_TOOLTIP_HTML = "<html><body style='width: 280px; padding: 4px;'>"
            + "<b>Reference Folders (Read-Only @alias)</b><br>"
            + "Mounts external repositories or source directories as read-only <code>@&lt;alias&gt;</code> prefixes without consuming prompt tokens up front.<br><br>"
            + "Roxy can search, inspect symbols, and read files on demand via <code>@&lt;alias&gt;/path</code> while all file modifications remain strictly blocked."
            + "</body></html>";

    final JLabel titleLabel;
    final JLabel subtitleLabel;
    final ContextScopeColumn globalColumn;
    final ContextScopeColumn projectColumn;
    final JLabel statusLabel;
    private final Timer statusTimer;

    public ContextManagerPanel(PromptService promptService, ProjectService projectService) {
        setLayout(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        this.titleLabel = new JLabel(PAGE_TITLE);
        this.subtitleLabel = new JLabel(PAGE_SUBTITLE);
        add(UiComponents.createPageHeader(titleLabel, subtitleLabel, null), BorderLayout.NORTH);

        this.statusLabel = new JLabel("");
        this.statusTimer = UiComponents.createStatusTimer(statusLabel, 4000);

        this.globalColumn = new ContextScopeColumn(promptService, projectService, "Global Context", true,
                this::showError);
        this.projectColumn = new ContextScopeColumn(promptService, projectService, "Project Context", false,
                this::showError);

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
            globalColumn.refreshFoldersAndFiles();
        });
    }

    void showError(String message) {
        UiComponents.showStatusMessage(statusLabel, statusTimer, message, false);
    }

    static final class ContextScopeColumn extends JPanel {
        private static final long serialVersionUID = 1L;
        private static final Logger log = LoggerFactory.getLogger(ContextScopeColumn.class);

        private final transient PromptService promptService;
        private final transient ProjectService projectService;
        private final boolean globalScope;
        private final transient Consumer<String> errorReporter;

        final JPanel headerBanner;
        final JLabel headerLabel;
        final JLabel subtitleLabel;
        final JLabel foldersSectionLabel;
        final JLabel foldersInfoIcon;
        final JLabel filesSectionLabel;
        final JLabel referenceFoldersSectionLabel;
        final JLabel referenceFoldersInfoIcon;

        final DefaultListModel<String> folderListModel = new DefaultListModel<>();
        final JList<String> folderList = new JList<>(folderListModel);
        final JButton btnAddFolder = new JButton("Add Folder...");
        final JButton btnRemoveFolder = new JButton("Remove");

        final DefaultListModel<ContextFileItem> fileListModel = new DefaultListModel<>();
        final JList<ContextFileItem> fileList = new JList<>(fileListModel);

        final DefaultListModel<ReferenceFolderMount> referenceFolderListModel = new DefaultListModel<>();
        final JList<ReferenceFolderMount> referenceFolderList = new JList<>(referenceFolderListModel);
        final JButton btnAddReferenceFolder = new JButton("Add Reference Folder...");
        final JButton btnRemoveReferenceFolder = new JButton("Remove");

        transient CompletableFuture<FolderAndFilesData> lastRefreshFuture = CompletableFuture
                .completedFuture(new FolderAndFilesData(List.of(), List.of(), List.of()));

        record FolderAndFilesData(List<String> folders, List<ContextFileItem> files,
                List<ReferenceFolderMount> referenceFolders) {
        }

        ContextScopeColumn(PromptService promptService, ProjectService projectService, String sectionTitle,
                boolean globalScope, Consumer<String> errorReporter) {
            this.promptService = promptService;
            this.projectService = projectService;
            this.globalScope = globalScope;
            this.errorReporter = errorReporter;

            setLayout(new MigLayout("fill, insets 10, gapy 8", "[grow, fill]", "[][grow, fill]"));

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
            add(headerBanner, "growx, wmin 0, wrap");

            folderList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            folderList.setCellRenderer(createCellRenderer(Codicons.FOLDER));

            btnAddFolder.setIconTextGap(6);
            btnRemoveFolder.setIconTextGap(6);
            btnRemoveFolder.setEnabled(false);

            JPanel folderButtons = new JPanel(new GridLayout(1, 2, 5, 5));
            folderButtons.setOpaque(false);
            folderButtons.add(btnAddFolder);
            folderButtons.add(btnRemoveFolder);

            foldersSectionLabel = new JLabel(sectionTitle + " Folders");
            foldersSectionLabel.setIconTextGap(6);
            foldersInfoIcon = UiComponents.createInfoIcon(CONTEXT_FOLDERS_TOOLTIP_HTML);

            JPanel foldersHeaderRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            foldersHeaderRow.setOpaque(false);
            foldersHeaderRow.add(foldersSectionLabel);
            foldersHeaderRow.add(foldersInfoIcon);

            JPanel foldersPanel = new JPanel(new BorderLayout(0, 5));
            foldersPanel.setOpaque(false);
            foldersPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
            foldersPanel.add(foldersHeaderRow, BorderLayout.NORTH);
            foldersPanel.add(new JScrollPane(folderList), BorderLayout.CENTER);
            foldersPanel.add(folderButtons, BorderLayout.SOUTH);

            fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            fileList.setCellRenderer(createCellRenderer(Codicons.FILE));

            filesSectionLabel = new JLabel("Discovered Text Files (Top-Level)");
            filesSectionLabel.setIconTextGap(6);

            JPanel filesPanel = new JPanel(new BorderLayout(0, 5));
            filesPanel.setOpaque(false);
            filesPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
            filesPanel.add(filesSectionLabel, BorderLayout.NORTH);
            filesPanel.add(new JScrollPane(fileList), BorderLayout.CENTER);

            referenceFolderList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            referenceFolderList.setCellRenderer(createCellRenderer(Codicons.REPO));

            btnAddReferenceFolder.setIconTextGap(6);
            btnRemoveReferenceFolder.setIconTextGap(6);
            btnRemoveReferenceFolder.setEnabled(false);

            JPanel referenceFolderButtons = new JPanel(new GridLayout(1, 2, 5, 5));
            referenceFolderButtons.setOpaque(false);
            referenceFolderButtons.add(btnAddReferenceFolder);
            referenceFolderButtons.add(btnRemoveReferenceFolder);

            referenceFoldersSectionLabel = new JLabel(REFERENCE_FOLDERS_SECTION_TITLE);
            referenceFoldersSectionLabel.setIconTextGap(6);
            referenceFoldersInfoIcon = UiComponents.createInfoIcon(REFERENCE_FOLDERS_TOOLTIP_HTML);

            JPanel referenceFoldersHeaderRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            referenceFoldersHeaderRow.setOpaque(false);
            referenceFoldersHeaderRow.add(referenceFoldersSectionLabel);
            referenceFoldersHeaderRow.add(referenceFoldersInfoIcon);

            JPanel referenceFoldersPanel = new JPanel(new BorderLayout(0, 5));
            referenceFoldersPanel.setOpaque(false);
            referenceFoldersPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
            referenceFoldersPanel.add(referenceFoldersHeaderRow, BorderLayout.NORTH);
            referenceFoldersPanel.add(new JScrollPane(referenceFolderList), BorderLayout.CENTER);
            referenceFoldersPanel.add(referenceFolderButtons, BorderLayout.SOUTH);

            JSplitPane contextSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, foldersPanel, filesPanel);
            contextSplitPane.setDividerLocation(180);
            contextSplitPane.setResizeWeight(0.5);
            contextSplitPane.setBorder(null);
            contextSplitPane.setOpaque(false);

            JSplitPane mainSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, contextSplitPane,
                    referenceFoldersPanel);
            mainSplitPane.setDividerLocation(360);
            mainSplitPane.setResizeWeight(0.65);
            mainSplitPane.setBorder(null);
            mainSplitPane.setOpaque(false);
            add(mainSplitPane, "grow, push, wmin 0");

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
                    } else if (value instanceof ReferenceFolderMount mount) {
                        setText(mount.toString());
                        if (mount.rootPath() != null) {
                            setToolTipText(mount.rootPath().toString());
                        } else {
                            setToolTipText(mount.configuredPath());
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
            Color buttonFg = SwingHtmlUtils.getUiColor(new Color(0x33, 0x33, 0x33), "Button.foreground",
                    "Label.foreground");

            UiComponents.styleCardPanel(this);

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
            referenceFoldersSectionLabel.setIcon(FontIcon.of(Codicons.REPO, 14, accent));
            UiComponents.updateInfoIconTheme(foldersInfoIcon);
            UiComponents.updateInfoIconTheme(referenceFoldersInfoIcon);

            btnAddFolder.setIcon(FontIcon.of(Codicons.ADD, 14, accent));
            btnRemoveFolder.setIcon(FontIcon.of(Codicons.TRASH, 14, buttonFg));
            btnAddReferenceFolder.setIcon(FontIcon.of(Codicons.ADD, 14, accent));
            btnRemoveReferenceFolder.setIcon(FontIcon.of(Codicons.TRASH, 14, buttonFg));

            folderList.repaint();
            fileList.repaint();
            referenceFolderList.repaint();
        }

        void clearSelection() {
            folderList.clearSelection();
            fileList.clearSelection();
            referenceFolderList.clearSelection();
            btnRemoveFolder.setEnabled(false);
            btnRemoveReferenceFolder.setEnabled(false);
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

            referenceFolderList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    btnRemoveReferenceFolder.setEnabled(referenceFolderList.getSelectedValue() != null);
                }
            });
            btnAddReferenceFolder.addActionListener(e -> chooseAndAddReferenceFolder());
            btnRemoveReferenceFolder.addActionListener(e -> {
                ReferenceFolderMount selectedMount = referenceFolderList.getSelectedValue();
                if (selectedMount != null) {
                    removeReferenceFolderAsync(selectedMount.configuredPath());
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

        private void chooseAndAddReferenceFolder() {
            String dialogTitle;
            if (globalScope) {
                dialogTitle = "Select Global Reference Folder (Read-Only)";
            } else {
                dialogTitle = "Select Project Reference Folder (Read-Only)";
            }
            File selectedFolder = UiComponents.chooseDirectory(this, dialogTitle, null);
            if (selectedFolder != null) {
                addReferenceFolderAsync(selectedFolder.getAbsolutePath());
            }
        }

        CompletableFuture<FolderAndFilesData> addFolderAsync(String folderPath) {
            return addFolderToListAsync(this::loadConfiguredFolders, this::saveConfiguredFolders, "context folder",
                    "Folder path must not be blank", folderPath);
        }

        CompletableFuture<FolderAndFilesData> removeFolderAsync(String folderPath) {
            return removeFolderFromListAsync(this::loadConfiguredFolders, this::saveConfiguredFolders, "context folder",
                    folderPath);
        }

        CompletableFuture<FolderAndFilesData> addReferenceFolderAsync(String folderPath) {
            return addFolderToListAsync(this::loadConfiguredReferenceFolders, this::saveConfiguredReferenceFolders,
                    "reference folder", "Reference folder path must not be blank", folderPath);
        }

        CompletableFuture<FolderAndFilesData> removeReferenceFolderAsync(String folderPath) {
            return removeFolderFromListAsync(this::loadConfiguredReferenceFolders, this::saveConfiguredReferenceFolders,
                    "reference folder", folderPath);
        }

        private CompletableFuture<FolderAndFilesData> addFolderToListAsync(Supplier<List<String>> loader,
                Consumer<List<String>> saver, String folderTypeLabel, String blankErrorMessage, String folderPath) {
            return mutateFolderListAsync(loader, saver, folderTypeLabel, updated -> {
                if (folderPath == null || folderPath.isBlank()) {
                    throw new IllegalArgumentException(blankErrorMessage);
                }
                String trimmed = folderPath.trim();
                if (!updated.contains(trimmed)) {
                    updated.add(trimmed);
                }
            }, null, "add", "adding", folderPath);
        }

        private CompletableFuture<FolderAndFilesData> removeFolderFromListAsync(Supplier<List<String>> loader,
                Consumer<List<String>> saver, String folderTypeLabel, String folderPath) {
            return mutateFolderListAsync(loader, saver, folderTypeLabel, updated -> updated.remove(folderPath),
                    this::clearSelection, "remove", "removing", folderPath);
        }

        private CompletableFuture<FolderAndFilesData> mutateFolderListAsync(Supplier<List<String>> loader,
                Consumer<List<String>> saver, String folderTypeLabel, Consumer<List<String>> mutator,
                Runnable onEdtSuccess, String logVerb, String errorGerund, String folderPath) {
            CompletableFuture<FolderAndFilesData> future = Edt.runAsync(() -> {
                List<String> updated = new ArrayList<>(loader.get());
                mutator.accept(updated);
                saver.accept(updated);
            }, onEdtSuccess, ex -> {
                log.warn("Failed to {} {} {}: {}", logVerb, folderTypeLabel, folderPath, ex.getMessage());
                if (errorReporter != null) {
                    errorReporter.accept("Error " + errorGerund + " " + folderTypeLabel + " " + folderPath + ": "
                            + ExceptionUtils.getRootCauseMessage(ex));
                }
            }).thenCompose(v -> refreshFoldersAndFiles());
            lastRefreshFuture = future;
            return future;
        }

        CompletableFuture<FolderAndFilesData> refreshFoldersAndFiles() {
            CompletableFuture<FolderAndFilesData> future = Edt.supplyAsync(
                    () -> new FolderAndFilesData(Objects.requireNonNullElse(loadConfiguredFolders(), List.of()),
                            Objects.requireNonNullElse(loadDiscoveredFiles(), List.of()),
                            Objects.requireNonNullElse(loadReferenceFolderMounts(), List.of())),
                    data -> {
                        UiComponents.replaceListItems(folderListModel, data.folders());
                        UiComponents.replaceListItems(fileListModel, data.files());
                        UiComponents.replaceListItems(referenceFolderListModel, data.referenceFolders());
                        btnRemoveFolder.setEnabled(folderList.getSelectedValue() != null);
                        btnRemoveReferenceFolder.setEnabled(referenceFolderList.getSelectedValue() != null);
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
                return projectService.getGlobalContextFolders();
            }
            return projectService.getProjectContextFolders();
        }

        private void saveConfiguredFolders(List<String> folders) {
            if (globalScope) {
                projectService.setGlobalContextFolders(folders);
            } else {
                projectService.setProjectContextFolders(folders);
            }
        }

        private List<ContextFileItem> loadDiscoveredFiles() {
            if (globalScope) {
                return promptService.listGlobalContextFiles();
            }
            return promptService.listProjectContextFiles();
        }

        private List<String> loadConfiguredReferenceFolders() {
            if (globalScope) {
                return projectService.getGlobalReferenceFolders();
            }
            return projectService.getProjectReferenceFolders();
        }

        private void saveConfiguredReferenceFolders(List<String> folders) {
            if (globalScope) {
                projectService.setGlobalReferenceFolders(folders);
            } else {
                projectService.setProjectReferenceFolders(folders);
            }
        }

        private List<ReferenceFolderMount> loadReferenceFolderMounts() {
            if (globalScope) {
                return projectService.listGlobalReferenceFolderMounts();
            }
            return projectService.listProjectReferenceFolderMounts();
        }
    }
}
