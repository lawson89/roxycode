package org.roxycode.ui;

import com.formdev.flatlaf.*;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.httprpc.sierra.Outlet;
import org.httprpc.sierra.UILoader;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.core.GenAIService;
import org.roxycode.core.tools.service.GitService;
import org.roxycode.core.RoxyProjectService;
import org.roxycode.core.Sandbox;
import org.roxycode.core.SettingsService;
import org.roxycode.core.UsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

@Singleton
public class MainFrame extends JFrame implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(MainFrame.class);

    // Dependencies
    private final GitService gitService;

    private final GenAIService genAIService;

    private final SettingsService settingsService;

    private final UsageService usageService;

    private final RoxyProjectService roxyProjectService;

    private final Sandbox sandbox;

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
    private JLabel currentModelLabel;

    @Outlet
    private JLabel projectNameLabel;

    @Outlet
    private JLabel currentProjectLabel;

    @Outlet
    private JButton rescanButton;

    @Outlet
    private JLabel icon;

    // Navigation Outlets
    @Outlet
    private JButton navChatButton;

    @Outlet
    private JButton navFilesButton;

    @Outlet
    private JButton navUsageButton;

    @Outlet
    private JButton navSettingsButton;

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
    private JButton navSystemPromptButton;

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
    private JLabel attachmentsLabel;

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

    @Inject
    public MainFrame(GitService gitService, GenAIService genAIService, SettingsService settingsService, UsageService usageService, RoxyProjectService roxyProjectService, Sandbox sandbox) {
        this.gitService = gitService;
        this.genAIService = genAIService;
        this.settingsService = settingsService;
        this.usageService = usageService;
        this.roxyProjectService = roxyProjectService;
        this.sandbox = sandbox;
        setTitle("RoxyCode");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
    }

    @Override
    public void run() {
        applyTheme(settingsService.getTheme());
        // 1. Load the Main Shell (Menu, Header, Nav, Empty Stack)
        setContentPane(UILoader.load(this, "MainFrame.xml"));
        // 2. Load individual Views and add them to the stack
        // Passing 'this' ensures the @Outlet fields in MainFrame are populated
        if (mainContentStack != null) {
            mainContentStack.add((JComponent) UILoader.load(this, "ChatView.xml"));
            mainContentStack.add((JComponent) UILoader.load(this, "FilesView.xml"));
            mainContentStack.add((JComponent) UILoader.load(this, "UsageView.xml"));
            mainContentStack.add((JComponent) UILoader.load(this, "SettingsView.xml"));
            mainContentStack.add((JComponent) UILoader.load(this, "SystemPromptView.xml"));
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
            inTokenLabel.setIcon(FontIcon.of(MaterialDesignA.ARROW_DOWN_BOLD_OUTLINE, 14));
            inTokenLabel.setIconTextGap(4);
        }
        if (outTokenLabel != null) {
            outTokenLabel.setIcon(FontIcon.of(MaterialDesignA.ARROW_UP_BOLD_OUTLINE, 14));
            outTokenLabel.setIconTextGap(4);
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
        if (rescanButton != null)
            rescanButton.addActionListener(e -> performRescan());
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
        }
    }

    private void updateUsageView() {
        if (usageHtmlLabel == null)
            return;
        StringBuilder html = new StringBuilder();
        html.append("<html><table border='0' cellspacing='0' cellpadding='8'>");
        html.append("<tr><td><b><font color='#888888'>API CALLS</font></b></td><td>").append(usageService.getApiCalls()).append("</td></tr>");
        html.append("<tr><td><b><font color='#888888'>TOTAL TOKENS</font></b></td><td>").append(String.format("%,d", usageService.getTotalTokens())).append("</td></tr>");
        html.append("<tr><td><b><font color='#888888'>PROMPT TOKENS</font></b></td><td>").append(String.format("%,d", usageService.getPromptTokens())).append("</td></tr>");
        html.append("<tr><td><b><font color='#888888'>CANDIDATE TOKENS</font></b></td><td>").append(String.format("%,d", usageService.getCandidateTokens())).append("</td></tr>");
        html.append("<tr><td><b><font color='#888888'>ESTIMATED COST</font></b></td><td>").append(String.format("$%.4f", usageService.getEstimatedCost())).append("</td></tr>");
        html.append("</table></html>");
        usageHtmlLabel.setText(html.toString());
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

    private void updateAttachmentsLabel() {
        if (attachmentsLabel == null)
            return;
        if (attachedFiles.isEmpty())
            attachmentsLabel.setText("None");
        else
            attachmentsLabel.setText(attachedFiles.stream().map(File::getName).collect(Collectors.joining(", ")));
    }

    private void onSaveSettings(ActionEvent e) {
        if (apiKeyField != null)
            settingsService.setGeminiApiKey(new String(apiKeyField.getPassword()).trim());
        try {
            if (maxTurnsField != null)
                settingsService.setMaxTurns(Integer.parseInt(maxTurnsField.getText().trim()));
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
            applyTheme(theme);
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
            if (t instanceof InterruptedIOException || t instanceof SocketTimeoutException)
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

    private void applyTheme(String themeName) {
        try {
            switch(themeName) {
                case "Dark":
                    UIManager.setLookAndFeel(new FlatDarkLaf());
                    break;
                case "IntelliJ":
                    UIManager.setLookAndFeel(new FlatIntelliJLaf());
                    break;
                case "Darcula":
                    UIManager.setLookAndFeel(new FlatDarculaLaf());
                    break;
                case "Light":
                default:
                    UIManager.setLookAndFeel(new FlatLightLaf());
                    break;
            }
            FlatLaf.updateUI();
            if (chatArea != null)
                chatArea.updateStyle();
            if (systemPromptArea != null)
                systemPromptArea.updateStyle();
        } catch (Exception ex) {
            log.error("Theme Error", ex);
        }
    }
}
