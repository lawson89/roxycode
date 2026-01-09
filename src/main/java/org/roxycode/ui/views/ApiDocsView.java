package org.roxycode.ui.views;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.httprpc.sierra.Outlet;
import org.httprpc.sierra.UILoader;
import org.roxycode.core.SettingsService;
import org.roxycode.core.tools.ScriptServiceRegistry;
import org.roxycode.ui.MarkdownPane;
import org.roxycode.ui.ThemeService;
import org.roxycode.ui.syntaxhighlight.JsToHtmlConverter;

import javax.swing.*;
import java.awt.*;

@Singleton
public class ApiDocsView extends JPanel {

    private final ScriptServiceRegistry scriptServiceRegistry;
    private final ThemeService themeService;
    private final SettingsService settingsService;
    private final MarkdownPane apiDocsArea = new MarkdownPane();

    @Outlet
    private JScrollPane apiDocsScrollPane;

    @Outlet
    private JButton refreshApiDocsButton;

    @Inject
    public ApiDocsView(ScriptServiceRegistry scriptServiceRegistry, ThemeService themeService, SettingsService settingsService) {
        this.scriptServiceRegistry = scriptServiceRegistry;
        this.themeService = themeService;
        this.settingsService = settingsService;
        setLayout(new BorderLayout());
    }

    @PostConstruct
    public void init() {
        add(UILoader.load(this, "ApiDocsView.xml"));
        if (apiDocsScrollPane != null) {
            apiDocsScrollPane.setViewportView(apiDocsArea);
        }
        themeService.registerPane(apiDocsArea);
        initListeners();
    }

    private void initListeners() {
        if (refreshApiDocsButton != null) {
            refreshApiDocsButton.addActionListener(e -> refresh());
        }
    }

    public MarkdownPane getApiDocsArea() {
        return apiDocsArea;
    }

    public void refresh() {
        apiDocsArea.setMarkdown("*Loading API Documentation...*");
        new Thread(() -> {
            try {
                String docs = scriptServiceRegistry.getApiDocs();
                boolean isDark = "Dark".equals(settingsService.getTheme()) || "Darcula".equals(settingsService.getTheme());
                String html = JsToHtmlConverter.convertToHtml(docs, isDark);
                SwingUtilities.invokeLater(() -> apiDocsArea.setHtml(html));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> apiDocsArea.setMarkdown("Error loading API documentation: " + e.getMessage()));
            }
        }).start();
    }

}
