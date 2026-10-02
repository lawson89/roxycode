package org.roxycode.app.ui;

import org.roxycode.app.ai.services.plan.PlanManagerService;
import org.roxycode.app.ai.services.specs.FunctionalSpec;
import org.roxycode.app.ai.services.specs.TechnicalSpec;

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
        
        JScrollPane scrollPane = new JScrollPane(displayArea);
        scrollPane.setBorder(null);
        this.add(scrollPane, BorderLayout.CENTER);

        planManagerService.addListener(this::updateDisplay);
        updateDisplay(planManagerService.getCurrentFunctionalSpec(), planManagerService.getCurrentTechnicalSpec());
    }

    private void updateDisplay(FunctionalSpec functionalSpec, TechnicalSpec technicalSpec) {
        StringBuilder html = new StringBuilder("<html><body style='font-family: sans-serif; padding: 10px;'>");

        if (functionalSpec == null && technicalSpec == null) {
            html.append("<h3>No specifications submitted yet.</h3>");
            html.append("<p>The AI will submit specifications as it progresses through the project.</p>");
        } else {
            if (functionalSpec != null) {
                html.append("<h2 style='color: #2196F3;'>Functional Specification</h2>");
                html.append("<b>Title:</b> ").append(escapeHtml(functionalSpec.title())).append("<br>");
                html.append("<b>Goal:</b> ").append(escapeHtml(functionalSpec.goal())).append("<br>");
                html.append("<h3>Requirements:</h3><ul>");
                for (String req : functionalSpec.requirements()) {
                    html.append("<li>").append(escapeHtml(req)).append("</li>");
                }
                html.append("</ul>");
            }

            if (technicalSpec != null) {
                html.append("<hr>");
                html.append("<h2 style='color: #4CAF50;'>Technical Specification</h2>");
                html.append("<b>Architecture Goal:</b> ").append(escapeHtml(technicalSpec.architectureGoal())).append("<br>");
                html.append("<h3>Constraints:</h3><ul>");
                for (String con : technicalSpec.constraints()) {
                    html.append("<li>").append(escapeHtml(con)).append("</li>");
                }
                html.append("</ul>");
                html.append("<h3>Implementation Steps:</h3><ol>");
                for (String step : technicalSpec.implementationSteps()) {
                    html.append("<li>").append(escapeHtml(step)).append("</li>");
                }
                html.append("</ol>");
            }
        }

        html.append("</body></html>");
        
        SwingUtilities.invokeLater(() -> {
            displayArea.setText(html.toString());
            displayArea.setCaretPosition(0);
        });
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace(String.valueOf((char)34), "&quot;")
                   .replace("'", "&#39;");
    }
}