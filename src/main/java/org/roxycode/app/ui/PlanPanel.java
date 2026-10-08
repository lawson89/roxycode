package org.roxycode.app.ui;

import org.roxycode.app.ai.services.PlanManagerService;
import org.roxycode.app.model.FunctionalSpec;
import org.roxycode.app.model.TechnicalSpec;

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
        updateDisplay(planManagerService.getCurrentFunctionalSpec(), planManagerService.getCurrentTechnicalSpec());
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (displayArea != null) {
            updateTheme();
            updateDisplay(planManagerService.getCurrentFunctionalSpec(), planManagerService.getCurrentTechnicalSpec());
        }
    }

    private void updateTheme() {
        SwingHtmlUtils.applyTheme(displayArea, 15);
    }

    private void updateDisplay(FunctionalSpec functionalSpec, TechnicalSpec technicalSpec) {
        StringBuilder html = new StringBuilder("<html><body>");

        if (functionalSpec == null && technicalSpec == null) {
            html.append("<h3>No specifications submitted yet.</h3>");
            html.append("<p>The AI will submit specifications as it progresses through the project.</p>");
        } else {
            if (functionalSpec != null) {
                html.append("<h2 class='spec-functional-header'>Functional Specification</h2>");
                html.append("<b>Title:</b> ").append(SwingHtmlUtils.escapeHtml(functionalSpec.title())).append("<br>");
                html.append("<b>Goal:</b> ").append(SwingHtmlUtils.escapeHtml(functionalSpec.goal())).append("<br>");
                html.append("<h3>Requirements:</h3><ul>");
                for (String req : functionalSpec.requirements()) {
                    html.append("<li>").append(SwingHtmlUtils.escapeHtml(req)).append("</li>");
                }
                html.append("</ul>");
            }

            if (technicalSpec != null) {
                html.append("<hr>");
                html.append("<h2 class='spec-technical-header'>Technical Specification</h2>");
                html.append("<b>Architecture Goal:</b> ").append(SwingHtmlUtils.escapeHtml(technicalSpec.architectureGoal())).append("<br>");
                html.append("<h3>Constraints:</h3><ul>");
                for (String con : technicalSpec.constraints()) {
                    html.append("<li>").append(SwingHtmlUtils.escapeHtml(con)).append("</li>");
                }
                html.append("</ul>");
                html.append("<h3>Implementation Steps:</h3><ol>");
                for (String step : technicalSpec.implementationSteps()) {
                    html.append("<li>").append(SwingHtmlUtils.escapeHtml(step)).append("</li>");
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
}
