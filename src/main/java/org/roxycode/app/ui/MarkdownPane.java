package org.roxycode.app.ui;

import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;

import javax.swing.*;
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
        SwingHtmlUtils.installTextContextMenu(this);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (parser != null) {
            updateTheme();
        }
    }

    public void updateTheme() {
        SwingHtmlUtils.applyTheme(this, 10);
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
