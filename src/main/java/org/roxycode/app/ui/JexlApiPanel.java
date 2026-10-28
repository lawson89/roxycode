package org.roxycode.app.ui;

import java.awt.*;
import javax.swing.*;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
@Lazy
public final class JexlApiPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    static final int COPY_FEEDBACK_DELAY_MS = 1500;
    static final String COPY_BUTTON_TEXT = "Copy";
    static final String COPIED_BUTTON_TEXT = "Copied!";

    private final transient JexlServiceRegistry registry;
    private final JTextPane textPane = new JTextPane();
    private final JButton copyBtn = new JButton(COPY_BUTTON_TEXT);
    private Timer copyFeedbackTimer;
    private String lastCopiedDocumentation;

    public JexlApiPanel(JexlServiceRegistry registry) {
        this.registry = registry;
        setLayout(new BorderLayout());

        textPane.setEditable(false);
        textPane.setContentType("text/html");
        textPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        SwingHtmlUtils.installTextContextMenu(textPane);
        updateTheme();
        add(new JScrollPane(textPane), BorderLayout.CENTER);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        toolbar.setOpaque(false);
        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(e -> refresh());
        copyBtn.addActionListener(e -> copyDocumentation());

        toolbar.add(copyBtn);
        toolbar.add(refreshBtn);
        add(toolbar, BorderLayout.NORTH);
    }

    private void copyDocumentation() {
        String doc = registry.getDocumentation(null);
        this.lastCopiedDocumentation = doc;
        SwingHtmlUtils.copyToClipboard(doc);
        copyFeedbackTimer = SwingHtmlUtils.showCopyButtonFeedback(copyBtn, copyFeedbackTimer, COPIED_BUTTON_TEXT, null,
                COPY_BUTTON_TEXT, null, COPY_FEEDBACK_DELAY_MS);
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

    public void updateTheme() {
        SwingHtmlUtils.applyTheme(textPane, 10);
        refresh();
    }

    private void refresh() {
        String doc = registry.getDocumentation(null);
        textPane.setText("<html><body><pre>" + JexlToHtmlConverter.convert(doc) + "</pre></body></html>");
        textPane.setCaretPosition(0);
    }
}
