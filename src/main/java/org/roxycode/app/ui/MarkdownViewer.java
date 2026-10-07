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
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URL;
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
        
        render();
    }

    public void setContent(String markdown) {
        this.currentMarkdown = markdown == null ? "" : markdown;
        render();
    }

    private void render() {
        String htmlContent = renderer.render(parser.parse(currentMarkdown));
        setText("<html><body>" + htmlContent + "</body></html>");
        setCaretPosition(0);
    }
}