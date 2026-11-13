package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.ArchiveUpdatedEvent;
import org.roxycode.app.events.PhaseChangedEvent;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.ui.ArchiveBrowserPanel.ArchiveMode;
import org.roxycode.app.util.Edt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Lazy
final class TaskWorkspacePanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final String LAYOUT_CENTER = "center";
    static final String PLAN_TAB_BASE_TITLE = "Plan";
    static final String PAST_PLANS_TAB_TITLE = "Past Plans";
    static final int MAX_PLAN_TITLE_LENGTH = 60;
    static final String WORKFLOW_LIFECYCLE_TOOLTIP_HTML = """
            <html><div style='width: 340px;'>
            <b>Feature Development Lifecycle</b><br>
            <code>Plan &rarr; (Approve) &rarr; Code &#8644; Review &rarr; (Approve)</code><br><br>
            &bull; <b>1. Plan (Lead Architect):</b> Drafts the implementation blueprint. Requires your <b>Approve</b> click before any files are modified.<br>
            &bull; <b>2. Code (Senior Developer):</b> Applies edits and runs <code>build.toml</code> compile &amp; test gates, then autonomously enters Review.<br>
            &bull; <b>3. Review (Code Reviewer):</b> Audits the <code>git diff</code> and tests. Routes back to Code if fixes are needed, or asks for your final <b>Approve</b> sign-off.<br><br>
            <i>Tip: Hover over each phase pill for details, or ask Roxy in <b>Codebase Q&amp;A</b> if you have questions about the workflow!</i>
            </div></html>""";

    final transient PlanService planService;
    final PlanPanel planPanel;
    final ChatPanel taskChatPanel;
    final ArchiveBrowserPanel pastPlansPanel;
    final JTabbedPane taskTabs;
    final JPanel topBar;
    final JLabel workflowInfoIcon = UiComponents.createInfoIcon(WORKFLOW_LIFECYCLE_TOOLTIP_HTML);
    final JLabel planLabel = new JLabel();
    final JPanel progressTracker;
    private final List<WorkflowPhaseIndicator> indicators = new ArrayList<>();

    public TaskWorkspacePanel(@Qualifier("featureChatPanel") ChatPanel taskChatPanel, PlanService planService,
            PlanPanel planPanel, MemoryService memoryService) {
        super(new BorderLayout());
        this.planService = planService;
        this.planPanel = planPanel;
        this.taskChatPanel = taskChatPanel;
        this.pastPlansPanel = new ArchiveBrowserPanel(memoryService, ArchiveMode.PLANS);

        topBar = new JPanel(new MigLayout("insets 0 16 0 16, gapx 0, fillx", "[left]12:push[][]", LAYOUT_CENTER));
        topBar.setOpaque(false);

        planLabel.setIconTextGap(6);
        planLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        planLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (taskTabs != null && taskTabs.getTabCount() > 1) {
                    taskTabs.setSelectedIndex(1);
                }
            }
        });
        applyPlanBadgeStyling();
        topBar.add(planLabel, "cell 0 0, hidemode 3");

        progressTracker = new JPanel(new MigLayout("insets 0, gapx 2", "[]", LAYOUT_CENTER));
        applyPhaseTrackerStyling();

        for (WorkflowPhase phase : WorkflowPhase.values()) {
            if (phase != WorkflowPhase.EXPLORE) {
                indicators.add(new WorkflowPhaseIndicator(phase));
            }
        }
        topBar.add(progressTracker, "cell 1 0");
        topBar.add(workflowInfoIcon, "cell 2 0, gapleft 8");

        this.taskTabs = UiComponents.createUnderlinedTabbedPane(topBar);
        Color accent = SwingHtmlUtils.getAccentColor();
        Color muted = SwingHtmlUtils.getMutedForegroundColor();
        taskTabs.addTab("Chat", FontIcon.of(Codicons.COMMENT_DISCUSSION, 15, accent), taskChatPanel);
        taskTabs.addTab(PLAN_TAB_BASE_TITLE, FontIcon.of(Codicons.LIST_ORDERED, 15, muted), planPanel);
        taskTabs.addTab(PAST_PLANS_TAB_TITLE, FontIcon.of(Codicons.HISTORY, 15, muted), pastPlansPanel);
        this.pastPlansPanel.setOnTotalCountChanged(this::updatePastPlansTabTitle);
        if (memoryService != null) {
            this.pastPlansPanel.refresh();
        }

        updateActivePhase(planService.getCurrentPhase());
        taskTabs.addChangeListener(e -> {
            UiComponents.updateSelectedTabIcons(taskTabs,
                    List.of(Codicons.COMMENT_DISCUSSION, Codicons.LIST_ORDERED, Codicons.HISTORY));
            if (taskChatPanel != null && taskChatPanel.equals(taskTabs.getSelectedComponent())) {
                SwingUtilities.invokeLater(taskChatPanel::focusInput);
            } else if (planPanel != null && planPanel.equals(taskTabs.getSelectedComponent())) {
                planPanel.refresh();
            } else if (pastPlansPanel.equals(taskTabs.getSelectedComponent())) {
                pastPlansPanel.refresh();
            }
        });
        if (this.taskChatPanel != null) {
            this.taskChatPanel.setOnViewPlanAction(() -> {
                if (this.planPanel != null) {
                    this.taskTabs.setSelectedComponent(this.planPanel);
                }
            });
        }
        add(taskTabs, BorderLayout.CENTER);
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        if (pastPlansPanel != null) {
            pastPlansPanel.refresh();
        }
    }

    @EventListener
    public void onArchiveUpdated(ArchiveUpdatedEvent event) {
        if (pastPlansPanel != null && event != null && ArchiveUpdatedEvent.TYPE_PLAN.equals(event.type())) {
            pastPlansPanel.refresh();
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (planLabel != null) {
            applyPhaseTrackerStyling();
            applyPlanBadgeStyling();
            if (planService != null) {
                updateActivePhase(planService.getCurrentPhase());
            }
        }
    }

    void applyPhaseTrackerStyling() {
        Color accent = SwingHtmlUtils.getAccentColor();
        Color tintedBg = SwingHtmlUtils.getTintedBackground(accent, 0.08);
        progressTracker.setOpaque(true);
        progressTracker.setBackground(tintedBg);
        progressTracker.setBorder(javax.swing.BorderFactory.createEmptyBorder(3, 10, 3, 10));
        progressTracker.putClientProperty(FlatClientProperties.STYLE,
                "arc: 12; background: " + SwingHtmlUtils.toHex(tintedBg));
        UiComponents.updateInfoIconTheme(workflowInfoIcon);
    }

    void applyPlanBadgeStyling() {
        Color accent = SwingHtmlUtils.getAccentColor();
        Color tintedBg = SwingHtmlUtils.getTintedBackground(accent, 0.10);
        planLabel.setIcon(FontIcon.of(Codicons.PROJECT, 14, accent));
        planLabel.setForeground(accent);
        planLabel.putClientProperty(FlatClientProperties.STYLE,
                "font: bold -2; background: " + SwingHtmlUtils.toHex(tintedBg) + "; arc: 10; border: 3,8,3,8");
        UiComponents.updateSelectedTabIcons(taskTabs,
                List.of(Codicons.COMMENT_DISCUSSION, Codicons.LIST_ORDERED, Codicons.HISTORY));
    }

    @EventListener
    public void onPhaseChanged(PhaseChangedEvent event) {
        Edt.run(() -> {
            updateActivePhase(event.phase());
            if (event.phase() == WorkflowPhase.PLAN && pastPlansPanel != null) {
                pastPlansPanel.refresh();
            }
        });
    }

    @EventListener
    public void onPlanUpdated(PlanUpdatedEvent event) {
        Edt.run(() -> updateActivePhase(planService.getCurrentPhase()));
    }

    public void onViewShown() {
        if (planPanel != null) {
            planPanel.refresh();
        }
        if (taskChatPanel != null) {
            taskTabs.setSelectedComponent(taskChatPanel);
            SwingUtilities.invokeLater(taskChatPanel::focusInput);
        }
    }

    void updatePastPlansTabTitle(int count) {
        if (taskTabs == null || taskTabs.getTabCount() <= 2) {
            return;
        }
        if (count > 0) {
            taskTabs.setTitleAt(2, PAST_PLANS_TAB_TITLE + " (" + count + ")");
        } else {
            taskTabs.setTitleAt(2, PAST_PLANS_TAB_TITLE);
        }
    }

    static String formatPlanTitleDisplay(String rawTitle) {
        if (StringUtils.isBlank(rawTitle)) {
            return "";
        }
        return StringUtils.abbreviate(rawTitle.trim(), MAX_PLAN_TITLE_LENGTH);
    }

    void updateActivePhase(WorkflowPhase currentPhase) {
        ImplementationPlan plan = planService.getCurrentPlan();
        boolean hasTitle = plan != null && plan.title() != null && !plan.title().isBlank();
        if (hasTitle) {
            String trimmedTitle = plan.title().trim();
            planLabel.setText(formatPlanTitleDisplay(trimmedTitle));
            planLabel.setToolTipText(trimmedTitle + " (Click to view Plan)");
        } else {
            planLabel.setText("");
            planLabel.setToolTipText(null);
        }
        planLabel.setVisible(hasTitle);

        if (taskTabs != null && taskTabs.getTabCount() > 1) {
            if (plan != null && plan.technicalSteps() != null && !plan.technicalSteps().isEmpty()) {
                long completed = plan.completedStepCount();
                int total = plan.technicalSteps().size();
                taskTabs.setTitleAt(1, PLAN_TAB_BASE_TITLE + " (" + completed + "/" + total + ")");
            } else {
                taskTabs.setTitleAt(1, PLAN_TAB_BASE_TITLE);
            }
        }

        progressTracker.removeAll();
        Set<WorkflowPhase> visitedPhases = planService.getVisitedPhases();
        for (int i = 0; i < indicators.size(); i++) {
            WorkflowPhaseIndicator indicator = indicators.get(i);
            boolean isActive = indicator.getPhase() == currentPhase;
            boolean isCompleted = visitedPhases != null && visitedPhases.contains(indicator.getPhase());
            indicator.setActive(isActive);
            indicator.setCompleted(isCompleted);
            if (i > 0) {
                progressTracker.add(createConnectorIcon(isActive || isCompleted), LAYOUT_CENTER);
            }
            progressTracker.add(indicator);
        }
        progressTracker.revalidate();
        progressTracker.repaint();
        if (topBar != null) {
            topBar.revalidate();
            topBar.repaint();
        }
    }

    private JLabel createConnectorIcon(boolean reached) {
        Color color;
        if (reached) {
            color = SwingHtmlUtils.getAccentColor();
        } else {
            color = SwingHtmlUtils.getUiColor(Color.GRAY, "Label.disabledForeground", "Component.borderColor");
        }
        return new JLabel(FontIcon.of(Codicons.CHEVRON_RIGHT, 12, color));
    }
}
