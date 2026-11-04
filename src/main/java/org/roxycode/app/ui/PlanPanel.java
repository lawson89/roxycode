package org.roxycode.app.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.Timer;
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
public final class PlanPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int COPY_FEEDBACK_DELAY_MS = 1500;
    private static final String COPY_BUTTON_TEXT = "Copy Plan as Markdown";

    private final transient PlanService planManagerService;
    final JEditorPane displayArea;
    final JButton copyMarkdownButton;
    private Timer copyFeedbackTimer;
    String lastCopiedMarkdown;

    public PlanPanel(PlanService planManagerService) {
        this.planManagerService = planManagerService;
        setLayout(new BorderLayout());

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        toolbar.setOpaque(false);
        copyMarkdownButton = UiComponents.createCopyButton(COPY_BUTTON_TEXT, false, this::copyPlanAsMarkdown);
        toolbar.add(copyMarkdownButton);
        add(toolbar, BorderLayout.NORTH);

        displayArea = SwingHtmlUtils.createHtmlEditorPane();
        add(UiComponents.createHtmlScrollPane(displayArea), BorderLayout.CENTER);

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
            if (planManagerService != null) {
                applyDisplayOnEdt(planManagerService.getCurrentPlan());
            }
        }
    }

    private void updateTheme() {
        SwingHtmlUtils.applyTheme(displayArea, 15);
    }

    void updateDisplay(ImplementationPlan plan) {
        Edt.run(() -> applyDisplayOnEdt(plan));
    }

    private void applyDisplayOnEdt(ImplementationPlan plan) {
        if (copyMarkdownButton != null) {
            copyMarkdownButton.setEnabled(plan != null);
        }
        displayArea.setText(buildPlanHtml(plan));
        displayArea.setCaretPosition(0);
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

        return "<html><body>" + SwingHtmlUtils.renderMarkdownToHtml(plan.toFullMarkdown()) + "</body></html>";
    }
}
