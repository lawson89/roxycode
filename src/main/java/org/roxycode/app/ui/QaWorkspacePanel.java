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
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.events.ArchiveUpdatedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.ui.ArchiveBrowserPanel.ArchiveMode;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Workspace panel for Q&A / Explore mode (read-only mentor mode). */
@Component
@Lazy
public final class QaWorkspacePanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final String LAYOUT_CENTER = "center";
    static final String MENTOR_MODE_TITLE = "READ-ONLY MENTOR MODE";
    static final String MENTOR_MODE_SUBTITLE = "Explore & Ask Questions";
    static final String PAST_TOPICS_TAB_TITLE = "Past Topics";

    private final JTabbedPane qaTabs;
    private final ExploreChatPanel qaChatPanel;
    private final ArchiveBrowserPanel pastTopicsPanel;
    private final JPanel qaHeaderBar;
    private final JPanel mentorBanner;
    private final JLabel mentorModeLabel;
    private final JLabel mentorSubtitleLabel;

    public QaWorkspacePanel(ExploreChatPanel exploreChatPanel, MemoryService memoryService) {
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
        this.pastTopicsPanel = new ArchiveBrowserPanel(memoryService, ArchiveMode.TOPICS);
        qaTabs.addTab("Chat", qaChatPanel);
        qaTabs.addTab(PAST_TOPICS_TAB_TITLE, pastTopicsPanel);
        qaTabs.addChangeListener(
                e -> {
                    if (qaChatPanel != null && qaChatPanel.equals(qaTabs.getSelectedComponent())) {
                        SwingUtilities.invokeLater(qaChatPanel::focusInput);
                    } else if (pastTopicsPanel.equals(qaTabs.getSelectedComponent())) {
                        pastTopicsPanel.refresh();
                    }
                });

        add(qaTabs, BorderLayout.CENTER);
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        if (pastTopicsPanel != null) {
            pastTopicsPanel.refresh();
        }
    }

    @EventListener
    public void onArchiveUpdated(ArchiveUpdatedEvent event) {
        if (pastTopicsPanel != null
                && event != null
                && ArchiveUpdatedEvent.TYPE_TOPIC.equals(event.type())) {
            pastTopicsPanel.refresh();
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (mentorBanner != null) {
            applyBannerStyling();
        }
    }

    void applyBannerStyling() {
        Color accent = SwingHtmlUtils.getAccentColor();
        Color mutedFg = SwingHtmlUtils.getMutedForegroundColor();
        Color tintedBg = SwingHtmlUtils.getTintedBackground(accent, 0.12);

        mentorBanner.setBackground(tintedBg);
        mentorBanner.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 4, 0, 0, accent),
                        BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        mentorBanner.putClientProperty(
                FlatClientProperties.STYLE, "background: " + SwingHtmlUtils.toHex(tintedBg));

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

    ArchiveBrowserPanel getPastTopicsPanel() {
        return pastTopicsPanel;
    }
}
