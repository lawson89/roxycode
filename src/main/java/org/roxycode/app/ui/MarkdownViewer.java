package org.roxycode.app.ui;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import javax.swing.*;

/** A reusable component for viewing Markdown content with theme-aware styling. */
public final class MarkdownViewer extends JTextPane {
    private static final long serialVersionUID = 1L;
    private final transient Parser parser;
    private final transient HtmlRenderer renderer;
    private String currentMarkdown = "";

    public MarkdownViewer() {
        setEditable(false);
        setContentType("text/html");
        setOpaque(true);
        putClientProperty("JEditorPane.honorDisplayProperties", true);

        parser = SwingHtmlUtils.markdownParser();
        renderer = SwingHtmlUtils.markdownRenderer();

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
        render();
    }

    public void setContent(String markdown) {
        this.currentMarkdown = markdown == null ? "" : markdown;
        render();
    }

    private void render() {
        String htmlContent =
                SwingHtmlUtils.styleMarkdownTables(renderer.render(parser.parse(currentMarkdown)));
        String highlightedHtml = JexlToHtmlConverter.highlightCodeBlocks(htmlContent);
        setText("<html><body>" + highlightedHtml + "</body></html>");
        setCaretPosition(0);
    }
}
