package org.roxycode.ui.views;

import com.formdev.flatlaf.FlatLaf;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.commons.lang3.StringUtils;
import org.httprpc.sierra.Outlet;
import org.httprpc.sierra.UILoader;
import org.roxycode.core.GenAIService;
import org.roxycode.ui.ThemeService;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Singleton
public class MessageHistoryView extends JPanel {

    private final GenAIService genAIService;
    private final JTextPane messageHistoryArea = new JTextPane();

    @Outlet
    private JScrollPane messageHistoryScrollPane;

    @Outlet
    private JButton refreshMessageHistoryButton;

    private final ThemeService themeService;

    @Inject
    public MessageHistoryView(GenAIService genAIService, ThemeService themeService) {
        this.genAIService = genAIService;
        this.themeService = themeService;
        setLayout(new BorderLayout());
    }

    @PostConstruct
    public void init() {
        add(UILoader.load(this, "MessageHistoryView.xml"));
        messageHistoryArea.setEditable(false);
        messageHistoryArea.setMargin(new Insets(10, 10, 10, 10));
        messageHistoryArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        if (messageHistoryScrollPane != null) {
            messageHistoryScrollPane.setViewportView(messageHistoryArea);
        }
        themeService.registerPane(messageHistoryArea);
        initListeners();
    }

    private void updateStyles() {
        boolean isDark = FlatLaf.isLafDark();
        StyledDocument doc = messageHistoryArea.getStyledDocument();
        Style defaultStyle = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE);

        Style userStyle = getOrAddStyle(doc, "user", defaultStyle);
        StyleConstants.setBold(userStyle, true);
        StyleConstants.setForeground(userStyle, isDark ? new Color(0x64B5F6) : new Color(0x1976D2));

        Style modelStyle = getOrAddStyle(doc, "model", defaultStyle);
        StyleConstants.setBold(modelStyle, true);
        StyleConstants.setForeground(modelStyle, isDark ? new Color(0x81C784) : new Color(0x388E3C));

        Style systemStyle = getOrAddStyle(doc, "system", defaultStyle);
        StyleConstants.setBold(systemStyle, true);
        StyleConstants.setForeground(systemStyle, isDark ? Color.LIGHT_GRAY : Color.DARK_GRAY);

        Style contentStyle = getOrAddStyle(doc, "content", defaultStyle);
        StyleConstants.setBold(contentStyle, false);
        StyleConstants.setForeground(contentStyle, isDark ? new Color(0xBBBBBB) : Color.BLACK);

        Style italicStyle = getOrAddStyle(doc, "italic", defaultStyle);
        StyleConstants.setItalic(italicStyle, true);
        StyleConstants.setForeground(italicStyle, isDark ? Color.LIGHT_GRAY : Color.DARK_GRAY);

        Style separatorStyle = getOrAddStyle(doc, "separator", defaultStyle);
        StyleConstants.setForeground(separatorStyle, isDark ? new Color(0x444444) : Color.LIGHT_GRAY);
    }

    private Style getOrAddStyle(StyledDocument doc, String name, Style parent) {
        Style s = doc.getStyle(name);
        if (s == null) {
            s = doc.addStyle(name, parent);
        }
        return s;
    }

    private void initListeners() {
        if (refreshMessageHistoryButton != null) {
            refreshMessageHistoryButton.addActionListener(e -> refresh());
        }
    }

    public void refresh() {
        if (messageHistoryArea == null)
            return;

        updateStyles();

        List<Content> history = new ArrayList<>(genAIService.getHistory());

        StyledDocument doc = messageHistoryArea.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
            for (Content content : history) {
                appendContent(doc, content);
                doc.insertString(doc.getLength(), "\n" + "-".repeat(80) + "\n\n", doc.getStyle("separator"));
            }
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
        // Scroll to bottom for history
        messageHistoryArea.setCaretPosition(doc.getLength());
    }

    private void appendContent(StyledDocument doc, Content content) throws BadLocationException {
        String role = content.role().orElse("unknown");
        Style roleStyle = doc.getStyle("system");
        if ("user".equalsIgnoreCase(role)) roleStyle = doc.getStyle("user");
        else if ("model".equalsIgnoreCase(role)) roleStyle = doc.getStyle("model");

        doc.insertString(doc.getLength(), role.toUpperCase() + ": ", roleStyle);

        List<Part> parts = content.parts().orElse(Collections.emptyList());
        for (Part part : parts) {
            if (part.text().isPresent()) {
                String text = part.text().get();
                if (text.length() > 5000) {
                    text = text.substring(0, 5000) + "... (truncated)";
                }
                doc.insertString(doc.getLength(), text + "\n", doc.getStyle("content"));
            } else if (part.functionCall().isPresent()) {
                doc.insertString(doc.getLength(), "[Function Call: " + part.functionCall().get().name().orElse("?") + "]\n", doc.getStyle("italic"));
            } else if (part.functionResponse().isPresent()) {
                doc.insertString(doc.getLength(), "[Function Response: " + part.functionResponse().get().name().orElse("?") + "]\n", doc.getStyle("italic"));
                Map<String, Object> toolOutput = part.functionResponse().get().response().orElse(null);
                String toolOutputStr = toolOutput != null ? toolOutput.toString() : "null";
                doc.insertString(doc.getLength(), StringUtils.abbreviate(toolOutputStr, 500) + "\n", doc.getStyle("content"));
            } else if (part.inlineData().isPresent()) {
                doc.insertString(doc.getLength(), "[Inline Data: " + part.inlineData().get().mimeType().orElse("?") + "]\n", doc.getStyle("italic"));
            }
        }
    }

    public JTextPane getMessageHistoryArea() {
        return messageHistoryArea;
    }
}
