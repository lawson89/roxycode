package org.roxycode.app.ui;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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
final class QaWorkspacePanel extends JPanel implements UiComponents.ExportableView {
    private static final long serialVersionUID = 1L;
    private static final String LAYOUT_CENTER = "center";
    static final String MENTOR_MODE_BADGE_TEXT = "MENTOR MODE • Read-Only Q&A";
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
    final JLabel mentorModeLabel;

    public QaWorkspacePanel(@Qualifier("exploreChatPanel") ChatPanel exploreChatPanel, MemoryService memoryService) {
        super(new BorderLayout());

        qaHeaderBar = new JPanel(new MigLayout("insets 0 12 0 16, fill", "[right, grow]", LAYOUT_CENTER));
        qaHeaderBar.setOpaque(false);

        mentorModeLabel = new JLabel(MENTOR_MODE_BADGE_TEXT);
        mentorModeLabel.setIconTextGap(6);
        mentorModeLabel.setToolTipText(MENTOR_MODE_TOOLTIP_HTML);
        mentorModeLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        mentorModeLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                UiComponents.showInfoTooltipImmediately(mentorModeLabel, e);
            }
        });
        applyBannerStyling();
        qaHeaderBar.add(mentorModeLabel, "right, center");

        this.qaTabs = UiComponents.createUnderlinedTabbedPane(qaHeaderBar);
        this.qaChatPanel = exploreChatPanel;
        this.pastTopicsPanel = new ArchiveBrowserPanel(memoryService, ArchiveMode.TOPICS);
        qaTabs.addTab("Chat", FontIcon.of(Codicons.COMMENT_DISCUSSION, 15, SwingHtmlUtils.getAccentColor()),
                qaChatPanel);
        qaTabs.addTab(PAST_TOPICS_TAB_TITLE,
                FontIcon.of(Codicons.HISTORY, 15, SwingHtmlUtils.getMutedForegroundColor()), pastTopicsPanel);
        this.pastTopicsPanel.setOnTotalCountChanged(this::updatePastTopicsTabTitle);
        if (memoryService != null) {
            this.pastTopicsPanel.refresh();
        }
        qaTabs.addChangeListener(e -> {
            if (qaChatPanel != null && qaChatPanel.equals(qaTabs.getSelectedComponent())) {
                SwingUtilities.invokeLater(qaChatPanel::focusInput);
            } else if (pastTopicsPanel.equals(qaTabs.getSelectedComponent())) {
                pastTopicsPanel.refresh();
            }
        });
        add(qaTabs, BorderLayout.CENTER);
    }

    void updatePastTopicsTabTitle(int count) {
        if (qaTabs == null || qaTabs.getTabCount() <= 1) {
            return;
        }
        if (count > 0) {
            qaTabs.setTitleAt(1, PAST_TOPICS_TAB_TITLE + " (" + count + ")");
        } else {
            qaTabs.setTitleAt(1, PAST_TOPICS_TAB_TITLE);
        }
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
        if (mentorModeLabel != null) {
            applyBannerStyling();
        }
    }

    void applyBannerStyling() {
        UiComponents.stylePillBadge(mentorModeLabel, MENTOR_MODE_BADGE_TEXT, SwingHtmlUtils.getAccentColor(),
                Codicons.SEARCH, 14, "4,12,4,12", false);
    }

    @Override
    public boolean canExportHtml() {
        if (qaTabs == null) {
            return false;
        }
        java.awt.Component selected = qaTabs.getSelectedComponent();
        if (pastTopicsPanel != null && pastTopicsPanel.equals(selected)) {
            return pastTopicsPanel.canExportSelectedEntry();
        }
        return qaChatPanel != null && qaChatPanel.hasExportableContent();
    }

    @Override
    public boolean exportHtml(java.awt.Component parent, @org.springframework.lang.Nullable String projectName,
            boolean saveToFile) {
        if (!canExportHtml()) {
            return false;
        }
        java.awt.Component selected = qaTabs.getSelectedComponent();
        if (pastTopicsPanel != null && pastTopicsPanel.equals(selected)) {
            if (saveToFile) {
                pastTopicsPanel.saveCurrentEntryAsHtml(parent, projectName);
            } else {
                pastTopicsPanel.exportCurrentEntryToBrowser(projectName);
            }
            return true;
        }
        return UiComponents.exportChatPanelHtml(parent, projectName, "Ask Roxy — Chat Session", "ask-roxy-chat",
                qaChatPanel, saveToFile);
    }

    void addWorkspaceStateListener(Runnable listener) {
        if (listener == null) {
            return;
        }
        if (qaTabs != null) {
            qaTabs.addChangeListener(e -> listener.run());
        }
        if (qaChatPanel != null) {
            qaChatPanel.addLoadingListener(loading -> listener.run());
            qaChatPanel.addUserInputListener(waiting -> listener.run());
            qaChatPanel.addExportStateListener(listener);
        }
        if (pastTopicsPanel != null) {
            pastTopicsPanel.addExportStateListener(listener);
        }
    }

    public void focusChatInput() {
        if (qaChatPanel != null) {
            qaTabs.setSelectedComponent(qaChatPanel);
            SwingUtilities.invokeLater(qaChatPanel::focusInput);
        }
    }
}
