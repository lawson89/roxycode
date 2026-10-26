package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.Color;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
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
    static final String MENTOR_MODE_TITLE = "READ-ONLY MENTOR MODE";
    static final String MENTOR_MODE_SUBTITLE = "Explore & Q&A";

    private final JTabbedPane qaTabs;
    private final ExploreChatPanel qaChatPanel;
    private final JPanel qaHeaderBar;
    private final JPanel mentorBanner;
    private final JLabel mentorModeLabel;
    private final JLabel mentorSubtitleLabel;

    public QaWorkspacePanel(ExploreChatPanel exploreChatPanel) {
        super(new BorderLayout());

        qaHeaderBar =
                new JPanel(new MigLayout("insets 0 12 0 16, fill", "[right, grow]", LAYOUT_CENTER));
        qaHeaderBar.setOpaque(false);

        mentorBanner = new JPanel(new MigLayout("insets 0, gapx 6", "[][]", LAYOUT_CENTER));
        mentorModeLabel = new JLabel(MENTOR_MODE_TITLE);
        mentorModeLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold -1");
        mentorSubtitleLabel = new JLabel("•  " + MENTOR_MODE_SUBTITLE);
        mentorSubtitleLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1");

        mentorBanner.add(mentorModeLabel);
        mentorBanner.add(mentorSubtitleLabel);
        applyBannerStyling();

        qaHeaderBar.add(mentorBanner, "right, center");

        this.qaTabs = new JTabbedPane();
        qaTabs.putClientProperty(
                FlatClientProperties.TABBED_PANE_TAB_TYPE,
                FlatClientProperties.TABBED_PANE_TAB_TYPE_UNDERLINED);
        qaTabs.putClientProperty(FlatClientProperties.TABBED_PANE_TRAILING_COMPONENT, qaHeaderBar);
        this.qaChatPanel = exploreChatPanel;
        qaTabs.addTab("Chat", qaChatPanel);
        qaTabs.addChangeListener(
                e -> {
                    if (qaChatPanel.equals(qaTabs.getSelectedComponent())) {
                        SwingUtilities.invokeLater(qaChatPanel::focusInput);
                    }
                });

        add(qaTabs, BorderLayout.CENTER);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (mentorBanner != null) {
            applyBannerStyling();
        }
    }

    static Color resolveAccentColor() {
        return SwingHtmlUtils.getUiColor(
                new Color(0x00, 0x7a, 0xcc), "Component.accentColor", "Actions.Blue");
    }

    void applyBannerStyling() {
        Color accent = resolveAccentColor();
        Color mutedFg =
                SwingHtmlUtils.getUiColor(
                        new Color(0x77, 0x77, 0x77),
                        "Label.disabledForeground",
                        "Component.infoForeground");
        String hex = SwingHtmlUtils.toHex(accent);

        mentorBanner.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 4, 0, 0, accent),
                        BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        mentorBanner.putClientProperty(
                FlatClientProperties.STYLE, "background: fade(" + hex + ", 12%)");

        mentorModeLabel.setIcon(FontIcon.of(Codicons.SEARCH, 15, accent));
        mentorModeLabel.setForeground(accent);
        mentorSubtitleLabel.setForeground(mutedFg);
    }

    public void bindSidebar(SidebarPanel sidebar) {
        if (sidebar != null && qaChatPanel != null) {
            qaChatPanel.addLoadingListener(sidebar::setQaBusy);
        }
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

    JPanel getMentorBanner() {
        return mentorBanner;
    }

    JLabel getMentorModeLabel() {
        return mentorModeLabel;
    }

    JLabel getMentorSubtitleLabel() {
        return mentorSubtitleLabel;
    }

    ChatPanel getQaChatPanel() {
        return qaChatPanel;
    }
}
