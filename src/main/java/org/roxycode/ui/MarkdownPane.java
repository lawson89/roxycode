package org.roxycode.ui;

import com.formdev.flatlaf.FlatLaf;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.KeepType;
import com.vladsch.flexmark.util.data.DataHolder;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.io.IOException;
import java.util.Arrays;

public class MarkdownPane extends JTextPane {
    private final Parser parser;
    private final HtmlRenderer renderer;
    private static final Logger log = LoggerFactory.getLogger(MarkdownPane.class);

    public MarkdownPane() {
        final DataHolder OPTIONS = new MutableDataSet()
                .set(Parser.REFERENCES_KEEP, KeepType.LAST)
                .set(HtmlRenderer.INDENT_SIZE, 2)
                .set(HtmlRenderer.PERCENT_ENCODE_URLS, true)

                // for full GFM table compatibility add the following table extension options:
                .set(TablesExtension.COLUMN_SPANS, false)
                .set(TablesExtension.APPEND_MISSING_COLUMNS, true)
                .set(TablesExtension.DISCARD_EXTRA_COLUMNS, true)
                .set(TablesExtension.HEADER_SEPARATOR_COLUMN_MATCH, true)
                .set(Parser.EXTENSIONS, Arrays.asList(TablesExtension.create()))
                .toImmutable();

        this.parser = Parser.builder(OPTIONS).build();
        this.renderer = HtmlRenderer.builder(OPTIONS).build();

        this.setEditable(false);
        this.setContentType("text/html");

        // --- FIX: Force HTML to use the LookAndFeel's (smooth) fonts ---
        this.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);

        HTMLEditorKit kit = new HTMLEditorKit();
        this.setEditorKit(kit);
        this.setDocument(kit.createDefaultDocument());
        
        updateStyle();

        this.setText("<html><body></body></html>");
    }
    
    public void updateStyle() {
        HTMLEditorKit kit = (HTMLEditorKit) this.getEditorKit();
        StyleSheet styleSheet = kit.getStyleSheet();
        
        // Clear previous rules if possible or just overwrite
        // StyleSheet doesn't easily allow clearing, but adding rules with same selector overrides properties.
        
        styleSheet.addRule("body { font-family: sans-serif; font-size: 14px; padding: 10px; }");
        
        if (FlatLaf.isLafDark()) {
            styleSheet.addRule("code { background-color: #3e3e42; color: #a9b7c6; font-family: monospace; }");
            styleSheet.addRule("pre { background-color: #3e3e42; color: #a9b7c6; padding: 10px; }");
        } else {
            styleSheet.addRule("code { background-color: #f0f0f0; color: #333333; font-family: monospace; }");
            styleSheet.addRule("pre { background-color: #f0f0f0; color: #333333; padding: 10px; }");
        }
    }

    // --- FIX: Force Graphics2D Anti-Aliasing during paint ---
    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        super.paintComponent(g2);
    }

    public void setMarkdown(String markdown) {
        String html = renderer.render(parser.parse(markdown));
        this.setText("<html><body>" + html + "</body></html>");
    }

    public void appendMarkdown(String markdown) {
        log.info("Rendering markdown: {}", markdown);
        String newHtml = renderer.render(parser.parse(markdown));

        try {
            HTMLDocument doc = (HTMLDocument) getDocument();
            HTMLEditorKit kit = (HTMLEditorKit) getEditorKit();

            // Insert the new HTML at the end of the body
            kit.insertHTML(doc, doc.getLength(), newHtml + "<hr/>", 0, 0, null);

            this.setCaretPosition(doc.getLength());

        } catch (BadLocationException | IOException e) {
            log.error("Failed to append markdown", e);
        }
    }
}
