package org.roxycode.ui;

import com.formdev.flatlaf.FlatLaf;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.KeepType;
import com.vladsch.flexmark.util.data.DataHolder;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.kordamp.ikonli.bootstrapicons.BootstrapIcons;
import org.kordamp.ikonli.swing.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Base64;
import java.util.Dictionary;
import java.util.Hashtable;

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
            kit.insertHTML(doc, doc.getLength(), newHtml + "<div style='height: 1px; background-color: #A0A0A0; font-size: 1px; border: none; margin: 5px 0;'></div>", 0, 0, null);

            this.setCaretPosition(doc.getLength());

        } catch (BadLocationException | IOException e) {
            log.error("Failed to append markdown", e);
        }
    }

    public void appendToolLog(String markdown) {
        log.info("Rendering tool log: {}", markdown);
        
        // Generate Icon via Document Image Cache (JEditorPane doesn't reliably support data: URIs)
        String imgTag = "";
        try {
            FontIcon icon = FontIcon.of(BootstrapIcons.WRENCH, 16, FlatLaf.isLafDark() ? Color.LIGHT_GRAY : Color.DARK_GRAY);
            BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = image.createGraphics();
            icon.paintIcon(this, g2, 0, 0);
            g2.dispose();
            
            // Generate a unique virtual URL for the icon
            String imageName = "wrench-" + System.nanoTime() + ".png";
            URL imageURL = new URL("http://roxycode.local/" + imageName);
            
            HTMLDocument doc = (HTMLDocument) getDocument();
            Dictionary cache = (Dictionary) doc.getProperty("imageCache");
            if (cache == null) {
                cache = new Hashtable();
                doc.putProperty("imageCache", cache);
            }
            cache.put(imageURL, image);
            
            imgTag = "<img src=\"" + imageURL + "\" style=\"vertical-align:middle\">&nbsp;";
        } catch (Exception e) {
            log.error("Failed to generate icon", e);
        }
        
        String html = renderer.render(parser.parse(markdown));
        // Remove surrounding <p> tags if present to align nicely with image
        if (html.startsWith("<p>") && html.endsWith("</p>\n")) {
             html = html.substring(3, html.length() - 5);
        }
        
        String combinedHtml = "<div>" + imgTag + "<span>" + html + "</span></div>";

        try {
            HTMLDocument doc = (HTMLDocument) getDocument();
            HTMLEditorKit kit = (HTMLEditorKit) getEditorKit();

            // Insert the new HTML at the end of the body
            kit.insertHTML(doc, doc.getLength(), combinedHtml, 0, 0, null);

            this.setCaretPosition(doc.getLength());

        } catch (BadLocationException | IOException e) {
            log.error("Failed to append tool log", e);
        }
    }

    public void appendStatus(String markdown) {
        log.info("Rendering status: {}", markdown);
        
        String imgTag = "";
        try {
            FontIcon icon = FontIcon.of(BootstrapIcons.GEAR_WIDE_CONNECTED, 16, FlatLaf.isLafDark() ? Color.LIGHT_GRAY : Color.DARK_GRAY);
            BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = image.createGraphics();
            icon.paintIcon(this, g2, 0, 0);
            g2.dispose();
            
            String imageName = "status-" + System.nanoTime() + ".png";
            URL imageURL = new URL("http://roxycode.local/" + imageName);
            
            HTMLDocument doc = (HTMLDocument) getDocument();
            Dictionary cache = (Dictionary) doc.getProperty("imageCache");
            if (cache == null) {
                cache = new Hashtable();
                doc.putProperty("imageCache", cache);
            }
            cache.put(imageURL, image);
            
            imgTag = "<img src=\"" + imageURL + "\" style=\"vertical-align:middle\">&nbsp;";
        } catch (Exception e) {
            log.error("Failed to generate icon", e);
        }
        
        String html = renderer.render(parser.parse(markdown));
        if (html.startsWith("<p>") && html.endsWith("</p>\n")) {
             html = html.substring(3, html.length() - 5);
        }
        
        String combinedHtml = "<div style='color: gray; font-style: italic;'>" + imgTag + "<span>" + html + "</span></div>";

        try {
            HTMLDocument doc = (HTMLDocument) getDocument();
            HTMLEditorKit kit = (HTMLEditorKit) getEditorKit();
            kit.insertHTML(doc, doc.getLength(), combinedHtml, 0, 0, null);
            this.setCaretPosition(doc.getLength());
        } catch (BadLocationException | IOException e) {
            log.error("Failed to append status", e);
        }
    }
}
