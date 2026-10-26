package org.roxycode.app.ui;

import java.awt.*;
import javax.swing.*;
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
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** UI panel for displaying the current project plan and specifications. */
@Component
@Lazy
public final class PlanPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int COPY_FEEDBACK_DELAY_MS = 1500;
    private static final String COPY_BUTTON_TEXT = "Copy Plan as Markdown";
    static final String NO_FILES_CHANGED = "No files changed";

    private final transient PlanService planManagerService;
    private final transient GitService gitService;
    private final JEditorPane displayArea;
    private final JButton copyMarkdownButton;
    private Timer copyFeedbackTimer;
    private String lastCopiedMarkdown;

    public PlanPanel(PlanService planManagerService, GitService gitService) {
        this.planManagerService = planManagerService;
        this.gitService = gitService;
        this.setLayout(new BorderLayout());

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        toolbar.setOpaque(false);
        copyMarkdownButton = new JButton(COPY_BUTTON_TEXT, FontIcon.of(Codicons.CLIPPY, 14));
        copyMarkdownButton.setEnabled(false);
        copyMarkdownButton.addActionListener(e -> copyPlanAsMarkdown());
        toolbar.add(copyMarkdownButton);
        this.add(toolbar, BorderLayout.NORTH);

        displayArea = new JEditorPane();
        displayArea.setEditable(false);
        displayArea.setContentType("text/html");
        displayArea.setOpaque(true);
        displayArea.putClientProperty("JEditorPane.honorDisplayProperties", true);

        JScrollPane scrollPane = new JScrollPane(displayArea);
        scrollPane.setBorder(null);
        this.add(scrollPane, BorderLayout.CENTER);

        SwingHtmlUtils.installTextContextMenu(displayArea);
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
        refresh();
    }

    private void copyPlanAsMarkdown() {
        ImplementationPlan plan = planManagerService.getCurrentPlan();
        if (plan == null) {
            return;
        }
        String markdown = buildPlanMarkdown(plan, getGitStatusText());
        this.lastCopiedMarkdown = markdown;
        SwingHtmlUtils.copyToClipboard(markdown);

        copyFeedbackTimer =
                SwingHtmlUtils.showCopyButtonFeedback(
                        copyMarkdownButton,
                        copyFeedbackTimer,
                        "Copied!",
                        FontIcon.of(Codicons.CHECK, 14),
                        COPY_BUTTON_TEXT,
                        FontIcon.of(Codicons.CLIPPY, 14),
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

    @Override
    public void updateUI() {
        super.updateUI();
        if (displayArea != null) {
            updateTheme();
            refresh();
        }
    }

    private void updateTheme() {
        SwingHtmlUtils.applyTheme(displayArea, 15);
    }

    private String getGitStatusText() {
        return gitService != null ? gitService.getStatus() : null;
    }

    private void updateDisplay(ImplementationPlan plan) {
        String html = buildPlanHtml(plan, getGitStatusText());
        boolean hasPlan = plan != null;
        Edt.run(
                () -> {
                    if (copyMarkdownButton != null) {
                        copyMarkdownButton.setEnabled(hasPlan);
                    }
                    displayArea.setText(html);
                    displayArea.setCaretPosition(0);
                });
    }

    static String formatChangesText(String gitStatus) {
        if (gitStatus == null || gitStatus.isBlank()) {
            return NO_FILES_CHANGED;
        }
        return gitStatus.stripTrailing();
    }

    static String buildPlanMarkdown(ImplementationPlan plan) {
        return buildPlanMarkdown(plan, null);
    }

    static String buildPlanMarkdown(ImplementationPlan plan, String gitStatus) {
        if (plan == null) {
            return "";
        }
        String baseMd = plan.toMarkdown(true);
        String changes = formatChangesText(gitStatus);
        StringBuilder sb = new StringBuilder(baseMd);
        sb.append("\n## Changes\n");
        if (NO_FILES_CHANGED.equals(changes)) {
            sb.append(NO_FILES_CHANGED).append("\n");
        } else {
            sb.append("```text\n").append(changes).append("\n```\n");
        }
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
            String checkbox =
                    step.completed()
                            ? "<span class='plan-step-done'><b>[x]</b></span>"
                            : "<span class='plan-muted'>[&nbsp;]</span>";
            String textClass = step.completed() ? "class='plan-step-completed'" : "";

            stepRows.append(
                    """
                    <tr>
                        <td valign='top' nowrap='nowrap' class='plan-step-index'>%d.</td>
                        <td valign='top' nowrap='nowrap' class='plan-step-check'>%s</td>
                        <td width='100%%' %s>%s</td>
                    </tr>
                    """
                            .formatted(
                                    index,
                                    checkbox,
                                    textClass,
                                    SwingHtmlUtils.escapeHtml(step.description())));
            index++;
        }

        return """
                <html><body>
                <h2 class='spec-technical-header'>Implementation Plan</h2>
                <p><b>Title:</b> %s</p>
                <p><b>Goal:</b> %s</p>
                <h3>Requirements:</h3>
                <ul>%s</ul>
                <h3>Technical Steps:</h3>
                <table width='100%%' border='0' cellpadding='4' cellspacing='0'>
                %s
                </table>
                %s
                %s
                </body></html>
                """
                .formatted(
                        SwingHtmlUtils.escapeHtml(plan.title()),
                        SwingHtmlUtils.escapeHtml(plan.goal()),
                        reqItems,
                        stepRows,
                        renderLogTable("Handoff Log", plan.handoffLog()),
                        renderChangesHtml(gitStatus));
    }

    private static String renderChangesHtml(String gitStatus) {
        String changes = formatChangesText(gitStatus);
        if (NO_FILES_CHANGED.equals(changes)) {
            return """
                    <h3>Changes:</h3>
                    <p class='plan-muted'>%s</p>
                    """
                    .formatted(NO_FILES_CHANGED);
        }
        return """
                <h3>Changes:</h3>
                <pre>%s</pre>
                """
                .formatted(SwingHtmlUtils.escapeHtml(changes));
    }

    private static String renderLogTable(
            String title, java.util.List<ImplementationPlan.LogEntry> logs) {
        if (logs == null || logs.isEmpty()) return "";

        StringBuilder rows = new StringBuilder();
        for (ImplementationPlan.LogEntry entry : logs) {
            rows.append(
                    """
                    <tr>
                        <td valign='top'><span class='plan-muted'><font size='2'>%s</font></span></td>
                        <td valign='top'><span class='plan-log-user'><font size='2'>%s</font></span></td>
                        <td valign='top'><font size='3'>%s</font></td>
                    </tr>
                    """
                            .formatted(
                                    SwingHtmlUtils.escapeHtml(entry.formattedTimestamp()),
                                    SwingHtmlUtils.escapeHtml(entry.user()),
                                    SwingHtmlUtils.escapeHtml(entry.message())));
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
