package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.Color;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Lazy
public final class QaWorkspacePanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final String LAYOUT_CENTER = "center";
    static final String MENTOR_MODE_TITLE = "MENTOR MODE";
    static final String MENTOR_MODE_SUBTITLE = "Explore & Ask Questions (read-only)";
    static final String PAST_TOPICS_TAB_TITLE = "Past Topics";
    static final String MENTOR_MODE_TOOLTIP_HTML = """
            <html><div style='width: 330px;'>
            <b>Mentor Mode &mdash; Read-Only Codebase Q&amp;A</b><br><br>
            &bull; <b>Strictly Read-Only:</b> Roxy can search, read files, inspect symbols, and check git history without modifying your project.<br>
            &bull; <b>Promote to Feature:</b> Click <b>[Move to Feature Request]</b> on any Roxy response to send findings directly into the <code>Plan &rarr; Code &rarr; Review</code> workflow.<br>
            &bull; <b>Ask About RoxyCode:</b> You can also ask Roxy how to use or configure RoxyCode itself (e.g., <code>build.toml</code>, Context folders, Agent Memory, or sandboxing)!
            </div></html>""";

    final JTabbedPane qaTabs;
    final ChatPanel qaChatPanel;
    final ArchiveBrowserPanel pastTopicsPanel;
    final JPanel qaHeaderBar;
    final JPanel mentorBanner;
    final JLabel mentorModeLabel;
    final JLabel mentorSubtitleLabel;
    final JLabel mentorInfoIcon;

    public QaWorkspacePanel(@Qualifier("exploreChatPanel") ChatPanel exploreChatPanel, MemoryService memoryService) {
        super(new BorderLayout());

        qaHeaderBar = new JPanel(new MigLayout("insets 0 12 0 16, fill", "[right, grow]", LAYOUT_CENTER));
        qaHeaderBar.setOpaque(false);

        mentorBanner = new JPanel(new MigLayout("insets 0, gapx 6", "[][][]", LAYOUT_CENTER));
        mentorModeLabel = new JLabel(MENTOR_MODE_TITLE);
        mentorModeLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold -1");
        mentorSubtitleLabel = new JLabel("•  " + MENTOR_MODE_SUBTITLE);
        mentorSubtitleLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        mentorInfoIcon = UiComponents.createInfoIcon(MENTOR_MODE_TOOLTIP_HTML);
        mentorBanner.setToolTipText(MENTOR_MODE_TOOLTIP_HTML);
        mentorModeLabel.setToolTipText(MENTOR_MODE_TOOLTIP_HTML);
        mentorSubtitleLabel.setToolTipText(MENTOR_MODE_TOOLTIP_HTML);

        mentorBanner.add(mentorModeLabel);
        mentorBanner.add(mentorSubtitleLabel);
        mentorBanner.add(mentorInfoIcon, "gapleft 2");
        applyBannerStyling();
        qaHeaderBar.add(mentorBanner, "right, center");

        this.qaTabs = UiComponents.createUnderlinedTabbedPane(qaHeaderBar);
        this.qaChatPanel = exploreChatPanel;
        this.pastTopicsPanel = new ArchiveBrowserPanel(memoryService, ArchiveMode.TOPICS);
        qaTabs.addTab("Chat", FontIcon.of(Codicons.COMMENT_DISCUSSION, 15, SwingHtmlUtils.getAccentColor()),
                qaChatPanel);
        qaTabs.addTab(PAST_TOPICS_TAB_TITLE,
                FontIcon.of(Codicons.HISTORY, 15, SwingHtmlUtils.getMutedForegroundColor()), pastTopicsPanel);
        qaTabs.addChangeListener(e -> {
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
        if (pastTopicsPanel != null && event != null && ArchiveUpdatedEvent.TYPE_TOPIC.equals(event.type())) {
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
        SwingHtmlUtils.applyTintedBannerStyle(mentorBanner, accent, 4, 10, 10);
        mentorModeLabel.setIcon(FontIcon.of(Codicons.SEARCH, 15, accent));
        mentorModeLabel.setForeground(accent);
        Color muted = SwingHtmlUtils.getMutedForegroundColor();
        mentorSubtitleLabel.setForeground(muted);
        UiComponents.updateInfoIconTheme(mentorInfoIcon);
        if (qaTabs != null && qaTabs.getTabCount() >= 2) {
            qaTabs.setIconAt(0, FontIcon.of(Codicons.COMMENT_DISCUSSION, 15, accent));
            qaTabs.setIconAt(1, FontIcon.of(Codicons.HISTORY, 15, muted));
        }
    }

    public void bindSidebar(SidebarPanel sidebar) {
        if (sidebar != null && qaChatPanel != null) {
            qaChatPanel.addLoadingListener(sidebar::setQaBusy);
        }
    }

    public void setOnMoveToFeatureListener(java.util.function.Consumer<String> listener) {
        if (qaChatPanel != null) {
            qaChatPanel.setOnMoveToFeatureListener(listener);
        }
    }

    public void focusChatInput() {
        if (qaChatPanel != null) {
            qaTabs.setSelectedComponent(qaChatPanel);
            SwingUtilities.invokeLater(qaChatPanel::focusInput);
        }
    }
}
