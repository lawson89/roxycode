package org.roxycode.app.ui;

import java.awt.FlowLayout;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.events.SystemToolsUpdatedEvent;
import org.roxycode.app.util.Edt;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Lazy
public final class JexlApiPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    static final int COPY_FEEDBACK_DELAY_MS = 1500;
    static final String PAGE_TITLE = "Agent Tools API";
    static final String PAGE_SUBTITLE = "Auto-generated JEXL @AgentService methods and TypeScript-style signatures available in executeJexl.";
    static final String COPY_BUTTON_TEXT = "Copy";
    static final String REFRESH_BUTTON_TEXT = "Refresh";

    private final transient JexlServiceRegistry registry;
    final JLabel titleLabel = new JLabel(PAGE_TITLE);
    final JLabel subtitleLabel = new JLabel(PAGE_SUBTITLE);
    final JEditorPane textPane = SwingHtmlUtils.createHtmlEditorPane();
    final JButton copyBtn;
    final JButton refreshBtn;
    private Timer copyFeedbackTimer;

    public JexlApiPanel(JexlServiceRegistry registry) {
        this.registry = registry;
        setLayout(new MigLayout("fill, insets 20, hidemode 3", "[grow, fill]", "[]12[grow, fill]"));

        copyBtn = UiComponents.createCopyButton(COPY_BUTTON_TEXT, true, this::copyDocumentation);
        refreshBtn = UiComponents.createActionButton(REFRESH_BUTTON_TEXT, Codicons.REFRESH, null, false, null,
                e -> refresh());

        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actionPanel.setOpaque(false);
        actionPanel.add(copyBtn);
        actionPanel.add(refreshBtn);

        add(UiComponents.createPageHeader(titleLabel, subtitleLabel, actionPanel), "growx, wmin 0, wrap");
        add(UiComponents.createHtmlScrollPane(textPane), "grow, push");

        updateTheme();
    }

    @EventListener
    public void onSystemToolsUpdated(SystemToolsUpdatedEvent event) {
        Edt.run(this::refresh);
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        Edt.run(this::refresh);
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
        UiComponents.refreshActionButtonIcons(this);
        refresh();
    }

    private void refresh() {
        String doc = registry.getDocumentation(null);
        textPane.setText("<html><body><pre>" + JexlToHtmlConverter.convert(doc) + "</pre></body></html>");
        textPane.setCaretPosition(0);
    }
}
