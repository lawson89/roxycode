package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import org.roxycode.app.events.WorkflowTransitionEvent;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.ui.ArchiveBrowserPanel.ArchiveMode;
import org.roxycode.app.util.Edt;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Lazy
public final class TaskWorkspacePanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final String LAYOUT_CENTER = "center";
    static final String PHASE_HEADER_TEXT = "CURRENT PHASE";
    static final String PAST_PLANS_TAB_TITLE = "Past Plans";
    static final int MAX_PLAN_TITLE_LENGTH = 40;

    private final transient PlanService planService;
    private final PlanPanel planPanel;
    private final FeatureChatPanel taskChatPanel;
    private final ArchiveBrowserPanel pastPlansPanel;
    private final JTabbedPane taskTabs;
    private final JPanel topBar;
    private final JLabel phaseHeaderLabel = new JLabel(PHASE_HEADER_TEXT);
    private final JLabel planLabel = new JLabel();
    private final JPanel progressTracker;
    private final List<WorkflowPhaseIndicator> indicators = new ArrayList<>();
    private SidebarPanel sidebar;

    public TaskWorkspacePanel(
            FeatureChatPanel taskChatPanel,
            PlanService planService,
            PlanPanel planPanel,
            MemoryService memoryService) {
        super(new BorderLayout());
        this.planService = planService;
        this.planPanel = planPanel;
        this.taskChatPanel = taskChatPanel;
        this.pastPlansPanel = new ArchiveBrowserPanel(memoryService, ArchiveMode.PLANS);

        topBar =
                new JPanel(new MigLayout("insets 0 12 0 16, fill", "[right, grow]", LAYOUT_CENTER));
        topBar.setOpaque(false);

        progressTracker = new JPanel(new MigLayout("insets 0, gapx 2", "[]", LAYOUT_CENTER));
        applyPhaseTrackerStyling();

        for (WorkflowPhase phase : WorkflowPhase.values()) {
            if (phase != WorkflowPhase.EXPLORE) {
                indicators.add(new WorkflowPhaseIndicator(phase));
            }
        }
        topBar.add(progressTracker, "split 2, right, center");

        planLabel.setIconTextGap(6);
        applyPlanBadgeStyling();
        topBar.add(planLabel, "gapleft 16, right, center, hidemode 3");

        updateActivePhase(planService.getCurrentPhase());

        this.taskTabs = new JTabbedPane();
        taskTabs.putClientProperty(
                FlatClientProperties.TABBED_PANE_TAB_TYPE,
                FlatClientProperties.TABBED_PANE_TAB_TYPE_UNDERLINED);
        taskTabs.putClientProperty(FlatClientProperties.TABBED_PANE_TRAILING_COMPONENT, topBar);
        taskTabs.addTab("Chat", taskChatPanel);
        taskTabs.addTab("Plan", planPanel);
        taskTabs.addTab(PAST_PLANS_TAB_TITLE, pastPlansPanel);
        taskTabs.addChangeListener(
                e -> {
                    if (taskChatPanel != null
                            && taskChatPanel.equals(taskTabs.getSelectedComponent())) {
                        SwingUtilities.invokeLater(taskChatPanel::focusInput);
                    } else if (planPanel != null
                            && planPanel.equals(taskTabs.getSelectedComponent())) {
                        planPanel.refresh();
                    } else if (pastPlansPanel.equals(taskTabs.getSelectedComponent())) {
                        pastPlansPanel.refresh();
                    }
                });
        add(taskTabs, BorderLayout.CENTER);
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        if (pastPlansPanel != null) pastPlansPanel.refresh();
    }

    @EventListener
    public void onArchiveUpdated(ArchiveUpdatedEvent event) {
        if (pastPlansPanel != null
                && event != null
                && ArchiveUpdatedEvent.TYPE_PLAN.equals(event.type())) {
            pastPlansPanel.refresh();
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (planLabel != null) {
            applyPhaseTrackerStyling();
            applyPlanBadgeStyling();
            if (planService != null) updateActivePhase(planService.getCurrentPhase());
        }
    }

    void applyPhaseTrackerStyling() {
        Color accent = SwingHtmlUtils.getAccentColor();
        SwingHtmlUtils.applyTintedBannerStyle(progressTracker, accent, 2, 8, 6);
        phaseHeaderLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold -2");
        phaseHeaderLabel.setForeground(accent);
    }

    void applyPlanBadgeStyling() {
        Color accent = SwingHtmlUtils.getAccentColor();
        Color tintedBg = SwingHtmlUtils.getTintedBackground(accent, 0.10);
        planLabel.setIcon(FontIcon.of(Codicons.PROJECT, 14, accent));
        planLabel.setForeground(accent);
        planLabel.putClientProperty(
                FlatClientProperties.STYLE,
                "font: bold -2; background: "
                        + SwingHtmlUtils.toHex(tintedBg)
                        + "; arc: 10; border: 3,8,3,8");
    }

    public void bindSidebar(SidebarPanel sidebar) {
        this.sidebar = sidebar;
        this.taskChatPanel.addLoadingListener(sidebar::setTaskBusy);
    }

    @EventListener
    public void onPhaseChanged(PhaseChangedEvent event) {
        Edt.run(
                () -> {
                    updateActivePhase(event.phase());
                    if (sidebar != null) {
                        sidebar.setTaskWaitingApproval(planService.getPendingPhase() != null);
                    }
                    if (event.phase() == WorkflowPhase.PLAN && pastPlansPanel != null) {
                        pastPlansPanel.refresh();
                    }
                });
    }

    @EventListener
    public void onPlanUpdated(PlanUpdatedEvent event) {
        Edt.run(() -> updateActivePhase(planService.getCurrentPhase()));
    }

    @EventListener
    public void onWorkflowTransition(WorkflowTransitionEvent event) {
        if (sidebar != null && !event.autonomous()) {
            Edt.run(() -> sidebar.setTaskWaitingApproval(event.to() != null));
        }
    }

    public void onViewShown() {
        if (planPanel != null) planPanel.refresh();
        if (taskChatPanel != null) {
            taskTabs.setSelectedComponent(taskChatPanel);
            SwingUtilities.invokeLater(taskChatPanel::focusInput);
        }
    }

    static String formatPlanTitleDisplay(String rawTitle) {
        return StringUtils.isBlank(rawTitle)
                ? ""
                : StringUtils.abbreviate(
                        rawTitle.trim().toUpperCase(Locale.ROOT), MAX_PLAN_TITLE_LENGTH);
    }

    void updateActivePhase(WorkflowPhase currentPhase) {
        ImplementationPlan plan = planService.getCurrentPlan();
        boolean hasTitle = plan != null && plan.title() != null && !plan.title().isBlank();
        String trimmedTitle = hasTitle ? plan.title().trim() : "";
        planLabel.setText(hasTitle ? formatPlanTitleDisplay(trimmedTitle) : "");
        planLabel.setToolTipText(hasTitle ? trimmedTitle : null);
        planLabel.setVisible(hasTitle);

        progressTracker.removeAll();
        progressTracker.add(phaseHeaderLabel, "gapright 4, center");
        var visitedPhases = planService.getVisitedPhases();
        for (int i = 0; i < indicators.size(); i++) {
            WorkflowPhaseIndicator indicator = indicators.get(i);
            boolean isActive = indicator.getPhase() == currentPhase;
            boolean isCompleted =
                    visitedPhases != null && visitedPhases.contains(indicator.getPhase());
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
        Color color =
                reached
                        ? SwingHtmlUtils.getAccentColor()
                        : SwingHtmlUtils.getUiColor(
                                Color.GRAY, "Label.disabledForeground", "Component.borderColor");
        return new JLabel(FontIcon.of(Codicons.CHEVRON_RIGHT, 12, color));
    }

    JTabbedPane getTaskTabs() {
        return taskTabs;
    }

    JPanel getTopBar() {
        return topBar;
    }

    ChatPanel getTaskChatPanel() {
        return taskChatPanel;
    }

    JPanel getProgressTracker() {
        return progressTracker;
    }

    JLabel getPhaseHeaderLabel() {
        return phaseHeaderLabel;
    }

    JLabel getPlanLabel() {
        return planLabel;
    }

    PlanPanel getPlanPanel() {
        return planPanel;
    }

    ArchiveBrowserPanel getPastPlansPanel() {
        return pastPlansPanel;
    }
}
