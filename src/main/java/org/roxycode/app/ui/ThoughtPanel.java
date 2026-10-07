package org.roxycode.app.ui;

import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import java.awt.*;

/**
 * Collapsible panel for displaying reasoning tokens from the AI model.
 */
public class ThoughtPanel extends JPanel {
    private final JTextArea textArea;
    private final JButton toggleButton;
    private final JScrollPane scrollPane;
    private boolean expanded = false;

    public ThoughtPanel() {
        setLayout(new MigLayout("fillx, ins 2, gapy 0", "[grow][]", "[]0[]"));
        setVisible(false);

        JLabel label = new JLabel("Reasoning");
        label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
        label.setForeground(UIManager.getColor("Label.disabledForeground"));

        toggleButton = new JButton();
        toggleButton.setIcon(FontIcon.of(Codicons.CHEVRON_RIGHT, 14, UIManager.getColor("Label.disabledForeground")));
        toggleButton.setBorderPainted(false);
        toggleButton.setContentAreaFilled(false);
        toggleButton.setFocusPainted(false);
        toggleButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        toggleButton.addActionListener(e -> setExpanded(!expanded));

        textArea = new JTextArea();
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        textArea.setMargin(new Insets(5, 5, 5, 5));

        scrollPane = new JScrollPane(textArea);
        scrollPane.setBorder(null);
        scrollPane.setVisible(false);

        add(label, "growx, gapleft 5");
        add(toggleButton, "wrap");
        add(scrollPane, "growx, span 2, hidemode 3, hmax 200");

        updateColors();
    }

    private void updateColors() {
        boolean isDark = UIManager.getBoolean("flatlaf.dark");
        if (isDark) {
            textArea.setBackground(new Color(45, 45, 45));
            textArea.setForeground(new Color(200, 200, 200));
        } else {
            textArea.setBackground(new Color(255, 250, 230));
            textArea.setForeground(new Color(50, 50, 50));
        }
    }

    public void appendThought(String text) {
        if (!isVisible()) setVisible(true);
        textArea.append(text);
        if (!expanded && !text.isBlank()) {
            setExpanded(true);
        }
    }

    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
        scrollPane.setVisible(expanded);
        toggleButton.setIcon(FontIcon.of(expanded ? Codicons.CHEVRON_DOWN : Codicons.CHEVRON_RIGHT, 14, UIManager.getColor("Label.disabledForeground")));
        revalidate();
        repaint();
    }

    public void clear() {
        textArea.setText("");
        setExpanded(false);
        setVisible(false);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (textArea != null) {
            updateColors();
        }
    }
}