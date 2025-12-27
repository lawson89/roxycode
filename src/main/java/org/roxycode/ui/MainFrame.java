package org.roxycode.ui;

import com.formdev.flatlaf.FlatLightLaf;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.httprpc.sierra.Outlet;
import org.httprpc.sierra.UILoader;
import org.kordamp.ikonli.bootstrapicons.BootstrapIcons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.core.GenAIService;
import org.roxycode.core.GitService;
import org.roxycode.core.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
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

    private Path currentProjectRoot;

    // Attachments state
    private final List<File> attachedFiles = new ArrayList<>();

    // Outlets (Mapped from XML IDs)
    @Outlet
    private JLabel gitBranchLabel;
    @Outlet
    private JLabel projectNameLabel;
    @Outlet
    private JTree fileTree;
    @Outlet
    private JTextArea inputField;
    @Outlet
    private JButton sendButton;
    @Outlet
    private JScrollPane chatScrollPane;
    @Outlet
    private JButton rescanButton;
    @Outlet
    private JMenuItem settingsMenuItem;
    @Outlet
    private JLabel currentProjectLabel;
    @Outlet
    private JMenuItem exitMenuItem;
    @Outlet
    private JMenuItem aboutMenuItem;
    @Outlet
    private JMenuItem openFolderMenuItem;

    // Attachments Outlets
    @Outlet
    private JButton attachButton;
    @Outlet
    private JLabel attachmentsLabel;
    @Outlet
    private JButton clearAttachmentsButton;

    // Navigation Outlets
    @Outlet private JButton navChatButton;
    @Outlet private JButton navFilesButton;
    @Outlet private JButton navSettingsButton;

    // View Outlets
    @Outlet private JComponent viewChat;
    @Outlet private JComponent viewFiles;
    @Outlet private JComponent viewSettings;

    // Settings Outlets
    @Outlet
    private JPasswordField apiKeyField;
    @Outlet
    private JTextField maxTurnsField;
    @Outlet
    private JButton saveSettingsButton;
    @Outlet
    private JLabel icon;

    private final MarkdownPane chatArea = new MarkdownPane();

    @Inject
    public MainFrame(GitService gitService, GenAIService genAIService, SettingsService settingsService) {
        this.gitService = gitService;
        this.genAIService = genAIService;
        this.settingsService = settingsService;

        setTitle("RoxyCode AI Environment");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    }

    @Override
    public void run() {
        FlatLightLaf.setup();
        setContentPane(UILoader.load(this, "MainFrame.xml")); // Ensure leading slash for classpath
        FontIcon alarmIcon = FontIcon.of(BootstrapIcons.CHAT, 24);
        if (icon != null) icon.setIcon(alarmIcon);

        // Manual Viewport injection
        if (chatScrollPane != null) {
            chatScrollPane.setViewportView(chatArea);
        }

        // Initialize project root to current directory
        currentProjectRoot = FileSystems.getDefault().getPath("").toAbsolutePath();

        initGitInfo();
        initListeners();
        initSettings();
        populateFileTree(); // Populate the file tree

        // --- STARTUP SCAN ---
        performRescan();

        // Set the project path label
        updateProjectLabel();

        // Set initial view
        showView("CHAT");

        setSize(1200, 800);
        setLocationRelativeTo(null);
        setVisible(true);
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
        if (sendButton != null) sendButton.addActionListener(this::onSend);
        
        // Attachments listeners
        if (attachButton != null) attachButton.addActionListener(this::onAttach);
        if (clearAttachmentsButton != null) clearAttachmentsButton.addActionListener(this::onClearAttachments);

        // Navigation listeners
        if (navChatButton != null) navChatButton.addActionListener(e -> showView("CHAT"));
        if (navFilesButton != null) navFilesButton.addActionListener(e -> showView("FILES"));
        if (navSettingsButton != null) navSettingsButton.addActionListener(e -> showView("SETTINGS"));

        // FIX: Use KeyBindings for JTextArea
        if (inputField != null) {
            // Map "Enter" key to the 'onSend' method
            inputField.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "send-message");
            inputField.getActionMap().put("send-message", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    onSend(e);
                }
            });

            // Ensure "Shift+Enter" still creates a new line
            inputField.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("shift ENTER"), "insert-break");
        }
        if (rescanButton != null) rescanButton.addActionListener(e -> performRescan());
        if (settingsMenuItem != null) settingsMenuItem.addActionListener(this::onSettings);
        if (exitMenuItem != null) exitMenuItem.addActionListener(e -> System.exit(0));
        if (aboutMenuItem != null) aboutMenuItem.addActionListener(this::onAbout);
        if (openFolderMenuItem != null) openFolderMenuItem.addActionListener(this::onOpenFolder);

        if (saveSettingsButton != null) saveSettingsButton.addActionListener(this::onSaveSettings);
    }

    private void showView(String viewName) {
        // Hide all
        if(viewChat != null) viewChat.setVisible(false);
        if(viewFiles != null) viewFiles.setVisible(false);
        if(viewSettings != null) viewSettings.setVisible(false);

        // Show selected
        switch (viewName) {
            case "CHAT": if(viewChat != null) viewChat.setVisible(true); break;
            case "FILES": if(viewFiles != null) viewFiles.setVisible(true); break;
            case "SETTINGS": if(viewSettings != null) viewSettings.setVisible(true); break;
        }
    }

    private void initSettings() {
        if (apiKeyField != null) {
            apiKeyField.setText(settingsService.getGeminiApiKey());
        }
        if (maxTurnsField != null) {
            maxTurnsField.setText(String.valueOf(settingsService.getMaxTurns()));
        }
    }

    private void onAttach(ActionEvent e) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setMultiSelectionEnabled(true);
        fileChooser.setCurrentDirectory(currentProjectRoot.toFile());

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File[] files = fileChooser.getSelectedFiles();
            for (File f : files) {
                if (!attachedFiles.contains(f)) {
                    attachedFiles.add(f);
                }
            }
            updateAttachmentsLabel();
        }
    }

    private void onClearAttachments(ActionEvent e) {
        attachedFiles.clear();
        updateAttachmentsLabel();
    }

    private void updateAttachmentsLabel() {
        if (attachmentsLabel == null) return;
        if (attachedFiles.isEmpty()) {
            attachmentsLabel.setText("None");
        } else {
            String names = attachedFiles.stream()
                    .map(File::getName)
                    .collect(Collectors.joining(", "));
            attachmentsLabel.setText(names);
        }
    }

    private void onSaveSettings(ActionEvent e) {
        Component parent = this;
        if (apiKeyField != null) {
            String key = new String(apiKeyField.getPassword()).trim();
            settingsService.setGeminiApiKey(key);
        }

        if (maxTurnsField != null) {
            try {
                String txt = maxTurnsField.getText().trim();
                if (!txt.isEmpty()) {
                    int turns = Integer.parseInt(txt);
                    if (turns > 0) {
                        settingsService.setMaxTurns(turns);
                    } else {
                        JOptionPane.showMessageDialog(parent, "Max Turns must be positive.", "Error", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(parent, "Invalid number for Max Turns.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        JOptionPane.showMessageDialog(parent, "Settings saved.", "Settings", JOptionPane.INFORMATION_MESSAGE);
    }

    private void onOpenFolder(ActionEvent e) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setCurrentDirectory(currentProjectRoot.toFile());

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            currentProjectRoot = selectedFile.toPath().toAbsolutePath();

            updateProjectLabel();
            initGitInfo();
            populateFileTree();
            performRescan();

            if (chatArea != null) {
                chatArea.appendMarkdown("*System: Switched project to " + currentProjectRoot.toString() + "*");
            }
        }
    }

    private void performRescan() {
        log.info("Triggering Knowledge Rescan for " + currentProjectRoot);
        // Run in background to not block UI startup
        new Thread(() -> {
            genAIService.refreshKnowledge(currentProjectRoot.toString());
            SwingUtilities.invokeLater(() -> {
                if (chatArea != null) {
                    chatArea.appendMarkdown("*System: Knowledge base reloaded.*");
                }
            });
        }).start();
    }

    private void onSend(ActionEvent e) {
        String prompt = inputField.getText().trim();
        if (prompt.isEmpty()) return;

        inputField.setText("");
        
        // Capture attachments state
        List<File> currentAttachments = new ArrayList<>(attachedFiles);
        
        // Clear UI attachments
        attachedFiles.clear();
        updateAttachmentsLabel();

        if (chatArea != null) {
            chatArea.appendMarkdown("**User:** " + prompt);
            if (!currentAttachments.isEmpty()) {
                 chatArea.appendMarkdown(" *(Attached: " + currentAttachments.stream().map(File::getName).collect(Collectors.joining(", ")) + ")*");
            }

            new Thread(() -> {
                String response = genAIService.chat(prompt, currentProjectRoot.toString(), currentAttachments, (toolLog) -> SwingUtilities.invokeLater(() -> chatArea.appendMarkdown(toolLog)));
                SwingUtilities.invokeLater(() -> chatArea.appendMarkdown("**Roxy:** " + response));
            }).start();
        }
    }

    private void onSettings(ActionEvent e) {
        showView("SETTINGS");
    }

    private void onAbout(ActionEvent e) {
        JOptionPane.showMessageDialog(MainFrame.this, "RoxyCode AI EnvironmentnVersion 1.0", "About RoxyCode", JOptionPane.INFORMATION_MESSAGE);
    }

    private void populateFileTree() {
        // Use the current working directory as the root
        File rootDir = currentProjectRoot.toFile();

        // Create the root node (Use folder name or "Project")
        String rootName = rootDir.getName().equals(".") ? "Project" : rootDir.getName();
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(rootName);

        // Recursively build the tree
        buildTreeNodes(root, rootDir);

        // Set the model to the JTree
        DefaultTreeModel treeModel = new DefaultTreeModel(root);
        fileTree.setModel(treeModel);
    }

    private void buildTreeNodes(DefaultMutableTreeNode node, File file) {
        File[] files = file.listFiles();
        if (files == null) return;

        // Sort: Directories first, then files, alphabetically
        Arrays.sort(files, (f1, f2) -> {
            if (f1.isDirectory() && !f2.isDirectory()) return -1;
            if (!f1.isDirectory() && f2.isDirectory()) return 1;
            return f1.getName().compareToIgnoreCase(f2.getName());
        });

        for (File child : files) {
            // Skip hidden files and the .git folder to keep UI clean
            if (child.isHidden() || child.getName().startsWith(".")) continue;

            DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(child.getName());
            node.add(childNode);

            if (child.isDirectory()) {
                buildTreeNodes(childNode, child);
            }
        }
    }

}
