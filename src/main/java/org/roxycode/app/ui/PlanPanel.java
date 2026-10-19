package org.roxycode.app.ui;

import java.awt.*;
import javax.swing.*;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.model.ImplementationPlan;

/** UI panel for displaying the current project plan and specifications. */
public final class PlanPanel extends JPanel {
  private static final long serialVersionUID = 1L;

  private final transient PlanService planManagerService;
  private final JEditorPane displayArea;

  public PlanPanel(PlanService planManagerService, WorkflowService workflowService) {
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
    workflowService.addPhaseListener(phase -> updateDisplay(planManagerService.getCurrentPlan()));
    updateDisplay(planManagerService.getCurrentPlan());
  }

  JEditorPane getDisplayArea() {
    return displayArea;
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
    SwingUtilities.invokeLater(
        () -> {
          displayArea.setText(html);
          displayArea.setCaretPosition(0);
        });
  }

  static String buildPlanHtml(ImplementationPlan plan) {
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

      html.append(
          "<h3>Technical Steps:</h3><table width='100%' border='0' cellpadding='4' cellspacing='0'>");
      int index = 0;
      for (ImplementationPlan.TechStep step : plan.technicalSteps()) {
        String checkbox =
            step.completed()
                ? "<font color='#28a745'><b>[x]</b></font>"
                : "<font color='#888888'>[&nbsp;]</font>";
        String textStyle =
            step.completed() ? "style='text-decoration: line-through; color: #888888;'" : "";

        html.append("<tr>");
        html.append(
                "<td valign='top' nowrap='nowrap' style='font-family: monospace; color: #888888;'>")
            .append(index)
            .append(".</td>");
        html.append("<td valign='top' nowrap='nowrap' style='font-family: monospace;'>")
            .append(checkbox)
            .append("</td>");
        html.append("<td width='100%' ")
            .append(textStyle)
            .append(">")
            .append(SwingHtmlUtils.escapeHtml(step.description()))
            .append("</td>");
        html.append("</tr>");
        index++;
      }
      html.append("</table>");

      html.append(renderLogTable("Handoff Log", plan.handoffLog()));
    }

    html.append("</body></html>");
    return html.toString();
  }

  private static String renderLogTable(
      String title, java.util.List<ImplementationPlan.LogEntry> logs) {
    if (logs == null || logs.isEmpty()) return "";

    StringBuilder sb = new StringBuilder();
    sb.append("<h3>").append(title).append(":</h3>");
    sb.append("<table width='100%' border='0' cellpadding='2' cellspacing='0'>");
    sb.append("<tr bgcolor='#2d2d2d'>");
    sb.append(
        "<td width='25%' align='left'><b><font size='2' color='#888888'>Timestamp</font></b></td>");
    sb.append(
        "<td width='15%' align='left'><b><font size='2' color='#888888'>User</font></b></td>");
    sb.append(
        "<td width='60%' align='left'><b><font size='2' color='#888888'>Message</font></b></td>");
    sb.append("</tr>");

    for (ImplementationPlan.LogEntry entry : logs) {
      String ts = entry.timestamp();
      final int timestampLength = 19;
      if (ts.length() > timestampLength) ts = ts.substring(0, timestampLength).replace('T', ' ');

      sb.append("<tr>");
      sb.append("<td valign='top'><font size='2' color='#888888'>")
          .append(SwingHtmlUtils.escapeHtml(ts))
          .append("</font></td>");
      sb.append("<td valign='top'><font size='2' color='#569cd6'>")
          .append(SwingHtmlUtils.escapeHtml(entry.user()))
          .append("</font></td>");
      sb.append("<td valign='top'><font size='3'>")
          .append(SwingHtmlUtils.escapeHtml(entry.message()))
          .append("</font></td>");
      sb.append("</tr>");
    }
    sb.append("</table>");
    return sb.toString();
  }
}
