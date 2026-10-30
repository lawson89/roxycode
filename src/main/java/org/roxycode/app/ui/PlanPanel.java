package org.roxycode.app.ui;

import java.awt.*;
import java.util.concurrent.CompletableFuture;
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
    CompletableFuture<String> lastRefreshFuture = CompletableFuture.completedFuture("");

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

    public CompletableFuture<String> refresh() {
        return updateDisplay(planManagerService.getCurrentPlan());
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

    CompletableFuture<String> updateDisplay(ImplementationPlan plan) {
        CompletableFuture<String> future = Edt.supplyAsync(this::getGitStatusText, status -> {
            String normalized = "";
            if (status != null) {
                normalized = status;
            }
            lastGitStatus = normalized;
            applyDisplayOnEdt(plan, normalized);
        }, e -> {
            log.debug("Failed to refresh git status for plan panel", e);
            applyDisplayOnEdt(plan, lastGitStatus);
        });
        this.lastRefreshFuture = future;
        return future;
    }

    private void applyDisplayOnEdt(ImplementationPlan plan, String gitStatus) {
        if (copyMarkdownButton != null) {
            copyMarkdownButton.setEnabled(plan != null);
        }
        displayArea.setText(buildPlanHtml(plan, gitStatus));
        displayArea.setCaretPosition(0);
    }

    static String buildPlanMarkdown(ImplementationPlan plan) {
        return buildPlanMarkdown(plan, null);
    }

    static String buildPlanMarkdown(ImplementationPlan plan, String gitStatus) {
        if (plan == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(plan.toMarkdown(true));
        ImplementationPlan.appendChangesMarkdown(sb, ImplementationPlan.formatChangesText(gitStatus));
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

        String markdown = buildPlanMarkdown(plan, gitStatus);
        return "<html><body>" + SwingHtmlUtils.renderMarkdownToHtml(markdown) + "</body></html>";
    }
}
