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

    private final transient JexlServiceRegistry registry;
    private final JEditorPane textPane = SwingHtmlUtils.createHtmlEditorPane();
    private final JButton copyBtn;
    private Timer copyFeedbackTimer;

    public JexlApiPanel(JexlServiceRegistry registry) {
        this.registry = registry;
        setLayout(new BorderLayout());

        copyBtn = UiComponents.createCopyButton(COPY_BUTTON_TEXT, true, this::copyDocumentation);
        updateTheme();
        add(UiComponents.createHtmlScrollPane(textPane), BorderLayout.CENTER);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        toolbar.setOpaque(false);
        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(e -> refresh());

        toolbar.add(copyBtn);
        toolbar.add(refreshBtn);
        add(toolbar, BorderLayout.NORTH);
    }

    private void copyDocumentation() {
        String doc = registry.getDocumentation(null);
        copyFeedbackTimer = UiComponents.copyWithFeedback(copyBtn, copyFeedbackTimer, doc, COPY_BUTTON_TEXT,
                COPY_FEEDBACK_DELAY_MS);
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
