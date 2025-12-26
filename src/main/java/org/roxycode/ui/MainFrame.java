package org.roxycode.ui;

import com.formdev.flatlaf.FlatLightLaf;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.httprpc.sierra.Outlet;
import org.httprpc.sierra.UILoader;
import org.roxycode.core.GenAIService;
import org.roxycode.core.GitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.nio.file.Paths;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.FileSystems;
import java.util.Arrays;
import java.util.Map;


@Singleton
public class MainFrame extends JFrame implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(MainFrame.class);

    // Dependencies
    private final GitService gitService;
    private final GenAIService genAIService;

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
    private JButton settingsButton;
    @Outlet
    private JLabel currentProjectLabel;

    private final MarkdownPane chatArea = new MarkdownPane();

    private JDialog settingsDialog;

    @Inject
    public MainFrame(GitService gitService, GenAIService genAIService) {
        this.gitService = gitService;
        this.genAIService = genAIService;

        setTitle("RoxyCode AI Environment");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    }

    @Override
    public void run() {
        FlatLightLaf.setup();
        setContentPane(UILoader.load(this, "MainFrame.xml")); // Ensure leading slash for classpath

        // Manual Viewport injection
        if (chatScrollPane != null) {
            chatScrollPane.setViewportView(chatArea);
        }

        initGitInfo();
        initListeners();
        populateFileTree(); // Populate the file tree

        // --- STARTUP SCAN ---
        performRescan();

        // Set the project path
        Path projectPath = FileSystems.getDefault().getPath("").toAbsolutePath();
        if (currentProjectLabel != null) {
            currentProjectLabel.setText(projectPath.toString());
        }

        setSize(1200, 800);
        setLocationRelativeTo(null);
        setVisible(true);
    }

    private void initGitInfo() {
        if (gitBranchLabel != null) {
            String branch = gitService.getCurrentBranch(Paths.get("."));
            gitBranchLabel.setText(branch != null ? branch : "No Git Repo");
        }
        if (projectNameLabel != null) {
            projectNameLabel.setText("roxycode");
        }
    }

    private void initListeners() {
        if (sendButton != null) sendButton.addActionListener(this::onSend);
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
        if (settingsButton != null) settingsButton.addActionListener(this::onSettings);
    }

    private void performRescan() {
        log.info("Triggering Knowledge Rescan...");
        // Run in background to not block UI startup
        new Thread(() -> {
            genAIService.refreshKnowledge(".");
            SwingUtilities.invokeLater(() -> {
                if (chatArea != null) {
                    chatArea.appendMarkdown("*System: Knowledge base reloaded from roxy_home.*");
                }
            });
        }).start();
    }

    private void onSend(ActionEvent e) {
        String prompt = inputField.getText().trim();
        if (prompt.isEmpty()) return;

        inputField.setText("");
        if (chatArea != null) {
            chatArea.appendMarkdown("**User:** " + prompt);

            new Thread(() -> {
                String response = genAIService.chat(prompt, ".");
                SwingUtilities.invokeLater(() -> {
                    chatArea.appendMarkdown("**Roxy:** " + response);
                });
            }).start();
        }
    }

    private void onSettings(ActionEvent e) {
        if (settingsDialog == null) {
            try {
                JPanel settingsPanel = (JPanel) UILoader.load(this, "SettingsPanel.xml");
                settingsDialog = new JDialog(this, "Settings", true);
                settingsDialog.setContentPane(settingsPanel);
                settingsDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
                settingsDialog.pack();
                settingsDialog.setLocationRelativeTo(this);
            } catch (Exception ex) {
                log.error("Failed to load settings panel", ex);
                JOptionPane.showMessageDialog(this, "Failed to load settings panel: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
        settingsDialog.setVisible(true);
    }

    public void closeSettings(ActionEvent e) {
        if (settingsDialog != null) {
            settingsDialog.dispose();
        }
    }

    private void populateFileTree() {
        // Use the current working directory as the root
        File rootDir = new File(".").getAbsoluteFile();

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
