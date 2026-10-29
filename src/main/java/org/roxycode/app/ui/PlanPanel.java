package org.roxycode.app.ui;

import java.awt.*;
import java.util.List;
import java.util.Objects;
import javax.swing.*;
import org.apache.commons.lang3.StringUtils;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.events.PhaseChangedEvent;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.util.Edt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Lazy
public final class PlanPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(PlanPanel.class);
    private static final int COPY_FEEDBACK_DELAY_MS = 1500;
    private static final String COPY_BUTTON_TEXT = "Copy Plan as Markdown";
    static final String NO_FILES_CHANGED = ImplementationPlan.NO_FILES_CHANGED;

    private final transient PlanService planManagerService;
    private final transient GitService gitService;
    private final JEditorPane displayArea;
    private final JButton copyMarkdownButton;
    private Timer copyFeedbackTimer;
    private String lastCopiedMarkdown;
    private volatile String lastGitStatus = "";
    private transient volatile SwingWorker<String, Void> activeRefreshWorker;

    public PlanPanel(PlanService planManagerService, GitService gitService) {
        this.planManagerService = planManagerService;
        this.gitService = gitService;
        setLayout(new BorderLayout());

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        toolbar.setOpaque(false);
        copyMarkdownButton = new JButton(COPY_BUTTON_TEXT, FontIcon.of(Codicons.CLIPPY, 14));
        copyMarkdownButton.setEnabled(false);
        copyMarkdownButton.addActionListener(e -> copyPlanAsMarkdown());
        toolbar.add(copyMarkdownButton);
        add(toolbar, BorderLayout.NORTH);

        displayArea = SwingHtmlUtils.createHtmlEditorPane();
        JScrollPane scrollPane = new JScrollPane(displayArea);
        scrollPane.setBorder(null);
        add(scrollPane, BorderLayout.CENTER);

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
    public void onFileChanged(FileChangedEvent event) {
        refresh();
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        lastGitStatus = "";
        refresh();
    }

    private void copyPlanAsMarkdown() {
        ImplementationPlan plan = planManagerService.getCurrentPlan();
        if (plan == null)
            return;
        String gitStatus = SwingUtilities.isEventDispatchThread() ? lastGitStatus : getGitStatusText();
        String markdown = buildPlanMarkdown(plan, gitStatus);
        this.lastCopiedMarkdown = markdown;
        SwingHtmlUtils.copyToClipboard(markdown);

        copyFeedbackTimer = SwingHtmlUtils.showCopyButtonFeedback(copyMarkdownButton, copyFeedbackTimer, "Copied!",
                FontIcon.of(Codicons.CHECK, 14), COPY_BUTTON_TEXT, FontIcon.of(Codicons.CLIPPY, 14),
                COPY_FEEDBACK_DELAY_MS);
    }

    JEditorPane getDisplayArea() {
        return displayArea;
    }

    JButton getCopyMarkdownButton() {
        return copyMarkdownButton;
    }

    String getLastCopiedMarkdown() {
        return lastCopiedMarkdown;
    }

    String getLastGitStatus() {
        return lastGitStatus;
    }

    SwingWorker<String, Void> getActiveRefreshWorker() {
        return activeRefreshWorker;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (displayArea != null) {
            updateTheme();
            if (planManagerService != null) {
                applyDisplayOnEdt(planManagerService.getCurrentPlan(), lastGitStatus);
            }
        }
    }

    private void updateTheme() {
        SwingHtmlUtils.applyTheme(displayArea, 15);
    }

    private String getGitStatusText() {
        return gitService != null ? gitService.getStatus() : null;
    }

    private void updateDisplay(ImplementationPlan plan) {
        var holder = new java.util.concurrent.atomic.AtomicReference<SwingWorker<String, Void>>();
        SwingWorker<String, Void> worker = Edt.supplyAsync(() -> {
            try {
                String status = getGitStatusText();
                return status != null ? status : "";
            } catch (Exception e) {
                log.debug("Failed to refresh git status for plan panel", e);
                return lastGitStatus;
            }
        }, normalized -> applyWorkerResult(holder.get(), plan, normalized));
        holder.set(worker);
        activeRefreshWorker = worker;
    }

    private void applyWorkerResult(SwingWorker<String, Void> worker, ImplementationPlan plan, String status) {
        if (worker != null && !Objects.equals(worker, activeRefreshWorker))
            return;
        String normalized = status != null ? status : "";
        lastGitStatus = normalized;
        applyDisplayOnEdt(plan, normalized);
    }

    private void applyDisplayOnEdt(ImplementationPlan plan, String gitStatus) {
        if (copyMarkdownButton != null) {
            copyMarkdownButton.setEnabled(plan != null);
        }
        displayArea.setText(buildPlanHtml(plan, gitStatus));
        displayArea.setCaretPosition(0);
    }

    static String formatChangesText(String gitStatus) {
        return ImplementationPlan.formatChangesText(gitStatus);
    }

    static String buildPlanMarkdown(ImplementationPlan plan) {
        return buildPlanMarkdown(plan, null);
    }

    static String buildPlanMarkdown(ImplementationPlan plan, String gitStatus) {
        if (plan == null)
            return "";
        StringBuilder sb = new StringBuilder(plan.toMarkdown(true));
        ImplementationPlan.appendChangesMarkdown(sb, formatChangesText(gitStatus));
        return sb.toString();
    }

    static String buildPlanHtml(ImplementationPlan plan) {
        return buildPlanHtml(plan, null);
    }

    static String buildPlanHtml(ImplementationPlan plan, String gitStatus) {
        if (plan == null) {
            return """
                    <html><body>
                    <h3>No implementation plan submitted yet.</h3>
                    <p>The AI will submit a plan as it progresses through the project.</p>
                    </body></html>
                    """;
        }

        StringBuilder reqItems = new StringBuilder();
        for (String req : plan.requirements()) {
            reqItems.append("<li>").append(SwingHtmlUtils.escapeHtml(req)).append("</li>");
        }

        StringBuilder stepRows = new StringBuilder();
        int index = 0;
        for (ImplementationPlan.TechStep step : plan.technicalSteps()) {
            String checkbox = step.completed()
                    ? "<span class='plan-step-done'><b>[x]</b></span>"
                    : "<span class='plan-muted'>[&nbsp;]</span>";
            String textClass = step.completed() ? "class='plan-step-completed'" : "";
            stepRows.append("""
                    <tr>
                        <td valign='top' nowrap='nowrap' class='plan-step-index'>%d.</td>
                        <td valign='top' nowrap='nowrap' class='plan-step-check'>%s</td>
                        <td width='100%%' %s>%s</td>
                    </tr>
                    """.formatted(index, checkbox, textClass, SwingHtmlUtils.escapeHtml(step.description())));
            index++;
        }

        return """
                <html><body>
                <h2 class='spec-technical-header'>Implementation Plan</h2>
                <p><b>Title:</b> %s</p>
                <p><b>Goal:</b> %s</p>
                %s
                <h3>Requirements:</h3>
                <ul>%s</ul>
                <h3>Technical Steps:</h3>
                <table width='100%%' border='0' cellpadding='4' cellspacing='0'>
                %s
                </table>
                %s
                %s
                </body></html>
                """.formatted(SwingHtmlUtils.escapeHtml(plan.title()), SwingHtmlUtils.escapeHtml(plan.goal()),
                renderApprovalsHtml(plan), reqItems, stepRows, renderLogTable("Handoff Log", plan.handoffLog()),
                renderChangesHtml(gitStatus));
    }

    private static String renderApprovalsHtml(ImplementationPlan plan) {
        if (!plan.hasApprovals())
            return "";
        StringBuilder items = new StringBuilder();
        if (plan.hasMoveToCodeApproval()) {
            appendApprovalItem(items, "Move to Code", "Approved by ", plan.approvedBy(), plan.approvalTimestamp());
        }
        if (plan.hasFinalAcceptance()) {
            appendApprovalItem(items, "Final Acceptance", "Accepted by ", plan.completedBy(), plan.completedOn());
        }
        return "<h3>Approvals:</h3><ul>%s</ul>".formatted(items);
    }

    private static void appendApprovalItem(StringBuilder items, String label, String verb, String rawUser,
            String rawTimestamp) {
        items.append("<li><b>").append(label).append(":</b> ").append(verb)
                .append(SwingHtmlUtils.escapeHtml(StringUtils.defaultIfBlank(rawUser, "User"))).append(" on ")
                .append(SwingHtmlUtils.escapeHtml(StringUtils.defaultIfBlank(rawTimestamp, "N/A"))).append("</li>");
    }

    private static String renderChangesHtml(String gitStatus) {
        String changes = formatChangesText(gitStatus);
        return NO_FILES_CHANGED.equals(changes)
                ? "<h3>Changes:</h3><p class='plan-muted'>%s</p>".formatted(NO_FILES_CHANGED)
                : "<h3>Changes:</h3><pre>%s</pre>".formatted(SwingHtmlUtils.escapeHtml(changes));
    }

    private static String renderLogTable(String title, List<ImplementationPlan.LogEntry> logs) {
        if (logs == null || logs.isEmpty())
            return "";

        StringBuilder rows = new StringBuilder();
        for (ImplementationPlan.LogEntry entry : logs) {
            rows.append("""
                    <tr>
                        <td valign='top'><span class='plan-muted'><font size='2'>%s</font></span></td>
                        <td valign='top'><span class='plan-log-user'><font size='2'>%s</font></span></td>
                        <td valign='top'><font size='3'>%s</font></td>
                    </tr>
                    """.formatted(SwingHtmlUtils.escapeHtml(entry.formattedTimestamp()),
                    SwingHtmlUtils.escapeHtml(entry.user()), SwingHtmlUtils.escapeHtml(entry.message())));
        }

        return """
                <h3>%s:</h3>
                <table width='100%%' border='0' cellpadding='2' cellspacing='0'>
                    <tr class='system-entry'>
                        <td class='md-th' width='25%%' align='left'><b><span class='plan-muted'><font size='2'>Timestamp</font></span></b></td>
                        <td class='md-th' width='15%%' align='left'><b><span class='plan-muted'><font size='2'>User</font></span></b></td>
                        <td class='md-th' width='60%%' align='left'><b><span class='plan-muted'><font size='2'>Message</font></span></b></td>
                    </tr>
                %s
                </table>
                """
                .formatted(title, rows);
    }
}
