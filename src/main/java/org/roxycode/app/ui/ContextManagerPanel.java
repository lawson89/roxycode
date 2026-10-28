package org.roxycode.app.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
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

    static final String GLOBAL_SUBTITLE = "Shared across all projects";
    static final String PROJECT_SUBTITLE = "Scoped to active project";

    private final ContextScopeColumn globalColumn;
    private final ContextScopeColumn projectColumn;
    private String lastErrorMessage;
    private transient Consumer<String> errorDialogPresenter;

    public ContextManagerPanel(PromptService promptService) {
        this.errorDialogPresenter = msg -> {
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(ContextManagerPanel.this, msg, "Error", JOptionPane.ERROR_MESSAGE);
            }
        };
        setLayout(new GridLayout(1, 2, 12, 0));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        this.globalColumn = new ContextScopeColumn(promptService, "Global Context", true, this::showError);
        this.projectColumn = new ContextScopeColumn(promptService, "Project Context", false, this::showError);

        add(globalColumn);
        add(projectColumn);
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
        this.lastErrorMessage = message;
        if (errorDialogPresenter != null) {
            errorDialogPresenter.accept(message);
        }
    }

    void setErrorDialogPresenter(Consumer<String> presenter) {
        this.errorDialogPresenter = presenter;
    }

    String getLastErrorMessage() {
        return lastErrorMessage;
    }

    ContextScopeColumn getGlobalColumn() {
        return globalColumn;
    }

    ContextScopeColumn getProjectColumn() {
        return projectColumn;
    }

    static final class ContextScopeColumn extends JPanel {
        private static final long serialVersionUID = 1L;
        private static final Logger log = LoggerFactory.getLogger(ContextScopeColumn.class);

        private final transient PromptService promptService;
        private final boolean globalScope;
        private final transient Consumer<String> errorReporter;

        private final JPanel headerBanner;
        private final JLabel headerLabel;
        private final JLabel subtitleLabel;
        private final JLabel foldersSectionLabel;
        private final JLabel filesSectionLabel;

        private final DefaultListModel<String> folderListModel = new DefaultListModel<>();
        private final JList<String> folderList = new JList<>(folderListModel);
        private final JButton btnAddFolder = new JButton("Add Folder...");
        private final JButton btnRemoveFolder = new JButton("Remove Folder");

        private final DefaultListModel<ContextFileItem> fileListModel = new DefaultListModel<>();
        private final JList<ContextFileItem> fileList = new JList<>(fileListModel);

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

            subtitleLabel = new JLabel(globalScope ? GLOBAL_SUBTITLE : PROJECT_SUBTITLE);
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
                    setIcon(FontIcon.of(icon, 14, isSelected ? getForeground() : resolveAccentColor()));
                    setIconTextGap(6);
                    if (value instanceof ContextFileItem item) {
                        setText(item.fileName());
                        setToolTipText(item.filePath() != null ? item.filePath().toString() : null);
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
            return globalScope
                    ? SwingHtmlUtils.getAccentColor()
                    : SwingHtmlUtils.getUiColor(new Color(0x2e, 0x7d, 0x32), "Actions.Green");
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
            headerLabel.setIcon(FontIcon.of(globalScope ? Codicons.GLOBE : Codicons.FOLDER_OPENED, 18, accent));
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
            if (GraphicsEnvironment.isHeadless())
                return;
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle(globalScope ? "Select Global Context Folder" : "Select Project Context Folder");
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
                addFolderAsync(chooser.getSelectedFile().getAbsolutePath());
            }
        }

        private <T> SwingWorker<T, Void> executeOperationAsync(Callable<T> backgroundTask, Consumer<T> onSuccess,
                String logVerb, String errorVerb, String target) {
            SwingWorker<T, Void> worker = new SwingWorker<>() {
                @Override
                protected T doInBackground() throws Exception {
                    return backgroundTask.call();
                }

                @Override
                protected void done() {
                    try {
                        T result = get();
                        if (onSuccess != null) {
                            onSuccess.accept(result);
                        }
                    } catch (Exception ex) {
                        log.warn("Failed to {} {}: {}", logVerb, target, ex.getMessage());
                        Throwable root = ExceptionUtils.getRootCause(ex);
                        if (errorReporter != null && errorVerb != null) {
                            errorReporter.accept("Error " + errorVerb + " " + target + ": "
                                    + (root != null ? root.getMessage() : ex.getMessage()));
                        }
                    }
                }
            };
            worker.execute();
            return worker;
        }

        SwingWorker<Void, Void> addFolderAsync(String folderPath) {
            return executeOperationAsync(() -> {
                if (folderPath == null || folderPath.isBlank()) {
                    throw new IllegalArgumentException("Folder path must not be blank");
                }
                List<String> updated = new ArrayList<>(loadConfiguredFolders());
                String trimmed = folderPath.trim();
                if (!updated.contains(trimmed)) {
                    updated.add(trimmed);
                }
                saveConfiguredFolders(updated);
                return null;
            }, v -> refreshFoldersAndFiles(), "add context folder", "adding context folder", folderPath);
        }

        SwingWorker<Void, Void> removeFolderAsync(String folderPath) {
            return executeOperationAsync(() -> {
                List<String> updated = new ArrayList<>(loadConfiguredFolders());
                updated.remove(folderPath);
                saveConfiguredFolders(updated);
                return null;
            }, v -> {
                clearSelection();
                refreshFoldersAndFiles();
            }, "remove context folder", "removing context folder", folderPath);
        }

        SwingWorker<FolderAndFilesData, Void> refreshFoldersAndFiles() {
            return executeOperationAsync(() -> {
                List<String> folders = loadConfiguredFolders();
                List<ContextFileItem> files = loadDiscoveredFiles();
                return new FolderAndFilesData(folders != null ? folders : List.of(), files != null ? files : List.of());
            }, data -> {
                folderListModel.clear();
                data.folders().forEach(folderListModel::addElement);
                fileListModel.clear();
                data.files().forEach(fileListModel::addElement);
                btnRemoveFolder.setEnabled(folderList.getSelectedValue() != null);
            }, "refresh context folders and files", null, "context");
        }

        private List<String> loadConfiguredFolders() {
            return globalScope ? promptService.getGlobalContextFolders() : promptService.getProjectContextFolders();
        }

        private void saveConfiguredFolders(List<String> folders) {
            if (globalScope) {
                promptService.setGlobalContextFolders(folders);
            } else {
                promptService.setProjectContextFolders(folders);
            }
        }

        private List<ContextFileItem> loadDiscoveredFiles() {
            return globalScope ? promptService.listGlobalContextFiles() : promptService.listProjectContextFiles();
        }

        JPanel getHeaderBanner() {
            return headerBanner;
        }

        JLabel getHeaderLabel() {
            return headerLabel;
        }

        JLabel getSubtitleLabel() {
            return subtitleLabel;
        }

        JLabel getFoldersSectionLabel() {
            return foldersSectionLabel;
        }

        JLabel getFilesSectionLabel() {
            return filesSectionLabel;
        }

        DefaultListModel<String> getFolderListModel() {
            return folderListModel;
        }

        JList<String> getFolderList() {
            return folderList;
        }

        DefaultListModel<ContextFileItem> getFileListModel() {
            return fileListModel;
        }

        JList<ContextFileItem> getFileList() {
            return fileList;
        }

        JButton getAddFolderButton() {
            return btnAddFolder;
        }

        JButton getRemoveFolderButton() {
            return btnRemoveFolder;
        }
    }
}
