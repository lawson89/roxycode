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
import javax.swing.UIManager;
import net.miginfocom.swing.MigLayout;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.events.PhaseChangedEvent;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.events.WorkflowTransitionEvent;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.util.Edt;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Workspace panel for Task mode (Plan, Code, Review phases). */
@Component
@Lazy
public final class TaskWorkspacePanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final String LAYOUT_CENTER = "center";
    static final int MAX_PLAN_TITLE_LENGTH = 40;
    private static final String ELLIPSIS = "...";

    private final transient PlanService planService;
    private final GitChangesPanel gitChangesPanel;
    private final FeatureChatPanel taskChatPanel;
    private final JTabbedPane taskTabs;
    private final JPanel topBar;
    private final JLabel planLabel;
    private final JPanel progressTracker;
    private final List<WorkflowPhaseIndicator> indicators = new ArrayList<>();
    private SidebarPanel sidebar;

    public TaskWorkspacePanel(
            FeatureChatPanel taskChatPanel,
            FeatureHistoryPanel taskHistoryPanel,
            PlanService planService,
            PlanPanel planPanel,
            GitChangesPanel gitChangesPanel) {
        super(new BorderLayout());
        this.planService = planService;
        this.gitChangesPanel = gitChangesPanel;
        this.taskChatPanel = taskChatPanel;

        topBar =
                new JPanel(new MigLayout("insets 0 12 0 16, fill", "[right, grow]", LAYOUT_CENTER));
        topBar.setOpaque(false);

        progressTracker = new JPanel(new MigLayout("insets 0, gapx 4", "[]", LAYOUT_CENTER));
        progressTracker.setOpaque(false);
        for (WorkflowPhase phase : WorkflowPhase.values()) {
            if (phase == WorkflowPhase.EXPLORE) {
                continue;
            }
            indicators.add(new WorkflowPhaseIndicator(phase, "font: bold -1"));
        }
        topBar.add(progressTracker, "split 2, right, center");

        planLabel = new JLabel();
        planLabel.putClientProperty(
                FlatClientProperties.STYLE, "font: bold -2; foreground: $Component.accentColor");
        topBar.add(planLabel, "gapleft 16, right, center, hidemode 3");

        updateActivePhase(planService.getCurrentPhase());

        this.taskTabs = new JTabbedPane();
        taskTabs.putClientProperty(
                FlatClientProperties.TABBED_PANE_TAB_TYPE,
                FlatClientProperties.TABBED_PANE_TAB_TYPE_UNDERLINED);
        taskTabs.putClientProperty(FlatClientProperties.TABBED_PANE_TRAILING_COMPONENT, topBar);
        taskTabs.addTab("Chat", taskChatPanel);
        taskTabs.addTab("Plan", planPanel);
        taskTabs.addTab("Git", gitChangesPanel);
        taskTabs.addTab("History", taskHistoryPanel);
        taskTabs.addChangeListener(
                e -> {
                    if (taskChatPanel.equals(taskTabs.getSelectedComponent())) {
                        SwingUtilities.invokeLater(taskChatPanel::focusInput);
                    } else if (gitChangesPanel != null
                            && gitChangesPanel.equals(taskTabs.getSelectedComponent())) {
                        gitChangesPanel.refresh();
                    }
                });

        add(taskTabs, BorderLayout.CENTER);
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
                });
    }

    @EventListener
    public void onPlanUpdated(PlanUpdatedEvent event) {
        Edt.run(() -> updateActivePhase(planService.getCurrentPhase()));
    }

    @EventListener
    public void onFileChanged(FileChangedEvent event) {
        if (gitChangesPanel != null) {
            Edt.run(gitChangesPanel::refresh);
        }
    }

    @EventListener
    public void onWorkflowTransition(WorkflowTransitionEvent event) {
        if (sidebar != null && !event.autonomous()) {
            Edt.run(() -> sidebar.setTaskWaitingApproval(event.to() != null));
        }
    }

    public void onViewShown() {
        if (gitChangesPanel != null) {
            gitChangesPanel.refresh();
        }
        if (taskChatPanel != null) {
            taskTabs.setSelectedComponent(taskChatPanel);
            SwingUtilities.invokeLater(taskChatPanel::focusInput);
        }
    }

    public void refreshGitChanges() {
        if (gitChangesPanel != null) {
            gitChangesPanel.refresh();
        }
    }

    static String formatPlanTitleDisplay(String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank()) {
            return "";
        }
        String normalized = rawTitle.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > MAX_PLAN_TITLE_LENGTH) {
            return normalized.substring(0, MAX_PLAN_TITLE_LENGTH) + ELLIPSIS;
        }
        return normalized;
    }

    void updateActivePhase(WorkflowPhase currentPhase) {
        ImplementationPlan plan = planService.getCurrentPlan();
        if (plan != null && plan.title() != null && !plan.title().isBlank()) {
            String trimmedTitle = plan.title().trim();
            planLabel.setText(formatPlanTitleDisplay(trimmedTitle));
            planLabel.setToolTipText(trimmedTitle);
            planLabel.setVisible(true);
        } else {
            planLabel.setText("");
            planLabel.setToolTipText(null);
            planLabel.setVisible(false);
        }

        progressTracker.removeAll();
        var visitedPhases = planService.getVisitedPhases();
        for (int i = 0; i < indicators.size(); i++) {
            WorkflowPhaseIndicator indicator = indicators.get(i);
            boolean isActive = indicator.getPhase() == currentPhase;
            boolean isCompleted =
                    visitedPhases != null && visitedPhases.contains(indicator.getPhase());
            indicator.setActive(isActive);
            indicator.setCompleted(isCompleted);
            if (i > 0) {
                progressTracker.add(
                        createConnectorLine(isActive || isCompleted), "w 24!, h 2!, center");
            }
            progressTracker.add(indicator);
        }
        progressTracker.revalidate();
        progressTracker.repaint();
    }

    private JPanel createConnectorLine(boolean reached) {
        JPanel line = new JPanel();
        line.setOpaque(true);
        Color accentColor = UIManager.getColor("Component.accentColor");
        Color borderColor = UIManager.getColor("Component.borderColor");
        if (reached) {
            line.setBackground(accentColor != null ? accentColor : Color.BLUE);
        } else {
            line.setBackground(borderColor != null ? borderColor : Color.GRAY);
        }
        return line;
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

    JLabel getPlanLabel() {
        return planLabel;
    }

    GitChangesPanel getGitChangesPanel() {
        return gitChangesPanel;
    }
}
