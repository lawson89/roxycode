package org.roxycode.app.ui;

import org.roxycode.app.ai.services.PlanManagerService;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.model.ImplementationPlan;

import javax.swing.*;
import java.awt.*;

/**
 * UI panel for displaying the current project plan and specifications.
 */
public class PlanPanel extends JPanel {

    private final PlanManagerService planManagerService;
    private final WorkflowService workflowService;
    private final JEditorPane displayArea;

    public PlanPanel(PlanManagerService planManagerService, WorkflowService workflowService) {
        this.planManagerService = planManagerService;
        this.workflowService = workflowService;
        this.setLayout(new BorderLayout());

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
        workflowService.addPhaseListener(phase -> updateDisplay(planManagerService.getCurrentPlan()));
        updateDisplay(planManagerService.getCurrentPlan());
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
        StringBuilder html = new StringBuilder("<html><body>");

        if (plan == null) {
            html.append("<h3>No implementation plan submitted yet.</h3>");
            html.append("<p>The AI will submit a plan as it progresses through the project.</p>");
        } else {
            boolean isPaused = workflowService.getCurrentPhase() == WorkflowPhase.EXPLORE;
            String statusBadge = isPaused 
                ? "<span style='color: #ff9800; font-weight: bold;'> [PAUSED]</span>" 
                : "<span style='color: #28a745; font-weight: bold;'> [ACTIVE]</span>";

            html.append("<h2 class='spec-technical-header'>Implementation Plan").append(statusBadge).append("</h2>");
            if (isPaused) {
                html.append("<p style='color: #888888; font-style: italic;'>Current mode is set to EXPLORE. Switch to CHANGE mode to resume execution.</p>");
            }
            html.append("<b>Title:</b> ").append(SwingHtmlUtils.escapeHtml(plan.title())).append("<br>");
            html.append("<b>Goal:</b> ").append(SwingHtmlUtils.escapeHtml(plan.goal())).append("<br>");
            
            html.append("<h3>Requirements:</h3><ul>");
            for (String req : plan.requirements()) {
                html.append("<li>").append(SwingHtmlUtils.escapeHtml(req)).append("</li>");
            }
            html.append("</ul>");

            html.append("<h3>Technical Steps:</h3><table border='0' cellpadding='4' cellspacing='0'>");
            int index = 0;
            for (ImplementationPlan.TechStep step : plan.technicalSteps()) {
                String checkbox = step.completed() 
                    ? "<font color='#28a745'><b>[x]</b></font>" 
                    : "<font color='#888888'>[ ]</font>";
                String textStyle = step.completed() 
                    ? "style='text-decoration: line-through; color: #888888;'" 
                    : "";

                html.append("<tr>");
                html.append("<td valign='top' style='font-family: monospace; color: #888888;'>").append(index).append(".</td>");
                html.append("<td valign='top' style='font-family: monospace;'>").append(checkbox).append("</td>");
                html.append("<td ").append(textStyle).append(">").append(SwingHtmlUtils.escapeHtml(step.description())).append("</td>");
                html.append("</tr>");
                index++;
            }
            html.append("</table>");
        }

        html.append("</body></html>");

        SwingUtilities.invokeLater(() -> {
            displayArea.setText(html.toString());
            displayArea.setCaretPosition(0);
        });
    }
}
