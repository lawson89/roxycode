package org.roxycode.app.ui;

import org.roxycode.app.ai.JexlServiceRegistry;
import javax.swing.*;
import java.awt.*;

public class ContextViewerPanel extends JPanel {
    private final JexlServiceRegistry registry;
    private final JTextArea textArea;

    public ContextViewerPanel(JexlServiceRegistry registry) {
        this.registry = registry;
        setLayout(new BorderLayout());
        
        textArea = new JTextArea();
        textArea.setEditable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        
        add(new JScrollPane(textArea), BorderLayout.CENTER);
        
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(e -> refresh());
        
        JButton copyBtn = new JButton("Copy");
        copyBtn.addActionListener(e -> {
            textArea.selectAll();
            textArea.copy();
            JOptionPane.showMessageDialog(this, "Documentation copied to clipboard.");
        });
        
        toolbar.add(copyBtn);
        toolbar.add(refreshBtn);
        add(toolbar, BorderLayout.NORTH);
        
        refresh();
    }

    private void refresh() {
        textArea.setText(registry.getDocumentation());
        textArea.setCaretPosition(0);
    }
}
