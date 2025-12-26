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
import java.awt.event.ActionEvent;
import java.nio.file.Paths;

@Singleton
public class MainFrame extends JFrame implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(MainFrame.class);

    // Dependencies
    private final GitService gitService;
    private final GenAIService genAIService;

    // Outlets (Mapped from XML IDs)
    @Outlet private JLabel gitBranchLabel;
    @Outlet private JTree fileTree;
    @Outlet private JTextField inputField;
    @Outlet private JButton sendButton;
    @Outlet private JScrollPane chatScrollPane;
    @Outlet private JButton rescanButton; // <--- NEW

    private final MarkdownPane chatArea = new MarkdownPane();

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

        // --- STARTUP SCAN ---
        performRescan();

        setSize(1200, 800);
        setLocationRelativeTo(null);
        setVisible(true);
    }

    private void initGitInfo() {
        if (gitBranchLabel != null) {
            String branch = gitService.getCurrentBranch(Paths.get("."));
            gitBranchLabel.setText(branch != null ? branch : "No Git Repo");
        }
    }

    private void initListeners() {
        if (sendButton != null) sendButton.addActionListener(this::onSend);
        if (inputField != null) inputField.addActionListener(this::onSend);
        if (rescanButton != null) rescanButton.addActionListener(e -> performRescan()); // <--- WIRE BUTTON
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
}