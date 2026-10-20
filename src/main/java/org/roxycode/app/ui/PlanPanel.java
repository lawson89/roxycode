package org.roxycode.app.ui;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import javax.swing.*;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.model.ImplementationPlan;

/** UI panel for displaying the current project plan and specifications. */
public final class PlanPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int COPY_FEEDBACK_DELAY_MS = 1500;
    private static final String COPY_BUTTON_TEXT = "Copy Plan as Markdown";

    private final transient PlanService planManagerService;
    private final JEditorPane displayArea;
    private final JButton copyMarkdownButton;
    private Timer copyFeedbackTimer;
    private String lastCopiedMarkdown;

    public PlanPanel(PlanService planManagerService, WorkflowService workflowService) {
        this.planManagerService = planManagerService;
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
        planManagerService.addListener(this::updateDisplay);
        workflowService.addPhaseListener(
                phase -> updateDisplay(planManagerService.getCurrentPlan()));
        updateDisplay(planManagerService.getCurrentPlan());
    }

    private void copyPlanAsMarkdown() {
        ImplementationPlan plan = planManagerService.getCurrentPlan();
        if (plan == null) {
            return;
        }
        String markdown = buildPlanMarkdown(plan);
        this.lastCopiedMarkdown = markdown;
        try {
            StringSelection selection = new StringSelection(markdown);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
        } catch (Exception ignored) {
            // Ignore clipboard access errors in headless or restricted environments
        }

        copyMarkdownButton.setText("Copied!");
        copyMarkdownButton.setIcon(FontIcon.of(Codicons.CHECK, 14));

        if (copyFeedbackTimer != null && copyFeedbackTimer.isRunning()) {
            copyFeedbackTimer.stop();
        }
        copyFeedbackTimer =
                new Timer(
                        COPY_FEEDBACK_DELAY_MS,
                        e -> {
                            copyMarkdownButton.setText(COPY_BUTTON_TEXT);
                            copyMarkdownButton.setIcon(FontIcon.of(Codicons.CLIPPY, 14));
                        });
        copyFeedbackTimer.setRepeats(false);
        copyFeedbackTimer.start();
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
            updateDisplay(planManagerService.getCurrentPlan());
        }
    }

    private void updateTheme() {
        SwingHtmlUtils.applyTheme(displayArea, 15);
    }

    private void updateDisplay(ImplementationPlan plan) {
        String html = buildPlanHtml(plan);
        boolean hasPlan = plan != null;
        if (copyMarkdownButton != null) {
            copyMarkdownButton.setEnabled(hasPlan);
        }
        SwingUtilities.invokeLater(
                () -> {
                    if (copyMarkdownButton != null) {
                        copyMarkdownButton.setEnabled(hasPlan);
                    }
                    displayArea.setText(html);
                    displayArea.setCaretPosition(0);
                });
    }

    static String buildPlanMarkdown(ImplementationPlan plan) {
        if (plan == null) {
            return "";
        }
        StringBuilder md = new StringBuilder();
        md.append(
                """
                # Implementation Plan: %s

                **Goal:** %s

                ## Requirements
                """
                        .formatted(
                                plan.title() != null ? plan.title() : "",
                                plan.goal() != null ? plan.goal() : ""));

        for (String req : plan.requirements()) {
            md.append("- ").append(req).append("\n");
        }
        md.append("\n## Technical Steps\n");
        int index = 0;
        for (ImplementationPlan.TechStep step : plan.technicalSteps()) {
            md.append(step.completed() ? "- [x] " : "- [ ] ")
                    .append(index)
                    .append(". ")
                    .append(step.description())
                    .append("\n");
            index++;
        }

        if (plan.handoffLog() != null && !plan.handoffLog().isEmpty()) {
            md.append(
                    """

                    ## Handoff Log
                    | Timestamp | User | Message |
                    | :--- | :--- | :--- |
                    """);
            for (ImplementationPlan.LogEntry entry : plan.handoffLog()) {
                String ts = entry.timestamp() != null ? entry.timestamp() : "";
                final int timestampLength = 19;
                if (ts.length() > timestampLength) {
                    ts = ts.substring(0, timestampLength).replace('T', ' ');
                }
                md.append("| ")
                        .append(escapeMarkdownTableCell(ts))
                        .append(" | ")
                        .append(escapeMarkdownTableCell(entry.user()))
                        .append(" | ")
                        .append(escapeMarkdownTableCell(entry.message()))
                        .append(" |\n");
            }
        }

        return md.toString();
    }

    private static String escapeMarkdownTableCell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }

    static String buildPlanHtml(ImplementationPlan plan) {
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
                            ? "<font color='#28a745'><b>[x]</b></font>"
                            : "<font color='#888888'>[&nbsp;]</font>";
            String textStyle =
                    step.completed()
                            ? "style='text-decoration: line-through; color: #888888;'"
                            : "";

            stepRows.append(
                    """
                    <tr>
                        <td valign='top' nowrap='nowrap' style='font-family: monospace; color: #888888;'>%d.</td>
                        <td valign='top' nowrap='nowrap' style='font-family: monospace;'>%s</td>
                        <td width='100%%' %s>%s</td>
                    </tr>
                    """
                            .formatted(
                                    index,
                                    checkbox,
                                    textStyle,
                                    SwingHtmlUtils.escapeHtml(step.description())));
            index++;
        }

        return """
                <html><body>
                <h2 class='spec-technical-header'>Implementation Plan</h2>
                <b>Title:</b> %s<br>
                <b>Goal:</b> %s<br>
                <h3>Requirements:</h3>
                <ul>%s</ul>
                <h3>Technical Steps:</h3>
                <table width='100%%' border='0' cellpadding='4' cellspacing='0'>
                %s
                </table>
                %s
                </body></html>
                """
                .formatted(
                        SwingHtmlUtils.escapeHtml(plan.title()),
                        SwingHtmlUtils.escapeHtml(plan.goal()),
                        reqItems,
                        stepRows,
                        renderLogTable("Handoff Log", plan.handoffLog()));
    }

    private static String renderLogTable(
            String title, java.util.List<ImplementationPlan.LogEntry> logs) {
        if (logs == null || logs.isEmpty()) return "";

        StringBuilder rows = new StringBuilder();
        for (ImplementationPlan.LogEntry entry : logs) {
            String ts = entry.timestamp();
            final int timestampLength = 19;
            if (ts.length() > timestampLength)
                ts = ts.substring(0, timestampLength).replace('T', ' ');

            rows.append(
                    """
                    <tr>
                        <td valign='top'><font size='2' color='#888888'>%s</font></td>
                        <td valign='top'><font size='2' color='#569cd6'>%s</font></td>
                        <td valign='top'><font size='3'>%s</font></td>
                    </tr>
                    """
                            .formatted(
                                    SwingHtmlUtils.escapeHtml(ts),
                                    SwingHtmlUtils.escapeHtml(entry.user()),
                                    SwingHtmlUtils.escapeHtml(entry.message())));
        }

        return """
                <h3>%s:</h3>
                <table width='100%%' border='0' cellpadding='2' cellspacing='0'>
                    <tr bgcolor='#2d2d2d'>
                        <td width='25%%' align='left'><b><font size='2' color='#888888'>Timestamp</font></b></td>
                        <td width='15%%' align='left'><b><font size='2' color='#888888'>User</font></b></td>
                        <td width='60%%' align='left'><b><font size='2' color='#888888'>Message</font></b></td>
                    </tr>
                %s
                </table>
                """
                .formatted(title, rows);
    }
}
