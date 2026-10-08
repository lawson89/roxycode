package org.roxycode.app.ui;

import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;

import javax.swing.*;
import java.util.Arrays;

/**
 * A reusable component for viewing Markdown content with theme-aware styling.
 */
public class MarkdownViewer extends JTextPane {
    private final Parser parser;
    private final HtmlRenderer renderer;
    private String currentMarkdown = "";

    public MarkdownViewer() {
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
        render();
    }

    public void setContent(String markdown) {
        this.currentMarkdown = markdown == null ? "" : markdown;
        render();
    }

        private void render() {
        String htmlContent = renderer.render(parser.parse(currentMarkdown));
        String highlightedHtml = JexlToHtmlConverter.highlightCodeBlocks(htmlContent);
        setText("<html><body>" + highlightedHtml + "</body></html>");
        setCaretPosition(0);
    }
}
