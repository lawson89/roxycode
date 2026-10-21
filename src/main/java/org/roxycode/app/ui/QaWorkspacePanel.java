package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** Workspace panel for Q&A / Explore mode (read-only mentor mode). */
@Component
@Lazy
public final class QaWorkspacePanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final String LAYOUT_CENTER = "center";

    private final JTabbedPane qaTabs;
    private final ExploreChatPanel qaChatPanel;
    private final JPanel qaHeaderBar;

    public QaWorkspacePanel(
            ExploreChatPanel exploreChatPanel, ExploreHistoryPanel exploreHistoryPanel) {
        super(new BorderLayout());

        qaHeaderBar =
                new JPanel(new MigLayout("insets 0 12 0 16, fill", "[right, grow]", LAYOUT_CENTER));
        qaHeaderBar.setOpaque(false);

        JLabel mentorModeLabel = new JLabel("READ-ONLY MENTOR MODE");
        mentorModeLabel.putClientProperty(
                FlatClientProperties.STYLE, "font: bold -1; foreground: $Label.disabledForeground");
        mentorModeLabel.setIcon(
                FontIcon.of(Codicons.SEARCH, 16, UIManager.getColor("Label.disabledForeground")));
        qaHeaderBar.add(mentorModeLabel, "right, center");

        this.qaTabs = new JTabbedPane();
        qaTabs.putClientProperty(
                FlatClientProperties.TABBED_PANE_TAB_TYPE,
                FlatClientProperties.TABBED_PANE_TAB_TYPE_UNDERLINED);
        qaTabs.putClientProperty(FlatClientProperties.TABBED_PANE_TRAILING_COMPONENT, qaHeaderBar);
        this.qaChatPanel = exploreChatPanel;
        qaTabs.addTab("Chat", qaChatPanel);
        qaTabs.addTab("History", exploreHistoryPanel);
        qaTabs.addChangeListener(
                e -> {
                    if (qaChatPanel.equals(qaTabs.getSelectedComponent())) {
                        SwingUtilities.invokeLater(qaChatPanel::focusInput);
                    }
                });

        add(qaTabs, BorderLayout.CENTER);
    }

    public void focusChatInput() {
        if (qaChatPanel != null) {
            qaTabs.setSelectedComponent(qaChatPanel);
            SwingUtilities.invokeLater(qaChatPanel::focusInput);
        }
    }

    JTabbedPane getQaTabs() {
        return qaTabs;
    }

    JPanel getQaHeaderBar() {
        return qaHeaderBar;
    }

    ChatPanel getQaChatPanel() {
        return qaChatPanel;
    }
}
