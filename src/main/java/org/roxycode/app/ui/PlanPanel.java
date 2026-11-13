package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.BorderLayout;
import java.awt.event.HierarchyEvent;
import java.util.List;
import java.util.Objects;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.PhaseChangedEvent;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.util.Edt;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Lazy
final class PlanPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int COPY_FEEDBACK_DELAY_MS = 1500;
    private static final String COPY_BUTTON_TEXT = "Copy Plan as Markdown";
    static final String COPY_COMMIT_MSG_TEXT = "Copy Commit Msg";

    private final transient PlanService planManagerService;
    final JPanel toolbar;
    final JLabel planTitleLabel = new JLabel("Implementation Plan");
    final JLabel planStepsLabel = new JLabel("No plan submitted yet");
    final JEditorPane displayArea;
    final JButton copyCommitMessageButton;
    final JButton copyMarkdownButton;
    private Timer copyCommitFeedbackTimer;
    private Timer copyFeedbackTimer;
    String lastCopiedCommitMessage;
    String lastCopiedMarkdown;
    private transient ImplementationPlan lastRenderedPlan;
    private boolean displayInitialized;
    private boolean htmlDirty = true;

    public PlanPanel(PlanService planManagerService) {
        this.planManagerService = planManagerService;
        setLayout(new BorderLayout());

        toolbar = new JPanel(new MigLayout("insets 8 12 8 12, fillx, hidemode 3", "[grow, fill][]", "center"));
        toolbar.setBorder(SwingHtmlUtils.createThemeMatteBorder(0, 0, 1, 0));

        JPanel headerInfo = new JPanel(new MigLayout("insets 0, gapy 2", "[grow, fill]", "[]"));
        headerInfo.setOpaque(false);
        planTitleLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold +1");
        planStepsLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1; foreground: $Label.disabledForeground");
        headerInfo.add(planTitleLabel, "wrap");
        headerInfo.add(planStepsLabel);

        JPanel actionButtons = new JPanel(new MigLayout("insets 0, gapx 8, hidemode 3", "", "center"));
        actionButtons.setOpaque(false);
        copyCommitMessageButton = UiComponents.createCopyButton(COPY_COMMIT_MSG_TEXT, false, this::copyCommitMessage);
        copyCommitMessageButton.setVisible(false);
        actionButtons.add(copyCommitMessageButton);
        copyMarkdownButton = UiComponents.createCopyButton(COPY_BUTTON_TEXT, false, this::copyPlanAsMarkdown);
        actionButtons.add(copyMarkdownButton);

        toolbar.add(headerInfo, "growx, wmin 0");
        toolbar.add(actionButtons, "alignx right");
        add(toolbar, BorderLayout.NORTH);

        displayArea = SwingHtmlUtils.createHtmlEditorPane();
        add(UiComponents.createHtmlScrollPane(displayArea), BorderLayout.CENTER);

        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing() && htmlDirty) {
                applyDisplayOnEdt(planManagerService.getCurrentPlan());
            }
        });

        updateTheme();
        refresh();
    }

    public void refresh() {
        updateDisplay(planManagerService.getCurrentPlan());
    }

    @EventListener
    public void onPlanUpdated(PlanUpdatedEvent event) {
        updateDisplay(event.plan());
    }

    @EventListener
    public void onPhaseChanged(PhaseChangedEvent event) {
        refresh();
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        refresh();
    }

    private void copyCommitMessage() {
        ImplementationPlan plan = planManagerService.getCurrentPlan();
        if (plan == null || StringUtils.isBlank(plan.commitMessage())) {
            return;
        }
        String commitMsg = plan.commitMessage();
        this.lastCopiedCommitMessage = commitMsg;
        copyCommitFeedbackTimer = UiComponents.copyWithFeedback(copyCommitMessageButton, copyCommitFeedbackTimer,
                commitMsg, COPY_COMMIT_MSG_TEXT, COPY_FEEDBACK_DELAY_MS);
    }

    private void copyPlanAsMarkdown() {
        ImplementationPlan plan = planManagerService.getCurrentPlan();
        if (plan == null) {
            return;
        }
        String markdown = plan.toFullMarkdown();
        this.lastCopiedMarkdown = markdown;
        copyFeedbackTimer = UiComponents.copyWithFeedback(copyMarkdownButton, copyFeedbackTimer, markdown,
                COPY_BUTTON_TEXT, COPY_FEEDBACK_DELAY_MS);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (displayArea != null) {
            updateTheme();
            htmlDirty = true;
            if (planManagerService != null) {
                applyDisplayOnEdt(planManagerService.getCurrentPlan());
            }
        }
    }

    private void updateTheme() {
        if (toolbar != null) {
            toolbar.setBorder(SwingHtmlUtils.createThemeMatteBorder(0, 0, 1, 0));
        }
        UiComponents.refreshActionButtonIcons(this);
        SwingHtmlUtils.applyTheme(displayArea, 15);
    }

    void updateDisplay(ImplementationPlan plan) {
        Edt.run(() -> applyDisplayOnEdt(plan));
    }

    private void applyDisplayOnEdt(ImplementationPlan plan) {
        if (copyMarkdownButton != null) {
            copyMarkdownButton.setEnabled(plan != null);
        }
        if (copyCommitMessageButton != null) {
            boolean hasCommitMsg = plan != null && StringUtils.isNotBlank(plan.commitMessage());
            copyCommitMessageButton.setEnabled(hasCommitMsg);
            copyCommitMessageButton.setVisible(hasCommitMsg);
        }
        if (plan == null) {
            planTitleLabel.setText("Implementation Plan");
            planStepsLabel.setText("No plan submitted yet");
        } else {
            planTitleLabel.setText(StringUtils.defaultIfBlank(plan.title(), "Implementation Plan"));
            List<ImplementationPlan.TechStep> steps = plan.technicalSteps();
            int total = 0;
            if (steps != null) {
                total = steps.size();
            }
            planStepsLabel.setText(plan.completedStepCount() + " / " + total + " steps completed");
        }
        if (displayInitialized && Objects.equals(plan, lastRenderedPlan) && !htmlDirty) {
            return;
        }
        lastRenderedPlan = plan;
        if (displayInitialized && getParent() != null && !isShowing()) {
            htmlDirty = true;
            return;
        }
        displayArea.setText(buildPlanHtml(plan));
        displayArea.setCaretPosition(0);
        displayInitialized = true;
        htmlDirty = false;
    }

    static String buildPlanHtml(ImplementationPlan plan) {
        if (plan == null) {
            return """
                    <html><body>
                    <table class="welcome-card" width="100%" cellpadding="0" cellspacing="0" border="0">
                        <tr>
                            <td class="ai-accent" width="4" nowrap="nowrap">&nbsp;</td>
                            <td width="100%" style="padding: 14px 16px;">
                                <div class="welcome-title">No implementation plan submitted yet.</div>
                                <div class="welcome-hint" style="margin-top: 6px;">
                                    Describe a feature, refactoring, or bug fix in the <b>Chat</b> tab. Roxy will inspect the codebase and draft a structured blueprint here for your approval.
                                </div>
                                <table width="100%" cellpadding="3" cellspacing="0" border="0" style="margin-top: 8px;">
                                    <tr><td class="welcome-hint"><b>1. Plan</b> &mdash; Review requirements, target files, line ranges, and technical steps.</td></tr>
                                    <tr><td class="welcome-hint"><b>2. Code</b> &mdash; Track live step-by-step completion, compilation, and unit test verification.</td></tr>
                                    <tr><td class="welcome-hint"><b>3. Review</b> &mdash; Inspect final audit status and copy the generated commit message.</td></tr>
                                </table>
                            </td>
                        </tr>
                    </table>
                    </body></html>
                    """;
        }

        return "<html><body>" + SwingHtmlUtils.renderMarkdownToHtml(plan.toFullMarkdown()) + "</body></html>";
    }
}
