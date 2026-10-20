package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import net.miginfocom.swing.MigLayout;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.events.PhaseChangedEvent;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.events.TransitionRequestedEvent;
import org.roxycode.app.events.TurnEventBridge;
import org.roxycode.app.model.ImplementationPlan;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** Workspace panel for Task mode (Plan, Code, Review phases). */
@Component
@Lazy
public final class TaskWorkspacePanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final String SESSION_PREFIX = "task";
    private static final String LAYOUT_CENTER = "center";

        private final transient WorkflowService workflowService;
    private final transient PlanService planService;
    private final transient TurnEventBridge turnEventBridge;
    private final GitChangesPanel gitChangesPanel;
    private final ChatPanel taskChatPanel;
    private final JTabbedPane taskTabs;
    private final JLabel planLabel;
    private final JPanel progressTracker;
    private final List<WorkflowPhaseIndicator> indicators = new ArrayList<>();

    public TaskWorkspacePanel(
            ChatPanelFactory chatPanelFactory,
            WorkflowService workflowService,
            PlanService planService,
            TurnEventBridge turnEventBridge,
            PlanPanel planPanel,
            GitChangesPanel gitChangesPanel) {
        super(new BorderLayout());
                this.workflowService = workflowService;
        this.planService = planService;
        this.turnEventBridge = turnEventBridge;
        this.gitChangesPanel = gitChangesPanel;

        JPanel taskHeaderBar =
                new JPanel(new MigLayout("insets 6 20 6 20, fillx", "[grow]", LAYOUT_CENTER));
        taskHeaderBar.setBorder(
                BorderFactory.createMatteBorder(
                        0, 0, 1, 0, UIManager.getColor("Component.borderColor")));

        progressTracker = new JPanel(new MigLayout("insets 0, gapx 4", "[]", LAYOUT_CENTER));
        progressTracker.setOpaque(false);
        for (WorkflowPhase phase : WorkflowPhase.values()) {
            if (phase == WorkflowPhase.EXPLORE) {
                continue;
            }
            indicators.add(new WorkflowPhaseIndicator(phase, "font: bold -1"));
        }
        taskHeaderBar.add(progressTracker, "split 2, center");

        planLabel = new JLabel();
        planLabel.putClientProperty(
                FlatClientProperties.STYLE, "font: bold -2; foreground: $Component.accentColor");
        taskHeaderBar.add(planLabel, "gapleft 16, center, hidemode 3");

                if (turnEventBridge != null) {
            turnEventBridge.subscribe(
                    PhaseChangedEvent.class, event -> updateActivePhase(event.phase()));
            turnEventBridge.subscribe(
                    PlanUpdatedEvent.class,
                    event -> updateActivePhase(workflowService.getCurrentPhase()));
        }
        updateActivePhase(workflowService.getCurrentPhase());

        this.taskTabs = new JTabbedPane();
        this.taskChatPanel = chatPanelFactory.createChatPanel(SESSION_PREFIX, null, true);
        taskTabs.addTab("Chat", taskChatPanel);
        taskTabs.addTab("Plan", planPanel);
        if (turnEventBridge != null && gitChangesPanel != null) {
            turnEventBridge.subscribe(
                    FileChangedEvent.class,
                    event -> SwingUtilities.invokeLater(gitChangesPanel::refresh));
        }
        taskTabs.addTab("Git", gitChangesPanel);
        taskTabs.addTab("History", chatPanelFactory.createHistoryPanel(SESSION_PREFIX));
        taskTabs.addChangeListener(
                e -> {
                    if (taskChatPanel.equals(taskTabs.getSelectedComponent())) {
                        SwingUtilities.invokeLater(taskChatPanel::focusInput);
                    } else if (gitChangesPanel != null
                            && gitChangesPanel.equals(taskTabs.getSelectedComponent())) {
                        gitChangesPanel.refresh();
                    }
                });

        add(taskHeaderBar, BorderLayout.NORTH);
        add(taskTabs, BorderLayout.CENTER);
    }

        public void bindSidebar(SidebarPanel sidebar) {
        this.taskChatPanel.addLoadingListener(sidebar::setTaskBusy);
        if (turnEventBridge != null) {
            turnEventBridge.subscribe(
                    TransitionRequestedEvent.class,
                    event ->
                            SwingUtilities.invokeLater(
                                    () ->
                                            sidebar.setTaskWaitingApproval(
                                                    event.requested() != null)));
            turnEventBridge.subscribe(
                    PhaseChangedEvent.class,
                    event ->
                            SwingUtilities.invokeLater(
                                    () ->
                                            sidebar.setTaskWaitingApproval(
                                                    workflowService.getPendingPhase() != null)));
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

    void updateActivePhase(WorkflowPhase currentPhase) {
        ImplementationPlan plan = planService.getCurrentPlan();
        if (plan != null && plan.title() != null && !plan.title().isBlank()) {
            planLabel.setText(plan.title().toUpperCase(Locale.ROOT));
            planLabel.setVisible(true);
        } else {
            planLabel.setVisible(false);
        }

        progressTracker.removeAll();
        var visitedPhases = workflowService.getVisitedPhases();
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
