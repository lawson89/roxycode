package org.roxycode.ui;

import com.formdev.flatlaf.FlatLightLaf;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.httprpc.sierra.Outlet;
import org.httprpc.sierra.UILoader;
import org.roxycode.core.GenAIService;
import org.roxycode.core.GitService;
import org.roxycode.core.context.ContextRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.nio.file.Paths;

@Singleton
public class MainFrame extends JFrame implements Runnable {

    // Dependencies
    private final GitService gitService;
    private final GenAIService genAIService;

    // Outlets (Mapped from XML IDs)
    @Outlet private JLabel gitBranchLabel;
    @Outlet private JTree fileTree;
    @Outlet private JTextField inputField;
    @Outlet private JButton sendButton;
    @Outlet private JScrollPane chatScrollPane;

    // not managed by Sierra, created manually
    private final MarkdownPane chatArea = new MarkdownPane();

    private static final Logger log = LoggerFactory.getLogger(MainFrame.class);

    @Inject
    public MainFrame(GitService gitService, GenAIService genAIService) {
        this.gitService = gitService;
        this.genAIService = genAIService;

        setTitle("RoxyCode AI Environment");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    }

    @Override
    public void run() {
        // 1. Setup Theme
        FlatLightLaf.setup();
        // 2. Load UI from Sierra XML
        setContentPane(UILoader.load(this, "MainFrame.xml"));

        // FIX: Use setViewportView instead of add
        if (chatScrollPane != null) {
            chatScrollPane.setViewportView(chatArea);
        } else {
            log.error("Chat Scroll Pane was not injected correctly!");
        }

        // 3. Initialize State
        initGitInfo();
        initListeners();
        // 4. Show Window
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
    }

    private void onSend(ActionEvent e) {
        String prompt = inputField.getText().trim();
        log.info("User question: {}", prompt);
        if (prompt.isEmpty()) return;

        inputField.setText("");
        if (chatArea != null) {

            chatArea.appendMarkdown("**User:** " + prompt);
            // Run AI in background thread to avoid freezing UI
            new Thread(() -> {
                String response = genAIService.chat(prompt, ".");
                log.info("AI response: {}", response);
                SwingUtilities.invokeLater(() -> {
                    log.info("Appending response to chat area: {}", response);
                    chatArea.appendMarkdown("**Roxy:** " + response);
                });
            }).start();
        }else{
            log.error("Chat area is not initialized.");
        }
    }
}