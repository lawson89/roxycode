package org.roxycode.app.ui;

import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.service.PromptService;
import org.roxycode.app.service.PromptService.ContextFileItem;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.util.Edt;
import org.roxycode.app.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Panel for managing per-project context folders and previewing discovered top-level text files.
 */
@Component
@Lazy
public final class ContextManagerPanel extends BaseContextFolderPanel {
    private static final long serialVersionUID = 1L;

    public ContextManagerPanel(PromptService promptService, SettingsService settingsService) {
        super(promptService, settingsService, "Project Context Folders", false);
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        handleSettingsChanged(event);
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        Edt.run(
                () -> {
                    clearSelectionAndPreview();
                    refreshFoldersAndFiles();
                });
    }
}

/**
 * Shared base panel for Global Context and Project Context folder management and read-only preview.
 */
abstract class BaseContextFolderPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(BaseContextFolderPanel.class);

    protected final transient PromptService promptService;
    protected final transient SettingsService settingsService;
    private final boolean globalScope;

    private final DefaultListModel<String> folderListModel;
    private final JList<String> folderList;
    private final JButton btnAddFolder;
    private final JButton btnRemoveFolder;

    private final DefaultListModel<ContextFileItem> fileListModel;
    private final JList<ContextFileItem> fileList;
    private final RSyntaxTextArea textArea;

    private ContextFileItem currentFile;
    private String lastErrorMessage;
    private transient Consumer<String> errorDialogPresenter;

    record FolderAndFilesData(List<String> folders, List<ContextFileItem> files) {}

    protected BaseContextFolderPanel(
            PromptService promptService,
            SettingsService settingsService,
            String foldersHeaderTitle,
            boolean globalScope) {
        this.promptService = promptService;
        this.settingsService = settingsService;
        this.globalScope = globalScope;
        this.errorDialogPresenter =
                msg -> {
                    if (!GraphicsEnvironment.isHeadless()) {
                        JOptionPane.showMessageDialog(
                                BaseContextFolderPanel.this,
                                msg,
                                "Error",
                                JOptionPane.ERROR_MESSAGE);
                    }
                };
        setLayout(new BorderLayout());

        folderListModel = new DefaultListModel<>();
        folderList = new JList<>(folderListModel);
        folderList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane folderScroll = new JScrollPane(folderList);

        btnAddFolder = new JButton("Add Folder...");
        btnRemoveFolder = new JButton("Remove Folder");
        btnRemoveFolder.setEnabled(false);

        JPanel folderButtons = new JPanel(new GridLayout(1, 2, 5, 5));
        folderButtons.add(btnAddFolder);
        folderButtons.add(btnRemoveFolder);

        JPanel foldersPanel = new JPanel(new BorderLayout(0, 5));
        foldersPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        foldersPanel.add(new JLabel(foldersHeaderTitle), BorderLayout.NORTH);
        foldersPanel.add(folderScroll, BorderLayout.CENTER);
        foldersPanel.add(folderButtons, BorderLayout.SOUTH);

        fileListModel = new DefaultListModel<>();
        fileList = new JList<>(fileListModel);
        fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileList.setCellRenderer(
                new DefaultListCellRenderer() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public java.awt.Component getListCellRendererComponent(
                            JList<?> list,
                            Object value,
                            int index,
                            boolean isSelected,
                            boolean cellHasFocus) {
                        super.getListCellRendererComponent(
                                list, value, index, isSelected, cellHasFocus);
                        if (value instanceof ContextFileItem item) {
                            setText(item.fileName());
                            if (item.filePath() != null) {
                                setToolTipText(item.filePath().toString());
                            }
                        }
                        return this;
                    }
                });
        JScrollPane fileScroll = new JScrollPane(fileList);

        JPanel filesPanel = new JPanel(new BorderLayout(0, 5));
        filesPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        filesPanel.add(new JLabel("Discovered Text Files (Top-Level)"), BorderLayout.NORTH);
        filesPanel.add(fileScroll, BorderLayout.CENTER);

        JSplitPane leftSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, foldersPanel, filesPanel);
        leftSplit.setDividerLocation(180);
        leftSplit.setResizeWeight(0.4);

        textArea = new RSyntaxTextArea();
        textArea.setEditable(false);
        textArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
        textArea.setCodeFoldingEnabled(true);
        textArea.setAntiAliasingEnabled(true);
        updateTextAreaTheme();
        RTextScrollPane textScroll = new RTextScrollPane(textArea);

        JPanel rightPanel = new JPanel(new BorderLayout(0, 5));
        rightPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        rightPanel.add(new JLabel("File Preview (Read-Only)"), BorderLayout.NORTH);
        rightPanel.add(textScroll, BorderLayout.CENTER);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftSplit, rightPanel);
        mainSplit.setDividerLocation(280);
        add(mainSplit, BorderLayout.CENTER);

        setupListeners();
        refreshFoldersAndFiles();
    }

    protected void handleSettingsChanged(SettingsChangedEvent event) {
        Edt.run(
                () -> {
                    updateTextAreaTheme(
                            event != null && event.settings() != null
                                    ? event.settings().getTheme()
                                    : null);
                    if (globalScope) {
                        refreshFoldersAndFiles();
                    }
                });
    }

    protected void clearSelectionAndPreview() {
        currentFile = null; // NOPMD
        folderList.clearSelection();
        fileList.clearSelection();
        textArea.setText("");
        btnRemoveFolder.setEnabled(false);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (textArea != null) {
            updateTextAreaTheme();
        }
    }

    void updateTextAreaTheme() {
        SettingsPanel.applyRSyntaxTheme(textArea, settingsService);
    }

    void updateTextAreaTheme(String themeName) {
        SettingsPanel.applyRSyntaxTheme(textArea, themeName);
    }

    static String resolveSyntaxStyle(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return SyntaxConstants.SYNTAX_STYLE_NONE;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot >= 0 ? lower.substring(dot + 1) : "";
        return switch (ext) {
            case "md", "markdown" -> SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
            case "java" -> SyntaxConstants.SYNTAX_STYLE_JAVA;
            case "json" -> SyntaxConstants.SYNTAX_STYLE_JSON;
            case "xml", "pom" -> SyntaxConstants.SYNTAX_STYLE_XML;
            case "yml", "yaml" -> SyntaxConstants.SYNTAX_STYLE_YAML;
            case "py" -> SyntaxConstants.SYNTAX_STYLE_PYTHON;
            case "js", "mjs", "cjs" -> SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
            case "ts", "tsx" -> SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT;
            case "html", "htm" -> SyntaxConstants.SYNTAX_STYLE_HTML;
            case "css" -> SyntaxConstants.SYNTAX_STYLE_CSS;
            case "sh", "bash", "zsh" -> SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL;
            case "sql" -> SyntaxConstants.SYNTAX_STYLE_SQL;
            case "properties", "toml", "ini", "cfg", "conf" ->
                    SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE;
            default -> SyntaxConstants.SYNTAX_STYLE_NONE;
        };
    }

    protected void showError(String message) {
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

    private void setupListeners() {
        folderList.addListSelectionListener(
                e -> {
                    if (!e.getValueIsAdjusting()) {
                        btnRemoveFolder.setEnabled(folderList.getSelectedValue() != null);
                    }
                });

        fileList.addListSelectionListener(
                e -> {
                    if (!e.getValueIsAdjusting()) {
                        ContextFileItem selected = fileList.getSelectedValue();
                        if (selected != null) {
                            currentFile = selected;
                            loadSelectedFileAsync(selected);
                        } else {
                            currentFile = null; // NOPMD
                            textArea.setText("");
                        }
                    }
                });

        btnAddFolder.addActionListener(e -> chooseAndAddFolder());

        btnRemoveFolder.addActionListener(
                e -> {
                    String selectedFolder = folderList.getSelectedValue();
                    if (selectedFolder != null) {
                        removeFolderAsync(selectedFolder);
                    }
                });
    }

    private void chooseAndAddFolder() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle(
                globalScope ? "Select Global Context Folder" : "Select Project Context Folder");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selectedDir = chooser.getSelectedFile();
            if (selectedDir != null) {
                addFolderAsync(selectedDir.getAbsolutePath());
            }
        }
    }

    private <T> SwingWorker<T, Void> executeOperationAsync(
            Callable<T> backgroundTask,
            Consumer<T> onSuccess,
            String logVerb,
            String errorVerb,
            String target) {
        SwingWorker<T, Void> worker =
                new SwingWorker<>() {
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
                            showError(
                                    "Error "
                                            + errorVerb
                                            + " "
                                            + target
                                            + ": "
                                            + (root != null ? root.getMessage() : ex.getMessage()));
                        }
                    }
                };
        worker.execute();
        return worker;
    }

    SwingWorker<Void, Void> addFolderAsync(String folderPath) {
        return executeOperationAsync(
                () -> {
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
                },
                v -> refreshFoldersAndFiles(),
                "add context folder",
                "adding context folder",
                folderPath);
    }

    SwingWorker<Void, Void> removeFolderAsync(String folderPath) {
        return executeOperationAsync(
                () -> {
                    List<String> updated = new ArrayList<>(loadConfiguredFolders());
                    updated.remove(folderPath);
                    saveConfiguredFolders(updated);
                    return null;
                },
                v -> {
                    clearSelectionAndPreview();
                    refreshFoldersAndFiles();
                },
                "remove context folder",
                "removing context folder",
                folderPath);
    }

    SwingWorker<String, Void> loadSelectedFileAsync(ContextFileItem selected) {
        currentFile = selected;
        return executeOperationAsync(
                () -> promptService.readContextFile(selected.filePath()),
                content -> {
                    if (selected.equals(currentFile)) {
                        textArea.setSyntaxEditingStyle(resolveSyntaxStyle(selected.fileName()));
                        textArea.setText(content != null ? content : "");
                        textArea.setCaretPosition(0);
                    }
                },
                "read context file",
                "reading context file",
                selected.fileName());
    }

    SwingWorker<FolderAndFilesData, Void> refreshFoldersAndFiles() {
        SwingWorker<FolderAndFilesData, Void> worker =
                new SwingWorker<>() {
                    @Override
                    protected FolderAndFilesData doInBackground() {
                        List<String> folders = loadConfiguredFolders();
                        List<ContextFileItem> files = loadDiscoveredFiles();
                        return new FolderAndFilesData(
                                folders != null ? folders : List.of(),
                                files != null ? files : List.of());
                    }

                    @Override
                    protected void done() {
                        try {
                            FolderAndFilesData data = get();
                            folderListModel.clear();
                            for (String folder : data.folders()) {
                                folderListModel.addElement(folder);
                            }
                            fileListModel.clear();
                            for (ContextFileItem item : data.files()) {
                                fileListModel.addElement(item);
                            }
                            btnRemoveFolder.setEnabled(folderList.getSelectedValue() != null);
                        } catch (Exception ex) {
                            log.error("Failed to refresh context folders and files", ex);
                        }
                    }
                };
        worker.execute();
        return worker;
    }

    private List<String> loadConfiguredFolders() {
        return globalScope
                ? promptService.getGlobalContextFolders()
                : promptService.getProjectContextFolders();
    }

    private void saveConfiguredFolders(List<String> folders) {
        if (globalScope) {
            promptService.setGlobalContextFolders(folders);
        } else {
            promptService.setProjectContextFolders(folders);
        }
    }

    private List<ContextFileItem> loadDiscoveredFiles() {
        return globalScope
                ? promptService.listGlobalContextFiles()
                : promptService.listProjectContextFiles();
    }

    DefaultListModel<String> getFolderListModel() {
        return folderListModel;
    }

    JList<String> getFolderList() {
        return folderList;
    }

    DefaultListModel<ContextFileItem> getListModel() {
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

    RSyntaxTextArea getTextArea() {
        return textArea;
    }
}
