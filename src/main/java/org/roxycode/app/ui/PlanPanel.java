package org.roxycode.app.ui;

import org.roxycode.app.ai.services.PlanManagerService;
import org.roxycode.app.model.ImplementationPlan;

import javax.swing.*;
import java.awt.*;

/**
 * UI panel for displaying the current project plan and specifications.
 */
public class PlanPanel extends JPanel {

    private final PlanManagerService planManagerService;
    private final JEditorPane displayArea;

    public PlanPanel(PlanManagerService planManagerService) {
        this.planManagerService = planManagerService;
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
            html.append("<h2 class='spec-technical-header'>Implementation Plan</h2>");
            html.append("<b>Title:</b> ").append(SwingHtmlUtils.escapeHtml(plan.title())).append("<br>");
            html.append("<b>Goal:</b> ").append(SwingHtmlUtils.escapeHtml(plan.goal())).append("<br>");
            
            html.append("<h3>Requirements:</h3><ul>");
            for (String req : plan.requirements()) {
                html.append("<li>").append(SwingHtmlUtils.escapeHtml(req)).append("</li>");
            }
            html.append("</ul>");

            html.append("<h3>Technical Steps:</h3><ol>");
            for (String step : plan.technicalSteps()) {
                html.append("<li>").append(SwingHtmlUtils.escapeHtml(step)).append("</li>");
            }
            html.append("</ol>");
        }

        html.append("</body></html>");

        SwingUtilities.invokeLater(() -> {
            displayArea.setText(html.toString());
            displayArea.setCaretPosition(0);
        });
    }
}