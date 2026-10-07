package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatLaf;
import org.roxycode.app.ai.services.PlanManagerService;
import org.roxycode.app.model.FunctionalSpec;
import org.roxycode.app.model.TechnicalSpec;

import javax.swing.*;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.net.URL;

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
        HTMLEditorKit kit = new HTMLEditorKit();
        StyleSheet sheet = new StyleSheet();

        boolean isDark = FlatLaf.isLafDark();
        String cssPath = isDark ? "/css/chat-styles-dark.css" : "/css/chat-styles-light.css";
        URL cssUrl = getClass().getResource(cssPath);
        if (cssUrl != null) {
            sheet.importStyleSheet(cssUrl);
        }

        Color bg = UIManager.getColor("TextPane.background");
        Color fg = UIManager.getColor("TextPane.foreground");
        if (bg == null) bg = isDark ? new Color(30, 30, 30) : Color.WHITE;
        if (fg == null) fg = isDark ? Color.LIGHT_GRAY : Color.BLACK;

        displayArea.setBackground(bg);
        displayArea.setForeground(fg);
        displayArea.setOpaque(true);

        // Ensure body background matches in HTML
        String bodyRule = String.format("body { background-color: #%02x%02x%02x; color: #%02x%02x%02x; margin: 15px; }", 
            bg.getRed(), bg.getGreen(), bg.getBlue(),
            fg.getRed(), fg.getGreen(), fg.getBlue());
        sheet.addRule(bodyRule);

        kit.setStyleSheet(sheet);
        displayArea.setEditorKit(kit);
    }

    private void updateDisplay(FunctionalSpec functionalSpec, TechnicalSpec technicalSpec) {
        StringBuilder html = new StringBuilder("<html><body>");

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