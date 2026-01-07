package org.roxycode.ui.views;

import com.formdev.flatlaf.FlatLaf;
import com.google.genai.types.Content;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.httprpc.sierra.Outlet;
import org.httprpc.sierra.UILoader;
import org.roxycode.core.GenAIService;
import org.roxycode.core.HistoryService;
import org.roxycode.ui.MarkdownPane;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Singleton
public class MessageHistoryView extends JPanel {

    private final GenAIService genAIService;
    private final HistoryService historyService;
    private final MarkdownPane messageHistoryArea = new MarkdownPane();

    @Outlet
    private JComponent viewMessageHistory;

    @Outlet
    private JScrollPane messageHistoryScrollPane;

    @Outlet
    private JButton refreshMessageHistoryButton;

    private final org.roxycode.ui.ThemeService themeService;

    @Inject
    public MessageHistoryView(GenAIService genAIService, HistoryService historyService, org.roxycode.ui.ThemeService themeService) {
        this.genAIService = genAIService;
        this.historyService = historyService;
        this.themeService = themeService;
        setLayout(new BorderLayout());
    }

    @PostConstruct
    public void init() {
        add(UILoader.load(this, "MessageHistoryView.xml"));
        if (messageHistoryScrollPane != null) {
            messageHistoryScrollPane.setViewportView(messageHistoryArea);
        }
        themeService.registerPane(messageHistoryArea);
        initListeners();
    }


    private void initListeners() {
        if (refreshMessageHistoryButton != null) {
            refreshMessageHistoryButton.addActionListener(e -> refresh());
        }
    }

    public void refresh() {
        if (messageHistoryArea == null)
            return;
        List<Content> history = new ArrayList<>(genAIService.getHistory());
        Collections.reverse(history);
        StringBuilder html = new StringBuilder();
        html.append("<table width='100%' border='0' cellspacing='0' cellpadding='10'>");
        for (Content content : history) {
            html.append(historyService.renderContentToHtmlRow(content, FlatLaf.isLafDark(), messageHistoryArea::markdownToHtml, messageHistoryArea::getIconTag));
        }
        html.append("</table>");
        messageHistoryArea.setHtml(html.toString());
    }

    public MarkdownPane getMessageHistoryArea() {
        return messageHistoryArea;
    }
}
