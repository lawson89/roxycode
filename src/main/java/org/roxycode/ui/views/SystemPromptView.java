package org.roxycode.ui.views;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.httprpc.sierra.Outlet;
import org.httprpc.sierra.UILoader;
import org.roxycode.core.GenAIService;
import org.roxycode.core.SettingsService;
import org.roxycode.ui.MarkdownPane;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;

@Singleton
public class SystemPromptView extends JPanel {

    private final GenAIService genAIService;
    private final SettingsService settingsService;
    private final MarkdownPane systemPromptArea = new MarkdownPane();

    @Outlet
    private JComponent viewSystemPrompt;

    @Outlet
    private JScrollPane systemPromptScrollPane;

    @Outlet
    private JButton refreshSystemPromptButton;

    private final org.roxycode.ui.ThemeService themeService;

    @Inject
    public SystemPromptView(GenAIService genAIService, SettingsService settingsService, org.roxycode.ui.ThemeService themeService) {
        this.genAIService = genAIService;
        this.settingsService = settingsService;
        this.themeService = themeService;
        setLayout(new BorderLayout());
    }

    @PostConstruct
    public void init() {
        add(UILoader.load(this, "SystemPromptView.xml"));
        if (systemPromptScrollPane != null) {
            systemPromptScrollPane.setViewportView(systemPromptArea);
        }
        themeService.registerPane(systemPromptArea);
        initListeners();
    }


    private void initListeners() {
        if (refreshSystemPromptButton != null) {
            refreshSystemPromptButton.addActionListener(e -> refresh());
        }
    }

    public void refresh() {
        if (systemPromptArea != null) {
            systemPromptArea.setMarkdown("*Generating system prompt...*");
            new Thread(() -> {
                String projectRoot = settingsService.getCurrentProject();
                String prompt = genAIService.buildSystemContext(projectRoot, new ArrayList<>());
                SwingUtilities.invokeLater(() -> systemPromptArea.setMarkdown(prompt));
            }).start();
        }
    }

    public MarkdownPane getSystemPromptArea() {
        return systemPromptArea;
    }
}
