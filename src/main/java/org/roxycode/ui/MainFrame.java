package org.roxycode.ui;

import com.formdev.flatlaf.FlatLaf;
import com.google.genai.types.Content;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.httprpc.sierra.Outlet;
import org.httprpc.sierra.UILoader;
import org.kordamp.ikonli.materialdesign2.*;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.core.*;
import org.roxycode.core.tools.service.GitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.InterruptedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Singleton
public class MainFrame extends JFrame implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(MainFrame.class);

    // Dependencies
    private final GitService gitService;

    private final GenAIService genAIService;

    private final HistoryService historyService;

    private final SettingsService settingsService;

    private final UsageService usageService;

    private final RoxyProjectService roxyProjectService;

    private final Sandbox sandbox;

    private final ThemeService themeService;

    private Path currentProjectRoot;

    private final List<File> attachedFiles = new ArrayList<>();

    private final MarkdownPane chatArea = new MarkdownPane();

    // --- OUTLETS ---
    // Container for Views (New Outlet)
    @Outlet
    private JComponent mainContentStack;

    // Header & Shell Outlets
    @Outlet
    private JLabel gitBranchLabel;

    @Outlet
    private JLabel roxyModeLabel;

    @Outlet
    private JLabel currentModelLabel;

    @Outlet
    private JLabel projectNameLabel;

    @Outlet
    private JLabel currentProjectLabel;

    @Outlet
    private JLabel icon;

    // Navigation Outlets
    @Outlet
    private JToggleButton navChatButton;

    @Outlet
    private JToggleButton navFilesButton;

    @Outlet
    private JToggleButton navUsageButton;

    @Outlet
    private JToggleButton navSettingsButton;

    // Menu Outlets
    @Outlet
    private JMenuItem settingsMenuItem;

    @Outlet
    private JMenuItem exitMenuItem;

    @Outlet
    private JMenuItem aboutMenuItem;

    @Outlet
    private JMenuItem openFolderMenuItem;

    @Outlet
    private JToggleButton navSystemPromptButton;

    @Outlet
    private JToggleButton navMessageHistoryButton;

    @Outlet
    private JToggleButton navSummaryQueueButton;

    // -- VIEW: CHAT --
    @Outlet
    private JComponent viewChat;

    @Outlet
    private JTextArea inputField;

    @Outlet
    private JButton sendButton;

    @Outlet
    private JButton stopButton;

    @Outlet
    private JScrollPane chatScrollPane;

    @Outlet
    private JButton attachButton;

    @Outlet
    private JPanel attachmentsContainer;

    @Outlet
    private JButton clearAttachmentsButton;

    @Outlet
    private JLabel msgCountLabel;

    @Outlet
    private JLabel inTokenLabel;

    @Outlet
    private JLabel outTokenLabel;

    // -- VIEW: FILES --
    @Outlet
    private JComponent viewFiles;

    @Outlet
    private JTree fileTree;

    // -- VIEW: USAGE --
    @Outlet
    private JComponent viewUsage;

    @Outlet
    private JLabel usageHtmlLabel;

    @Outlet
    private JButton resetUsageButton;

    // -- VIEW: SETTINGS --
    @Outlet
    private JComponent viewSettings;

    @Outlet
    private JPasswordField apiKeyField;

    @Outlet
    private JTextField maxTurnsField;

    @Outlet
    private JTextField historyThresholdField;

    @Outlet
    private JTextField compactionChunkSizeField;

    @Outlet
    private JTextField maxSummaryChunksField;

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

    // -- VIEW: SYSTEM PROMPT --
    @Outlet
    private JComponent viewSystemPrompt;

    private final MarkdownPane systemPromptArea = new MarkdownPane();

    @Outlet
    private JScrollPane systemPromptScrollPane;

    @Outlet
    private JButton refreshSystemPromptButton;

    @Outlet
    private JComponent viewMessageHistory;

    private final MarkdownPane messageHistoryArea = new MarkdownPane();

    @Outlet
    private JScrollPane messageHistoryScrollPane;

    @Outlet
    private JButton refreshMessageHistoryButton;

    // -- VIEW: SUMMARY QUEUE --
    @Outlet
    private JComponent viewSummaryQueue;

    @Outlet
    private JButton refreshSummaryQueueButton;

    @Outlet
    private JScrollPane summaryQueueScrollPane;

    private final MarkdownPane summaryQueueArea = new MarkdownPane();

    // -- VIEW: LOGS --
    @Outlet
    private JComponent viewLogs;

    @Outlet
    private JScrollPane logsScrollPane;

    @Outlet
    private JButton refreshLogsButton;

    @Outlet
    private JToggleButton navLogsButton;

    private final JTextArea logsArea = new JTextArea();

    private final LogCaptureService logCaptureService;

    @Inject
    public MainFrame(GitService gitService, GenAIService genAIService, HistoryService historyService, SettingsService settingsService, UsageService usageService, RoxyProjectService roxyProjectService, Sandbox sandbox, ThemeService themeService, LogCaptureService logCaptureService) {
        this.gitService = gitService;
        this.genAIService = genAIService;
        this.historyService = historyService;
        this.settingsService = settingsService;
        this.usageService = usageService;
        this.roxyProjectService = roxyProjectService;
        this.sandbox = sandbox;
        this.themeService = themeService;
        this.logCaptureService = logCaptureService;
        setTitle("RoxyCode");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
    }

    @Override
    public void run() {
        themeService.applyTheme(settingsService.getTheme(), chatArea, systemPromptArea, messageHistoryArea, summaryQueueArea);
        // 1. Load the Main Shell (Menu, Header, Nav, Empty Stack)
        setContentPane(UILoader.load(this, "MainFrame.xml"));
        // 2. Load individual Views and add them to the stack
        // Passing 'this' ensures the @Outlet fields in MainFrame are populated
        if (mainContentStack != null) {
            mainContentStack.add(UILoader.load(this, "ChatView.xml"));
            mainContentStack.add(UILoader.load(this, "FilesView.xml"));
            mainContentStack.add(UILoader.load(this, "UsageView.xml"));
            mainContentStack.add(UILoader.load(this, "SettingsView.xml"));
            mainContentStack.add(UILoader.load(this, "SystemPromptView.xml"));
            mainContentStack.add(UILoader.load(this, "MessageHistoryView.xml"));
            mainContentStack.add(UILoader.load(this, "SummaryQueueView.xml"));
            mainContentStack.add(UILoader.load(this, "LogsView.xml"));
        }
        // 3. Initialize UI Components
        initIcons();
        // Manual Viewport injection
        if (chatScrollPane != null) {
            chatScrollPane.setViewportView(chatArea);
        }
        if (systemPromptScrollPane != null) {
            systemPromptScrollPane.setViewportView(systemPromptArea);
        }
        if (messageHistoryScrollPane != null) {
            messageHistoryScrollPane.setViewportView(messageHistoryArea);
        }
        if (summaryQueueScrollPane != null) {
            summaryQueueScrollPane.setViewportView(summaryQueueArea);
        }
        if (logsScrollPane != null) {
            logsArea.setEditable(false);
            logsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            logsScrollPane.setViewportView(logsArea);
        }
        // Initialize logic
        currentProjectRoot = FileSystems.getDefault().getPath("").toAbsolutePath();
        sandbox.setRoot(currentProjectRoot.toString());
        roxyProjectService.ensureProjectStructure();
        initGitInfo();
        initListeners();
        initSettings();
        populateFileTree();
        // Startup Scan
        performRescan();
        updateProjectLabel();
        updateRoxyMode();
        // Set initial view
        showView("CHAT");
        setSize(1200, 800);
        setLocationRelativeTo(null);
        setVisible(true);
    }

    private void initIcons() {
        java.net.URL iconUrl = getClass().getResource("roxy_logo_transparent.png");
        if (iconUrl != null) {
            ImageIcon roxyIcon = new ImageIcon(iconUrl);
            Image img = roxyIcon.getImage();
            setIconImage(img);
            if (icon != null) {
                Image newImg = img.getScaledInstance(64, 64, Image.SCALE_SMOOTH);
                icon.setIcon(new ImageIcon(newImg));
            }
        } else {
            FontIcon alarmIcon = FontIcon.of(MaterialDesignC.CHAT_OUTLINE, 64);
            if (icon != null)
                icon.setIcon(alarmIcon);
        }
        if (currentModelLabel != null) {
            currentModelLabel.setIcon(FontIcon.of(MaterialDesignR.ROBOT_HAPPY_OUTLINE, 16));
            currentModelLabel.setIconTextGap(6);
            currentModelLabel.setText(settingsService.getGeminiModel());
        }
        if (msgCountLabel != null) {
            msgCountLabel.setIcon(FontIcon.of(MaterialDesignM.MESSAGE_TEXT_OUTLINE, 14));
            msgCountLabel.setIconTextGap(4);
        }
        if (inTokenLabel != null) {
            inTokenLabel.setIcon(FontIcon.of(MaterialDesignA.ARROW_UP_BOLD_OUTLINE, 14));
            inTokenLabel.setIconTextGap(4);
        }
        if (outTokenLabel != null) {
            outTokenLabel.setIcon(FontIcon.of(MaterialDesignA.ARROW_DOWN_BOLD_OUTLINE, 14));
            outTokenLabel.setIconTextGap(4);
        }
        if (navChatButton != null) {
            navChatButton.setIcon(FontIcon.of(MaterialDesignC.CHAT_OUTLINE, 16));
        }
        if (navFilesButton != null) {
            navFilesButton.setIcon(FontIcon.of(MaterialDesignF.FILE_TREE_OUTLINE, 16));
        }
        if (navUsageButton != null) {
            navUsageButton.setIcon(FontIcon.of(MaterialDesignC.CHART_LINE, 16));
        }
        if (navSettingsButton != null) {
            navSettingsButton.setIcon(FontIcon.of(MaterialDesignC.COG_OUTLINE, 16));
        }
        if (navSystemPromptButton != null) {
            navSystemPromptButton.setIcon(FontIcon.of(MaterialDesignR.ROBOT_OUTLINE, 16));
        }
        if (navMessageHistoryButton != null) {
            navMessageHistoryButton.setIcon(FontIcon.of(MaterialDesignM.MESSAGE_TEXT_CLOCK_OUTLINE, 16));
        }
        if (navSummaryQueueButton != null) {
            navSummaryQueueButton.setIcon(FontIcon.of(MaterialDesignA.ARCHIVE_OUTLINE, 16));
        }
        if (navLogsButton != null) {
            navLogsButton.setIcon(FontIcon.of(MaterialDesignF.FILE_DOCUMENT_OUTLINE, 16));
        }
        if (settingsMenuItem != null) {
            settingsMenuItem.setIcon(FontIcon.of(MaterialDesignC.COG_OUTLINE, 16));
        }
        if (exitMenuItem != null) {
            exitMenuItem.setIcon(FontIcon.of(MaterialDesignL.LOGOUT, 16));
        }
        if (aboutMenuItem != null) {
            aboutMenuItem.setIcon(FontIcon.of(MaterialDesignI.INFORMATION_OUTLINE, 16));
        }
        if (openFolderMenuItem != null) {
            openFolderMenuItem.setIcon(FontIcon.of(MaterialDesignF.FOLDER_OPEN_OUTLINE, 16));
        }
        if (stopButton != null) {
            stopButton.setIcon(FontIcon.of(MaterialDesignP.PAUSE_CIRCLE_OUTLINE, 16));
        }
        if (attachButton != null) {
            attachButton.setIcon(FontIcon.of(MaterialDesignA.ATTACHMENT, 16));
        }
        if (clearAttachmentsButton != null) {
            clearAttachmentsButton.setIcon(FontIcon.of(MaterialDesignC.CLOSE_CIRCLE_OUTLINE, 16));
        }
        if (sendButton != null) {
            sendButton.setIcon(FontIcon.of(MaterialDesignS.SEND_OUTLINE, 16));
        }
        if (resetUsageButton != null) {
            resetUsageButton.setIcon(FontIcon.of(MaterialDesignR.RELOAD, 16));
        }
        if (saveSettingsButton != null) {
            saveSettingsButton.setIcon(FontIcon.of(MaterialDesignC.CONTENT_SAVE_OUTLINE, 16));
        }
        if (refreshSystemPromptButton != null) {
            refreshSystemPromptButton.setIcon(FontIcon.of(MaterialDesignR.REFRESH, 16));
        }
        if (refreshMessageHistoryButton != null) {
            refreshMessageHistoryButton.setIcon(FontIcon.of(MaterialDesignR.REFRESH, 16));
        }
        if (refreshSummaryQueueButton != null) {
            refreshSummaryQueueButton.setIcon(FontIcon.of(MaterialDesignR.REFRESH, 16));
        }
        if (refreshLogsButton != null) {
            refreshLogsButton.setIcon(FontIcon.of(MaterialDesignR.REFRESH, 16));
        }
    }

    private void updateProjectLabel() {
        if (currentProjectLabel != null) {
            currentProjectLabel.setText(currentProjectRoot.toString());
        }
    }

    private void initGitInfo() {
        if (gitBranchLabel != null) {
            String branch = gitService.getCurrentBranch(currentProjectRoot);
            gitBranchLabel.setText(branch != null ? branch : "No Git Repo");
        }
        if (projectNameLabel != null) {
            projectNameLabel.setText(currentProjectRoot.getFileName().toString());
        }
    }

    private void updateRoxyMode() {
        if(roxyModeLabel != null) {
            roxyModeLabel.setText(genAIService.getRoxyMode().toString());
        }
    }

    private void initListeners() {
        addWindowListener(new java.awt.event.WindowAdapter() {

            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                confirmExit();
            }
        });
        if (sendButton != null)
            sendButton.addActionListener(this::onSend);
        if (stopButton != null)
            stopButton.addActionListener(this::onStopChat);
        if (attachButton != null)
            attachButton.addActionListener(this::onAttach);
        if (clearAttachmentsButton != null)
            clearAttachmentsButton.addActionListener(this::onClearAttachments);
        if (navChatButton != null)
            navChatButton.addActionListener(e -> showView("CHAT"));
        if (navFilesButton != null)
            navFilesButton.addActionListener(e -> showView("FILES"));
        if (navUsageButton != null)
            navUsageButton.addActionListener(e -> showView("USAGE"));
        if (navSettingsButton != null)
            navSettingsButton.addActionListener(e -> showView("SETTINGS"));
        if (navSystemPromptButton != null)
            navSystemPromptButton.addActionListener(e -> showView("SYSTEM_PROMPT"));
        if (navMessageHistoryButton != null)
            navMessageHistoryButton.addActionListener(e -> showView("MESSAGE_HISTORY"));
        if (navSummaryQueueButton != null)
            navSummaryQueueButton.addActionListener(e -> showView("SUMMARY_QUEUE"));
        if (navLogsButton != null)
            navLogsButton.addActionListener(e -> showView("LOGS"));
        if (resetUsageButton != null)
            resetUsageButton.addActionListener(e -> {
                usageService.reset();
                updateUsageView();
            });
        if (inputField != null) {
            inputField.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "send-message");
            inputField.getActionMap().put("send-message", new AbstractAction() {

                @Override
                public void actionPerformed(ActionEvent e) {
                    onSend(e);
                }
            });
            inputField.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("shift ENTER"), "insert-break");
        }
        if (settingsMenuItem != null)
            settingsMenuItem.addActionListener(this::onSettings);
        if (exitMenuItem != null)
            exitMenuItem.addActionListener(e -> confirmExit());
        if (aboutMenuItem != null)
            aboutMenuItem.addActionListener(this::onAbout);
        if (openFolderMenuItem != null)
            openFolderMenuItem.addActionListener(this::onOpenFolder);
        if (refreshSystemPromptButton != null)
            refreshSystemPromptButton.addActionListener(e -> onRefreshSystemPrompt());
        if (refreshMessageHistoryButton != null)
            refreshMessageHistoryButton.addActionListener(e -> updateMessageHistoryView());
        if (refreshSummaryQueueButton != null)
            refreshSummaryQueueButton.addActionListener(e -> updateSummaryQueueView());
        if (refreshLogsButton != null)
            refreshLogsButton.addActionListener(e -> updateLogsView());
        if (saveSettingsButton != null)
            saveSettingsButton.addActionListener(this::onSaveSettings);
    }

    private void showView(String viewName) {
        if (viewChat != null)
            viewChat.setVisible(false);
        if (viewFiles != null)
            viewFiles.setVisible(false);
        if (viewUsage != null)
            viewUsage.setVisible(false);
        if (viewSettings != null)
            viewSettings.setVisible(false);
        if (viewSystemPrompt != null)
            viewSystemPrompt.setVisible(false);
        if (viewMessageHistory != null)
            viewMessageHistory.setVisible(false);
        if (viewSummaryQueue != null)
            viewSummaryQueue.setVisible(false);
        if (viewLogs != null)
            viewLogs.setVisible(false);
        switch(viewName) {
            case "CHAT":
                if (viewChat != null)
                    viewChat.setVisible(true);
                break;
            case "FILES":
                if (viewFiles != null)
                    viewFiles.setVisible(true);
                break;
            case "USAGE":
                if (viewUsage != null) {
                    updateUsageView();
                    viewUsage.setVisible(true);
                }
                break;
            case "SETTINGS":
                if (viewSettings != null)
                    viewSettings.setVisible(true);
                break;
            case "SYSTEM_PROMPT":
                if (viewSystemPrompt != null) {
                    onRefreshSystemPrompt();
                    viewSystemPrompt.setVisible(true);
                }
                break;
            case "MESSAGE_HISTORY":
                if (viewMessageHistory != null) {
                    updateMessageHistoryView();
                    viewMessageHistory.setVisible(true);
                }
                break;
            case "SUMMARY_QUEUE":
                if (viewSummaryQueue != null) {
                    updateSummaryQueueView();
                    viewSummaryQueue.setVisible(true);
                }
                break;
            case "LOGS":
                if (viewLogs != null) {
                    updateLogsView();
                    viewLogs.setVisible(true);
                }
                break;
        }
    }

    private void updateUsageView() {
        if (usageHtmlLabel == null)
            return;
        String html = "<html><table border='0' cellspacing='0' cellpadding='8'>" + "<tr><td><b><font color='#888888'>API CALLS</font></b></td><td>" + usageService.getApiCalls() + "</td></tr>" + "<tr><td><b><font color='#888888'>TOTAL TOKENS</font></b></td><td>" + String.format("%,d", usageService.getTotalTokens()) + "</td></tr>" + "<tr><td><b><font color='#888888'>PROMPT TOKENS</font></b></td><td>" + String.format("%,d", usageService.getPromptTokens()) + "</td></tr>" + "<tr><td><b><font color='#888888'>CANDIDATE TOKENS</font></b></td><td>" + String.format("%,d", usageService.getCandidateTokens()) + "</td></tr>" + "<tr><td><b><font color='#888888'>ESTIMATED COST</font></b></td><td>" + String.format("$%.4f", usageService.getEstimatedCost()) + "</td></tr>" + "</table></html>";
        usageHtmlLabel.setText(html);
    }

    private void updateChatStats() {
        int msgCount = genAIService.getHistory().size();
        int in = genAIService.getInTokens();
        int out = genAIService.getOutTokens();
        if (msgCountLabel != null) {
            msgCountLabel.setText(String.format("%d Messages", msgCount));
        }
        if (inTokenLabel != null) {
            inTokenLabel.setText(String.format("%d In", in));
        }
        if (outTokenLabel != null) {
            outTokenLabel.setText(String.format("%d Out", out));
        }
    }

    private void initSettings() {
        if (apiKeyField != null)
            apiKeyField.setText(settingsService.getGeminiApiKey());
        if (maxTurnsField != null)
            maxTurnsField.setText(String.valueOf(settingsService.getMaxTurns()));
                if (logLinesCountField != null)
            logLinesCountField.setText(String.valueOf(settingsService.getLogLinesCount()));
        if (logAutoScrollCheckBox != null)
            logAutoScrollCheckBox.setSelected(settingsService.isLogAutoScroll());
        if (themeComboBox != null) {
            themeComboBox.removeAllItems();
            themeComboBox.addItem("Light");
            themeComboBox.addItem("Dark");
            themeComboBox.addItem("IntelliJ");
            themeComboBox.addItem("Darcula");
            themeComboBox.setSelectedItem(settingsService.getTheme());
        }
        if (modelComboBox != null) {
            modelComboBox.removeAllItems();
            modelComboBox.addItem("gemini-3-pro-preview");
            modelComboBox.addItem("gemini-3-flash-preview");
            modelComboBox.addItem("gemini-2.5-flash");
            modelComboBox.addItem("gemini-2.5-pro");
            modelComboBox.addItem("gemini-2.0-flash");
            modelComboBox.setSelectedItem(settingsService.getGeminiModel());
        }
        if (historyThresholdField != null)
            historyThresholdField.setText(String.valueOf(settingsService.getHistoryThreshold()));
        if (compactionChunkSizeField != null)
            compactionChunkSizeField.setText(String.valueOf(settingsService.getCompactionChunkSize()));
        if (maxSummaryChunksField != null)
            maxSummaryChunksField.setText(String.valueOf(settingsService.getMaxSummaryChunks()));
    }

    private void onStopChat(ActionEvent e) {
        genAIService.stopChat();
        stopButton.setEnabled(false);
    }

    private void onAttach(ActionEvent e) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setMultiSelectionEnabled(true);
        fileChooser.setCurrentDirectory(currentProjectRoot.toFile());
        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            for (File f : fileChooser.getSelectedFiles()) {
                if (!attachedFiles.contains(f))
                    attachedFiles.add(f);
            }
            updateAttachmentsLabel();
        }
    }

    private void onClearAttachments(ActionEvent e) {
        attachedFiles.clear();
        updateAttachmentsLabel();
    }

    private MaterialDesignF getIconForFile(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".java") || name.endsWith(".py") || name.endsWith(".js") || name.endsWith(".html") || name.endsWith(".css") || name.endsWith(".xml") || name.endsWith(".json")) {
            return MaterialDesignF.FILE_CODE_OUTLINE;
        } else if (name.endsWith(".pdf")) {
            return MaterialDesignF.FILE_PDF_BOX;
        } else if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".gif")) {
            return MaterialDesignF.FILE_IMAGE_OUTLINE;
        } else if (name.endsWith(".txt") || name.endsWith(".md")) {
            return MaterialDesignF.FILE_DOCUMENT_OUTLINE;
        }
        return MaterialDesignF.FILE_OUTLINE;
    }

    private void updateAttachmentsLabel() {
        if (attachmentsContainer == null)
            return;
        attachmentsContainer.removeAll();
        if (attachedFiles.isEmpty()) {
            attachmentsContainer.add(new JLabel("None"));
        } else {
            for (File file : attachedFiles) {
                JLabel label = new JLabel(file.getName());
                label.setIcon(FontIcon.of(getIconForFile(file), 16));
                label.setIconTextGap(4);
                attachmentsContainer.add(label);
            }
        }
        attachmentsContainer.revalidate();
        attachmentsContainer.repaint();
    }

    private void onSaveSettings(ActionEvent e) {
        if (apiKeyField != null)
            settingsService.setGeminiApiKey(new String(apiKeyField.getPassword()).trim());
        try {
            if (maxTurnsField != null)
                settingsService.setMaxTurns(Integer.parseInt(maxTurnsField.getText().trim()));
                        if (logLinesCountField != null)
                settingsService.setLogLinesCount(Integer.parseInt(logLinesCountField.getText().trim()));
            if (logAutoScrollCheckBox != null)
                settingsService.setLogAutoScroll(logAutoScrollCheckBox.isSelected());
            if (historyThresholdField != null)
                settingsService.setHistoryThreshold(Integer.parseInt(historyThresholdField.getText().trim()));
            if (compactionChunkSizeField != null)
                settingsService.setCompactionChunkSize(Integer.parseInt(compactionChunkSizeField.getText().trim()));
            if (maxSummaryChunksField != null)
                settingsService.setMaxSummaryChunks(Integer.parseInt(maxSummaryChunksField.getText().trim()));
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Please enter valid numbers.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (themeComboBox != null) {
            String theme = (String) themeComboBox.getSelectedItem();
            settingsService.setTheme(theme);
            themeService.applyTheme(theme, chatArea, systemPromptArea, messageHistoryArea, summaryQueueArea);
        }
        if (modelComboBox != null) {
            String model = (String) modelComboBox.getSelectedItem();
            settingsService.setGeminiModel(model);
            if (currentModelLabel != null)
                currentModelLabel.setText(model);
        }
        JOptionPane.showMessageDialog(this, "Settings saved.", "Settings", JOptionPane.INFORMATION_MESSAGE);
    }

    private void onOpenFolder(ActionEvent e) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setCurrentDirectory(currentProjectRoot.toFile());
        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            currentProjectRoot = fileChooser.getSelectedFile().toPath().toAbsolutePath();
            sandbox.setRoot(currentProjectRoot.toString());
            roxyProjectService.ensureProjectStructure();
            genAIService.clearHistory();
            updateProjectLabel();
            initGitInfo();
            populateFileTree();
            performRescan();
            if (chatArea != null)
                chatArea.appendMarkdown("*System: Switched project to " + currentProjectRoot.toString() + "*");
        }
    }

    private void performRescan() {
        log.info("Triggering Knowledge Rescan for {}", currentProjectRoot);
        new Thread(() -> {
            genAIService.refreshKnowledge(currentProjectRoot.toString());
            SwingUtilities.invokeLater(() -> {
                if (chatArea != null)
                    chatArea.appendMarkdown("*System: Knowledge base reloaded.*");
            });
        }).start();
    }

    private boolean isTimeout(Throwable t) {
        while (t != null) {
            if (t instanceof InterruptedIOException)
                return true;
            if (t.getMessage() != null && t.getMessage().toLowerCase().contains("timeout"))
                return true;
            t = t.getCause();
        }
        return false;
    }

    private void setInputEnabled(boolean enabled) {
        if (sendButton != null)
            sendButton.setEnabled(enabled);
        if (stopButton != null)
            stopButton.setEnabled(!enabled);
        if (inputField != null) {
            inputField.setEnabled(enabled);
            if (enabled)
                inputField.requestFocusInWindow();
        }
        if (attachButton != null)
            attachButton.setEnabled(enabled);
        if (clearAttachmentsButton != null)
            clearAttachmentsButton.setEnabled(enabled);
    }

    private void onSend(ActionEvent e) {
        String prompt = inputField.getText().trim();
        if (prompt.isEmpty())
            return;
        inputField.setText("");
        List<File> currentAttachments = new ArrayList<>(attachedFiles);
        attachedFiles.clear();
        updateAttachmentsLabel();
        if (chatArea != null) {
            chatArea.appendMarkdown("**User:** " + prompt);
            if (!currentAttachments.isEmpty())
                chatArea.appendMarkdown(" *(Attached: " + currentAttachments.size() + " files)*");
            setInputEnabled(false);
            new Thread(() -> {
                try {
                    String response = genAIService.chat(prompt, currentProjectRoot.toString(), currentAttachments, (status) -> SwingUtilities.invokeLater(() -> {
                        if (status.startsWith("Thinking"))
                            chatArea.appendStatus(status);
                        else
                            chatArea.appendToolLog(status);
                        updateChatStats();
                    }));
                    SwingUtilities.invokeLater(() -> {
                        chatArea.appendRoxyMarkdown(response);
                        updateChatStats();
                        updateRoxyMode();
                    });
                } catch (Exception ex) {
                    log.error("Chat error", ex);
                    SwingUtilities.invokeLater(() -> {
                        if (isTimeout(ex))
                            chatArea.appendMarkdown("⏱️ **Request Timeout**");
                        else
                            chatArea.appendMarkdown("❌ **Error:** " + ex.getMessage());
                    });
                } finally {
                    SwingUtilities.invokeLater(() -> {
                        setInputEnabled(true);
                        updateChatStats();
                    });
                }
            }).start();
        }
    }

    private void onSettings(ActionEvent e) {
        showView("SETTINGS");
    }

    private void onAbout(ActionEvent e) {
        JOptionPane.showMessageDialog(this, "RoxyCode AInVersion 1.0", "About", JOptionPane.INFORMATION_MESSAGE);
    }

    private void onRefreshSystemPrompt() {
        if (systemPromptArea != null) {
            systemPromptArea.setMarkdown("*Generating system prompt...*");
            new Thread(() -> {
                String prompt = genAIService.buildSystemContext(currentProjectRoot.toString(), new ArrayList<>(attachedFiles));
                SwingUtilities.invokeLater(() -> systemPromptArea.setMarkdown(prompt));
            }).start();
        }
    }

    private void confirmExit() {
        log.info("Prompting for exit confirmation");
        JOptionPane optionPane = new JOptionPane("Are you sure you want to exit RoxyCode?", JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION);
        JDialog dialog = optionPane.createDialog(this, "Confirm Exit");
        // Force center on screen to avoid positioning bugs relative to MainFrame
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
        Object selectedValue = optionPane.getValue();
        if (selectedValue instanceof Integer && (Integer) selectedValue == JOptionPane.YES_OPTION) {
            System.exit(0);
        }
    }

    private void populateFileTree() {
        File rootDir = currentProjectRoot.toFile();
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(rootDir.getName().equals(".") ? "Project" : rootDir.getName());
        buildTreeNodes(root, rootDir);
        fileTree.setModel(new DefaultTreeModel(root));
    }

    private void buildTreeNodes(DefaultMutableTreeNode node, File file) {
        File[] files = file.listFiles();
        if (files == null)
            return;
        Arrays.sort(files, (f1, f2) -> {
            if (f1.isDirectory() && !f2.isDirectory())
                return -1;
            if (!f1.isDirectory() && f2.isDirectory())
                return 1;
            return f1.getName().compareToIgnoreCase(f2.getName());
        });
        for (File child : files) {
            if (child.isHidden() || child.getName().startsWith("."))
                continue;
            DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(child.getName());
            node.add(childNode);
            if (child.isDirectory())
                buildTreeNodes(childNode, child);
        }
    }

    private void updateMessageHistoryView() {
        if (messageHistoryArea == null)
            return;
        List<Content> history = new ArrayList<>(genAIService.getHistory());
        Collections.reverse(history);
        StringBuilder html = new StringBuilder();
        html.append("<table width='100%' border='0' cellspacing='0' cellpadding='10'>");
        for (Content content : history) {
            html.append(historyService.renderContentToHtmlRow(content, FlatLaf.isLafDark(), messageHistoryArea::markdownToHtml));
        }
        html.append("</table>");
        messageHistoryArea.setHtml(html.toString());
    }

    private void updateSummaryQueueView() {
        if (summaryQueueArea == null)
            return;
        List<String> queue = historyService.getSummaryQueue();
        StringBuilder md = new StringBuilder();
        md.append("# Summary Queue (").append(queue.size()).append(" segments)\n\n");
        if (queue.isEmpty()) {
            md.append("*The queue is empty. No history compaction has occurred yet.*");
        } else {
            int i = 1;
            for (String summary : queue) {
                md.append("## Segment ").append(i++).append("\n");
                md.append(summary).append("\n\n");
                md.append("---\n");
            }
        }
        summaryQueueArea.setMarkdown(md.toString());
    }

    private void updateLogsView() {
        if (logsArea == null)
            return;
        List<String> logs = logCaptureService.getLogs(settingsService.getLogLinesCount());
        logsArea.setText(String.join("\n", logs));
        if (settingsService.isLogAutoScroll()) {
            logsArea.setCaretPosition(logsArea.getDocument().getLength());
        }
    }
}
