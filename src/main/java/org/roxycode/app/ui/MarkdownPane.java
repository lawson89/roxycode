package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatLaf;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;

import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.text.html.StyleSheet;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A JTextPane that renders Markdown messages using native Java templates.
 * Supports streaming updates by re-rendering the message history.
 */
public class MarkdownPane extends JTextPane {
    private final Parser parser;
    private final HtmlRenderer renderer;
    private final List<Message> messages = new ArrayList<>();
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");

    private record Message(String role, StringBuilder content, String timestamp, boolean isTool) {}

    public MarkdownPane() {
        setEditable(false);
        setContentType("text/html");
        setOpaque(true);
        putClientProperty("JEditorPane.honorDisplayProperties", true);

        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, Arrays.asList(TablesExtension.create(), StrikethroughExtension.create()));
        parser = Parser.builder(options).build();
        renderer = HtmlRenderer.builder(options).build();

        updateTheme();
        setupContextMenu();
    }

    private void setupContextMenu() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) showPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) showPopup(e);
            }

            private void showPopup(MouseEvent e) {
                requestFocusInWindow();
                JPopupMenu menu = new JPopupMenu();
                JMenuItem copy = new JMenuItem(new DefaultEditorKit.CopyAction());
                copy.setText("Copy");
                menu.add(copy);
                menu.addSeparator();
                JMenuItem selectAll = new JMenuItem("Select All");
                selectAll.addActionListener(ae -> selectAll());
                menu.add(selectAll);
                menu.show(e.getComponent(), e.getX(), e.getY());
            }
        });
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (parser != null) {
            updateTheme();
        }
    }

    public void updateTheme() {
        HTMLEditorKit kit = new HTMLEditorKit();
        StyleSheet sheet = new StyleSheet();
        
        boolean isDark = FlatLaf.isLafDark();
        String cssPath = isDark ? "/css/chat-styles-dark.css" : "/css/chat-styles-light.css";
        URL cssUrl = getClass().getResource(cssPath);
        if (cssUrl != null) {
            sheet.importStyleSheet(cssUrl);
        }
        
        Color bg = UIManager.getColor("TextPane.background");
        Color fg = UIManager.getColor("TextPane.foreground");
        if (bg == null) bg = isDark ? new Color(30, 30, 30) : Color.WHITE;
        if (fg == null) fg = isDark ? Color.LIGHT_GRAY : Color.BLACK;

        setBackground(bg);
        setForeground(fg);
        setOpaque(true);

        // Ensure body background matches in HTML
        String bodyRule = String.format("body { background-color: #%02x%02x%02x; color: #%02x%02x%02x; margin: 10px; }", 
            bg.getRed(), bg.getGreen(), bg.getBlue(),
            fg.getRed(), fg.getGreen(), fg.getBlue());
        sheet.addRule(bodyRule);

        kit.setStyleSheet(sheet);
        setEditorKit(kit);
        renderAll();
    }

    public void appendMessage(String role, String content) {
        String safeContent = content != null ? content : "";
        messages.add(new Message(role, new StringBuilder(safeContent), LocalDateTime.now().format(timeFormatter), false));
        renderAll();
    }

    public void appendToolLog(String toolName, String output) {
        String safeOutput = output != null ? output : "";
        messages.add(new Message(toolName, new StringBuilder(safeOutput), LocalDateTime.now().format(timeFormatter), true));
        renderAll();
    }

    private void renderAll() {
        StringBuilder fullHtml = new StringBuilder("<html><body>");
        for (Message msg : messages) {
            try {
                if (msg.isTool()) {
                    fullHtml.append(ChatHtmlTemplates.renderToolLog(msg.role(), msg.content().toString()));
                } else {
                    String renderedMarkdown = renderer.render(parser.parse(msg.content().toString()));
                    fullHtml.append(ChatHtmlTemplates.renderChatEntry(msg.role().toLowerCase(), renderedMarkdown, msg.timestamp()));
                }
            } catch (Exception e) {
                fullHtml.append("<div>Error rendering message</div>");
            }
        }
        fullHtml.append("</body></html>");
        setText(fullHtml.toString());
        SwingUtilities.invokeLater(() -> setCaretPosition(getDocument().getLength()));
    }

    public void clear() {
        messages.clear();
        renderAll();
    }
}