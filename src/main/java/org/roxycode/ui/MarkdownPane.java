package org.roxycode.ui;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignW;
import org.kordamp.ikonli.swing.FontIcon;
import org.xhtmlrenderer.swing.ScalableXHTMLPanel;
import org.xhtmlrenderer.swing.NaiveUserAgent;
import org.xhtmlrenderer.simple.extend.XhtmlNamespaceHandler;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MarkdownPane extends JPanel {
    private final ScalableXHTMLPanel xhtmlPanel;
    private final List<String> snippets = new ArrayList<>();
    private final Parser parser;
    private final HtmlRenderer renderer;
    private final RoxyReplacedElementFactory replacedElementFactory;

    public MarkdownPane() {
        setLayout(new BorderLayout());

        // Initialize with UserAgentCallback
        xhtmlPanel = new ScalableXHTMLPanel(new NaiveUserAgent());
        add(xhtmlPanel, BorderLayout.CENTER);

        // Flexmark Setup
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, Arrays.asList(TablesExtension.create()));
        parser = Parser.builder(options).build();
        renderer = HtmlRenderer.builder(options).build();

        // Image Handling Setup
        replacedElementFactory = new RoxyReplacedElementFactory(xhtmlPanel.getSharedContext().getReplacedElementFactory());
        xhtmlPanel.getSharedContext().setReplacedElementFactory(replacedElementFactory);

        initIcons();
        render();
    }

    private void initIcons() {
        registerIcon("wrench", MaterialDesignW.WRENCH, Color.GRAY);
        registerIcon("robot", MaterialDesignR.ROBOT, new Color(100, 150, 250));
        registerIcon("cat", MaterialDesignC.CAT, new Color(200, 150, 100));
    }

    private void registerIcon(String name, Ikon iconCode, Color color) {
        FontIcon icon = FontIcon.of(iconCode, 24, color);
        BufferedImage img = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        icon.paintIcon(null, g2, 0, 0);
        g2.dispose();
        replacedElementFactory.putImage("http://roxycode.local/" + name + ".png", img);
    }

    public void updateStyle() {
        render();
    }

    public void setMarkdown(String m) {
        snippets.clear();
        appendMarkdown(m);
    }

    public void appendMarkdown(String m) {
        String html = renderer.render(parser.parse(m));
        snippets.add(html);
        render();
    }

    public void appendToolLog(String m) {
        String html = "<div style='color: #888888; font-family: monospace; margin: 5px 0;'>" +
                      "<img src='http://roxycode.local/wrench.png' style='vertical-align: middle; margin-right: 5px;'/>" +
                      escapeHtml(m) + "</div>";
        snippets.add(html);
        render();
    }

    public void appendStatus(String m) {
        String html = "<div style='color: #666666; font-style: italic; margin: 5px 0;'>" +
                      escapeHtml(m) + "</div>";
        snippets.add(html);
        render();
    }

    public void appendRoxyMarkdown(String m) {
        String html = "<div style='margin-top: 10px;'>" +
                      "<img src='http://roxycode.local/cat.png' style='vertical-align: middle; margin-right: 5px;'/>" +
                      "<b>Roxy:</b></div>" +
                      renderer.render(parser.parse(m));
        snippets.add(html);
        render();
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private void render() {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><style>");
        sb.append(getStyleSheet());
        sb.append("</style></head><body>");
        for (String snippet : snippets) {
            sb.append(snippet);
        }
        sb.append("</body></html>");

        String xhtml = convertToXhtml(sb.toString());

        SwingUtilities.invokeLater(() -> {
            xhtmlPanel.setDocumentFromString(
                    xhtml,
                    "http://roxycode.local/",
                    new XhtmlNamespaceHandler()
            );

            // Scroll to bottom
            scrollRectToVisible(new Rectangle(0, getHeight() - 1, 1, 1));
        });
    }

    private String convertToXhtml(String html) {
        Document doc = Jsoup.parse(html);
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
        doc.outputSettings().escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml);
        return doc.html();
    }

    private String getStyleSheet() {
        Color fg = UIManager.getColor("Label.foreground");
        Color bg = UIManager.getColor("Panel.background");
        // Fallbacks
        if (fg == null) fg = Color.BLACK;
        if (bg == null) bg = Color.WHITE;

        String fgHex = toHex(fg);
        String bgHex = toHex(bg);

        // Slightly darker borders for better contrast
        String borderColor = isDark() ? "#555555" : "#cccccc";
        // Distinct background for code blocks
        String blockBg = isDark() ? "#2d2d2d" : "#f5f5f5";
        // Accent color for links or special highlights (optional)
        String linkColor = isDark() ? "#64b5f6" : "#1e88e5";

        return
                // 1. GLOBAL RESET & FONT SIZING
                // Use 'Dialog' which maps to the system UI font.
                // Reduced size to 11px or 10pt often matches Swing better than 12pt.
                "body { font-family: Dialog, sans-serif; font-size: 10pt; color: " + fgHex + "; background-color: " + bgHex + "; margin: 0; padding: 12px; line-height: 1.4; }\n" +

                // 2. HEADERS
                // Tighten margins to save vertical space
                "h1, h2, h3, h4 { margin-top: 15px; margin-bottom: 8px; font-weight: bold; }\n" +
                "h1 { font-size: 1.4em; border-bottom: 1px solid " + borderColor + "; padding-bottom: 4px; }\n" +
                "h2 { font-size: 1.2em; }\n" +

                // 3. CODE BLOCKS (CRITICAL FIX)
                // 'white-space: pre-wrap' forces long lines to wrap, preventing overflow.
                // Flying Saucer often ignores 'overflow: auto' on divs, so wrapping is safer.
                "pre { background-color: " + blockBg + "; padding: 8px; border: 1px solid " + borderColor + "; border-radius: 4px; font-family: Monospaced; font-size: 0.9em; white-space: pre-wrap; word-wrap: break-word; margin: 10px 0; }\n" +
                "code { font-family: Monospaced; background-color: " + blockBg + "; padding: 2px 4px; border-radius: 3px; font-size: 0.9em; }\n" +

                // 4. TABLES
                "table { border-collapse: collapse; width: 100%; margin: 10px 0; }\n" +
                "th, td { border: 1px solid " + borderColor + "; padding: 6px 10px; text-align: left; vertical-align: top; }\n" +
                "th { background-color: " + blockBg + "; font-weight: bold; }\n" +

                // 5. IMAGES & UTILS
                // Prevent large screenshots from creating horizontal scrollbars
                "img { max-width: 100%; height: auto; }\n" +
                "blockquote { border-left: 4px solid " + borderColor + "; margin: 10px 0; padding-left: 12px; color: " + (isDark() ? "#aaaaaa" : "#666666") + "; }\n" +
                "p { margin: 8px 0; }\n" +
                "a { color: " + linkColor + "; text-decoration: none; font-weight: bold; }";
    }

    private String toHex(Color c) {
        if (c == null) return "#000000";
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private boolean isDark() {
        Color bg = UIManager.getColor("Panel.background");
        if (bg == null) return false;
        return (0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue()) < 128;
    }
}
