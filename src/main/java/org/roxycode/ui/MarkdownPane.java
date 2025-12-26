package org.roxycode.ui;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JEditorPane;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.IOException;

public class MarkdownPane extends JTextPane {
    private final Parser parser;
    private final HtmlRenderer renderer;
    private static final Logger log = LoggerFactory.getLogger(MarkdownPane.class);

    public MarkdownPane() {
        this.parser = Parser.builder().build();
        this.renderer = HtmlRenderer.builder().build();

        this.setEditable(false);
        this.setContentType("text/html");

        // --- FIX: Force HTML to use the LookAndFeel's (smooth) fonts ---
        this.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);

        // Basic styling
        HTMLEditorKit kit = new HTMLEditorKit();
        StyleSheet styleSheet = kit.getStyleSheet();
        styleSheet.addRule("body { font-family: sans-serif; font-size: 14px; padding: 10px; }");
        styleSheet.addRule("code { background-color: #f0f0f0; font-family: monospace; }");
        this.setEditorKit(kit);
        this.setDocument(kit.createDefaultDocument());

        this.setText("<html><body></body></html>");
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