package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatLaf;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ui.JexlToHtmlConverter;

import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URL;

/**
 * Dedicated panel for viewing JEXL API documentation with syntax highlighting.
 */
public class JexlApiPanel extends JPanel {
    private final JexlServiceRegistry registry;
    private final JTextPane textPane;

    public JexlApiPanel(JexlServiceRegistry registry) {
        this.registry = registry;
        setLayout(new BorderLayout());

        textPane = new JTextPane();
        textPane.setEditable(false);
        textPane.setContentType("text/html");
        textPane.putClientProperty("JEditorPane.honorDisplayProperties", Boolean.TRUE);
        
        setupContextMenu();
        updateTheme();
        
        add(new JScrollPane(textPane), BorderLayout.CENTER);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        toolbar.setOpaque(false);
        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(e -> {
            refresh();
        });

        JButton copyBtn = new JButton("Copy");
        copyBtn.addActionListener(e -> {
            textPane.selectAll();
            textPane.copy();
            JOptionPane.showMessageDialog(this, "API Documentation copied to clipboard.");
        });

        toolbar.add(copyBtn);
        toolbar.add(refreshBtn);
        add(toolbar, BorderLayout.NORTH);

        refresh();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (registry != null) {
            updateTheme();
        }
    }

    private void setupContextMenu() {
        textPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showPopup(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showPopup(e);
                }
            }

            private void showPopup(MouseEvent e) {
                textPane.requestFocusInWindow();
                JPopupMenu menu = new JPopupMenu();
                JMenuItem copy = new JMenuItem(new DefaultEditorKit.CopyAction());
                copy.setText("Copy");
                menu.add(copy);
                menu.addSeparator();
                JMenuItem selectAll = new JMenuItem("Select All");
                selectAll.addActionListener(ae -> {
                    textPane.selectAll();
                });
                menu.add(selectAll);
                menu.show(e.getComponent(), e.getX(), e.getY());
            }
        });
    }

            /**
     * Updates the look and feel of the documentation view.
     */
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

        textPane.setBackground(bg);
        textPane.setForeground(fg);
        textPane.setOpaque(true);
        
        // Ensure body background matches in HTML
        String bodyRule = String.format("body { background-color: #%02x%02x%02x; color: #%02x%02x%02x; margin: 10px; }", 
            bg.getRed(), bg.getGreen(), bg.getBlue(),
            fg.getRed(), fg.getGreen(), fg.getBlue());
        sheet.addRule(bodyRule);
        
        kit.setStyleSheet(sheet);
        textPane.setEditorKit(kit);
        
        refresh();
    }

    private void refresh() {
        String doc = registry.getDocumentation(null);
        String highlighted = JexlToHtmlConverter.convert(doc);
        String html = "<html><body><pre>" + highlighted + "</pre></body></html>";
        textPane.setText(html);
        textPane.setCaretPosition(0);
    }
}
