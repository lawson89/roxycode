package org.roxycode.app.ui;

import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;

import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
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
        StyleSheet sheet = kit.getStyleSheet();
        boolean isDark = UIManager.getBoolean("flatlaf.dark");
        String cssPath = isDark ? "/css/chat-styles-dark.css" : "/css/chat-styles-light.css";
        URL cssUrl = getClass().getResource(cssPath);
        if (cssUrl != null) {
            sheet.importStyleSheet(cssUrl);
        }
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
