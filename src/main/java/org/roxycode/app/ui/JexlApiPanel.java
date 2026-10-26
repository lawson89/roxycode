package org.roxycode.app.ui;

import java.awt.*;
import javax.swing.*;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** Dedicated panel for viewing JEXL API documentation with syntax highlighting. */
@Component
@Lazy
public final class JexlApiPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    static final int COPY_FEEDBACK_DELAY_MS = 1500;
    static final String COPY_BUTTON_TEXT = "Copy";
    static final String COPIED_BUTTON_TEXT = "Copied!";

    private final transient JexlServiceRegistry registry;
    private final JTextPane textPane;
    private final JButton copyBtn;
    private Timer copyFeedbackTimer;
    private String lastCopiedDocumentation;

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
        refreshBtn.addActionListener(e -> refresh());

        copyBtn = new JButton(COPY_BUTTON_TEXT);
        copyBtn.addActionListener(e -> copyDocumentation());

        toolbar.add(copyBtn);
        toolbar.add(refreshBtn);
        add(toolbar, BorderLayout.NORTH);
    }

    private void copyDocumentation() {
        String doc = registry.getDocumentation(null);
        this.lastCopiedDocumentation = doc;
        SwingHtmlUtils.copyToClipboard(doc);

        copyBtn.setText(COPIED_BUTTON_TEXT);
        if (copyFeedbackTimer != null && copyFeedbackTimer.isRunning()) {
            copyFeedbackTimer.stop();
        }
        copyFeedbackTimer =
                new Timer(COPY_FEEDBACK_DELAY_MS, e -> copyBtn.setText(COPY_BUTTON_TEXT));
        copyFeedbackTimer.setRepeats(false);
        copyFeedbackTimer.start();
    }

    JButton getCopyButton() {
        return copyBtn;
    }

    JTextPane getTextPane() {
        return textPane;
    }

    String getLastCopiedDocumentation() {
        return lastCopiedDocumentation;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (registry != null) {
            updateTheme();
        }
    }

    /** Updates the look and feel of the documentation view. */
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
