package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatLaf;

import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.JTextComponent;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URL;

/**
 * Shared utility for Swing HTML 3.2 and CSS 1.0 styling, theming, and common editor component behaviors.
 */
public final class SwingHtmlUtils {

    private SwingHtmlUtils() {}

    /**
     * Applies theme-aware stylesheet and editor kit to a JEditorPane or JTextPane.
     *
     * @param textPane the text pane to style
     * @param marginPx the body margin in pixels
     */
    public static void applyTheme(JEditorPane textPane, int marginPx) {
        HTMLEditorKit kit = new HTMLEditorKit();
        StyleSheet sheet = new StyleSheet();

        boolean isDark = FlatLaf.isLafDark();
        String cssPath = isDark ? "/css/chat-styles-dark.css" : "/css/chat-styles-light.css";
        URL cssUrl = SwingHtmlUtils.class.getResource(cssPath);
        if (cssUrl != null) {
            sheet.importStyleSheet(cssUrl);
        }

        Color bg = UIManager.getColor("TextPane.background");
        Color fg = UIManager.getColor("TextPane.foreground");
        if (bg == null) bg = isDark ? new Color(30, 30, 30) : Color.WHITE;
        if (fg == null) fg = isDark ? Color.LIGHT_GRAY : Color.BLACK;

        textPane.setBackground(bg);
        textPane.setForeground(fg);
        textPane.setOpaque(true);

        String bodyRule = String.format("body { background-color: #%02x%02x%02x; color: #%02x%02x%02x; margin: %dpx; }",
                bg.getRed(), bg.getGreen(), bg.getBlue(),
                fg.getRed(), fg.getGreen(), fg.getBlue(),
                marginPx);
        sheet.addRule(bodyRule);

        kit.setStyleSheet(sheet);
        textPane.setEditorKit(kit);
    }

    /**
     * Installs a standard context menu (Copy, Select All) on a text component.
     *
     * @param component the text component to attach the menu to
     */
    public static void installTextContextMenu(JTextComponent component) {
        component.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) showPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) showPopup(e);
            }

            private void showPopup(MouseEvent e) {
                component.requestFocusInWindow();
                JPopupMenu menu = new JPopupMenu();
                JMenuItem copy = new JMenuItem(new DefaultEditorKit.CopyAction());
                copy.setText("Copy");
                menu.add(copy);
                menu.addSeparator();
                JMenuItem selectAll = new JMenuItem("Select All");
                selectAll.addActionListener(ae -> component.selectAll());
                menu.add(selectAll);
                menu.show(e.getComponent(), e.getX(), e.getY());
            }
        });
    }

    /**
     * Escapes text for safe inclusion in HTML bodies.
     *
     * @param text the raw text
     * @return HTML entity escaped text
     */
    public static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace(String.valueOf((char) 34), "&quot;")
                .replace("'", "&#39;");
    }
}
