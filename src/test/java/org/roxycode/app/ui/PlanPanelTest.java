package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.*;
import org.roxycode.app.model.ImplementationPlan;

class PlanPanelTest {

    private static void awaitRefresh(PlanPanel panel) {
        SwingTestUtils.flushEdt();
    }

    private static ImplementationPlan createPlan(List<ImplementationPlan.TechStep> steps,
            List<ImplementationPlan.LogEntry> logs) {
        return new ImplementationPlan("Test Plan", "Test Goal", List.of("Requirement 1"), steps, false, null, null,
                null, null, logs);
    }

    private static ImplementationPlan createPlan(ImplementationPlan.TechStep... steps) {
        return createPlan(List.of(steps), List.of());
    }

    @Test
    void testBuildPlanHtmlUsesNowrapAndNonBreakingCheckbox() {
        ImplementationPlan plan = createPlan(new ImplementationPlan.TechStep(
                "Long step description that should wrap in its column without squishing the index or checkbox.", false),
                new ImplementationPlan.TechStep("Completed step", true));

        String html = PlanPanel.buildPlanHtml(plan);
        assertContainsAll(html, "<p><b>Title:</b> Test Plan</p>", "<p><b>Goal:</b> Test Goal</p>", "[&nbsp;]",
                "<b>[x]</b>", "nowrap='nowrap'", "<td width='100%'", "<h3>Changes:</h3>", "No files changed");
        assertFalse(html.contains(">[ ]<"));

        String htmlWithChanges = PlanPanel.buildPlanHtml(plan, " M src/App.java\n?? src/NewFile.java\n");
        assertContainsAll(htmlWithChanges, "<h3>Changes:</h3>", "<pre> M src/App.java\n?? src/NewFile.java</pre>");
        assertFalse(htmlWithChanges.contains("No files changed"));

        ImplementationPlan planWithLogs = new ImplementationPlan("Test Plan", "Test Goal", List.of("Requirement 1"),
                List.of(new ImplementationPlan.TechStep("Completed step", true)), true, "2026-04-06T12:00:00", "user",
                "reviewer", "2026-04-06T13:00:00",
                List.of(new ImplementationPlan.LogEntry("2026-04-06T12:00:00", "User", "Approved")));
        String htmlWithLogs = PlanPanel.buildPlanHtml(planWithLogs);
        for (String legacy : List.of("bgcolor='#2d2d2d'", "#888888", "#569cd6", "#28a745")) {
            assertFalse(htmlWithLogs.contains(legacy));
        }
        assertContainsAll(htmlWithLogs, "<h3>Approvals:</h3>",
                "<li><b>Move to Code:</b> Approved by user on 2026-04-06T12:00:00</li>",
                "<li><b>Final Acceptance:</b> Accepted by reviewer on 2026-04-06T13:00:00</li>",
                "class='plan-step-done'", "class='plan-step-completed'", "class='plan-log-user'");
        assertTrue(htmlWithLogs.contains("class='system-entry'") || htmlWithLogs.contains("class='md-th'"));
    }

    @Test
    void testCopyMarkdownButtonAndGitChangesRefreshOnEvents() {
        PlanService planService = mock(PlanService.class);
        GitService gitService = mock(GitService.class);
        AtomicBoolean calledOnEdt = new AtomicBoolean(false);
        when(planService.getCurrentPlan()).thenReturn(null);
        when(gitService.getStatus()).thenAnswer(inv -> {
            if (SwingUtilities.isEventDispatchThread())
                calledOnEdt.set(true);
            return "";
        });

        PlanPanel panel = new PlanPanel(planService, gitService);
        awaitRefresh(panel);
        assertFalse(panel.getCopyMarkdownButton().isEnabled());

        ImplementationPlan plan = createPlan(new ImplementationPlan.TechStep("Step 0", true));
        when(planService.getCurrentPlan()).thenReturn(plan);
        panel.onPlanUpdated(new PlanUpdatedEvent(plan));
        awaitRefresh(panel);

        assertTrue(panel.getCopyMarkdownButton().isEnabled());
        assertEquals("Copy Plan as Markdown", panel.getCopyMarkdownButton().getText());
        assertTrue(panel.getDisplayArea().getText().contains("No files changed"));

        when(gitService.getStatus()).thenAnswer(inv -> {
            if (SwingUtilities.isEventDispatchThread())
                calledOnEdt.set(true);
            return " M src/Main.java\n?? src/Extra.java\n";
        });
        panel.onFileChanged(new FileChangedEvent("src/Main.java"));
        awaitRefresh(panel);
        assertContainsAll(panel.getDisplayArea().getText(), "M src/Main.java", "?? src/Extra.java");

        clearInvocations(gitService);
        SwingTestUtils.clickOnEdt(panel.getCopyMarkdownButton());
        verify(gitService, never()).getStatus();
        assertEquals("Copied!", panel.getCopyMarkdownButton().getText());
        assertContainsAll(panel.getLastCopiedMarkdown(), "# Implementation Plan: Test Plan", "- [x] 0. Step 0",
                "## Changes", " M src/Main.java");

        SwingTestUtils.runOnEdt(panel::updateUI);
        verify(gitService, never()).getStatus();

        when(gitService.getStatus()).thenAnswer(inv -> {
            if (SwingUtilities.isEventDispatchThread())
                calledOnEdt.set(true);
            return "";
        });
        panel.onProjectChanged(new ProjectChangedEvent(Path.of("/tmp/other")));
        awaitRefresh(panel);
        assertTrue(panel.getDisplayArea().getText().contains("No files changed"));
        assertFalse(calledOnEdt.get());
    }

}
