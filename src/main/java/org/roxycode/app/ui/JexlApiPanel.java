package org.roxycode.app.ui;

import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ui.JexlToHtmlConverter;

import javax.swing.*;
import java.awt.*;

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

        SwingHtmlUtils.installTextContextMenu(textPane);
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

    /**
     * Updates the look and feel of the documentation view.
     */
    public void updateTheme() {
        SwingHtmlUtils.applyTheme(textPane, 10);
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
