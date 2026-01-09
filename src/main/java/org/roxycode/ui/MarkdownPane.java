package org.roxycode.ui;

import org.roxycode.core.utils.UIUtils;

import com.formdev.flatlaf.FlatLaf;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.KeepType;
import com.vladsch.flexmark.util.data.DataHolder;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignW;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.ui.syntaxhighlight.JsToHtmlConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;

public class MarkdownPane extends JTextPane {

    private final Parser parser;

    private final HtmlRenderer renderer;

    private static final Logger log = LoggerFactory.getLogger(MarkdownPane.class);

    public MarkdownPane() {
        final DataHolder // for full GFM table compatibility add the following table extension options:
        // for full GFM table compatibility add the following table extension options:
        OPTIONS = new MutableDataSet().set(Parser.REFERENCES_KEEP, KeepType.LAST).set(HtmlRenderer.INDENT_SIZE, 2).set(HtmlRenderer.PERCENT_ENCODE_URLS, true).set(TablesExtension.COLUMN_SPANS, false).set(TablesExtension.APPEND_MISSING_COLUMNS, true).set(TablesExtension.DISCARD_EXTRA_COLUMNS, true).set(TablesExtension.HEADER_SEPARATOR_COLUMN_MATCH, true).set(Parser.EXTENSIONS, List.of(TablesExtension.create())).toImmutable();
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
        UIUtils.addContextMenu(this);
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
        String html = markdownToHtml(markdown);
        setHtml(html);
    }

    public void setHtml(String html) {
        this.setText("<html><body>" + html + "</body></html>");
    }

    public void appendMarkdown(String markdown) {
        log.info("Rendering markdown: {}", markdown);
        String newHtml = renderer.render(parser.parse(markdown));
        try {
            HTMLDocument doc = (HTMLDocument) getDocument();
            HTMLEditorKit kit = (HTMLEditorKit) getEditorKit();
            // Insert the new HTML at the end of the body
            kit.insertHTML(doc, doc.getLength(), newHtml + "<div style='height: 1px; background-color: " + (FlatLaf.isLafDark() ? "#444444" : "#D0D0D0") + "; font-size: 1px; border: none; margin: 10px 0;'></div>", 0, 0, null);
            this.setCaretPosition(doc.getLength());
        } catch (BadLocationException | IOException e) {
            log.error("Failed to append markdown", e);
        }
    }

    public void appendToolLog(String markdown) {
        log.info("Rendering tool log: {}", markdown);
        String imgTag = generateIconTag(MaterialDesignW.WRENCH_OUTLINE, 16, FlatLaf.isLafDark() ? Color.LIGHT_GRAY : Color.DARK_GRAY, "wrench");
        if (!imgTag.isEmpty()) {
            imgTag += "&nbsp;";
        }
//        String html = stripParagraph(renderer.render(parser.parse(markdown)));
        String html = JsToHtmlConverter.convertToHtml(markdown, com.formdev.flatlaf.FlatLaf.isLafDark());
        String combinedHtml = "<div style='background-color: " + (FlatLaf.isLafDark() ? "#2b2d30" : "#f2f2f2") + "; padding: 4px; border-radius: 4px; margin: 2px 0;'>" + imgTag + "<span>" + html + "</span></div>";
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
        String imgTag = generateIconTag(MaterialDesignR.ROBOT_HAPPY_OUTLINE, 18, FlatLaf.isLafDark() ? Color.LIGHT_GRAY : Color.DARK_GRAY, "status");
        if (!imgTag.isEmpty()) {
            imgTag += "&nbsp;";
        }
        String html = stripParagraph(renderer.render(parser.parse(markdown)));
        String bgColor = FlatLaf.isLafDark() ? "#2b2d30" : "#f8f9fa";
        String combinedHtml = "<div style='background-color: " + bgColor + "; padding: 4px 8px; border-radius: 4px; color: gray; font-style: italic; margin: 2px 0;'>" + imgTag + "<span>" + html + "</span></div>";
        try {
            HTMLDocument doc = (HTMLDocument) getDocument();
            HTMLEditorKit kit = (HTMLEditorKit) getEditorKit();
            kit.insertHTML(doc, doc.getLength(), combinedHtml, 0, 0, null);
            this.setCaretPosition(doc.getLength());
        } catch (BadLocationException | IOException e) {
            log.error("Failed to append status", e);
        }
    }

    public void appendRoxyMarkdown(String markdown) {
        log.info("Rendering Roxy markdown: {}", markdown);
        String imgTag = generateIconTag(MaterialDesignP.PAW_OUTLINE, 18, FlatLaf.isLafDark() ? Color.LIGHT_GRAY : Color.DARK_GRAY, "roxy");
        String html = stripParagraph(renderer.render(parser.parse(markdown)));
        String combinedHtml = "<div style='padding: 4px 8px; border-radius: 4px;'>" + imgTag + "&nbsp;<span>" + html + "</span></div>";
        try {
            HTMLDocument doc = (HTMLDocument) getDocument();
            HTMLEditorKit kit = (HTMLEditorKit) getEditorKit();
            kit.insertHTML(doc, doc.getLength(), combinedHtml + "<div style='height: 1px; background-color: " + (FlatLaf.isLafDark() ? "#444444" : "#D0D0D0") + "; font-size: 1px; border: none; margin: 10px 0;'></div>", 0, 0, null);
            this.setCaretPosition(doc.getLength());
        } catch (BadLocationException | IOException e) {
            log.error("Failed to append Roxy markdown", e);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private String generateIconTag(Ikon iconCode, int size, Color color, String namePrefix) {
        try {
            FontIcon icon = FontIcon.of(iconCode, size, color);
            BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = image.createGraphics();
            icon.paintIcon(this, g2, 0, 0);
            g2.dispose();
            // Generate a unique virtual URL for the icon
            String imageName = namePrefix + "-" + System.nanoTime() + ".png";
            URL imageURL = new URI("http://roxycode.local/" + imageName).toURL();
            HTMLDocument doc = (HTMLDocument) getDocument();
            Dictionary cache = (Dictionary) doc.getProperty("imageCache");
            if (cache == null) {
                cache = new Hashtable();
                doc.putProperty("imageCache", cache);
            }
            cache.put(imageURL, image);
            return "<img src=\"" + imageURL + "\" style=\"vertical-align:middle\">";
        } catch (Exception e) {
            log.error("Failed to generate icon: " + namePrefix, e);
            return "";
        }
    }

    public String markdownToHtml(String markdown) {
        return renderer.render(parser.parse(markdown));
    }

    public void appendUserMarkdown(String markdown) {
        log.info("Rendering User markdown: {}");
        String imgTag = generateIconTag(MaterialDesignM.MESSAGE_QUESTION_OUTLINE, 18, FlatLaf.isLafDark() ? new Color(0x4080FF) : new Color(0x0055BB), "user");
        String html = stripParagraph(renderer.render(parser.parse(markdown)));
        String combinedHtml = "<div style='padding: 4px 8px; border-radius: 4px;'>" + imgTag + "&nbsp;<span>" + html + "</span></div>";
        try {
            HTMLDocument doc = (HTMLDocument) getDocument();
            HTMLEditorKit kit = (HTMLEditorKit) getEditorKit();
            kit.insertHTML(doc, doc.getLength(), combinedHtml + "<div style='height: 1px; background-color: " + (FlatLaf.isLafDark() ? "#444444" : "#D0D0D0") + "; font-size: 1px; border: none; margin: 10px 0;'></div>", 0, 0, null);
            this.setCaretPosition(doc.getLength());
        } catch (BadLocationException | IOException e) {
            log.error("Failed to append User markdown", e);
        }
    }

    public String getIconTag(String role) {
        if ("user".equalsIgnoreCase(role)) {
            return generateIconTag(MaterialDesignM.MESSAGE_QUESTION_OUTLINE, 18, FlatLaf.isLafDark() ? new Color(0x4080FF) : new Color(0x0055BB), "user");
        } else if ("model".equalsIgnoreCase(role)) {
            return generateIconTag(MaterialDesignP.PAW_OUTLINE, 18, FlatLaf.isLafDark() ? Color.LIGHT_GRAY : Color.DARK_GRAY, "roxy");
        }
        return null;
    }

        private String stripParagraph(String html) {
        if (html == null)
            return "";
        String result = html.trim();
        if (result.startsWith("<p>") && result.endsWith("</p>")) {
            return result.substring(3, result.length() - 4).trim();
        }
        return result;
    }
}
