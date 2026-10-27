package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JEditorPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.text.Element;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.InlineView;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.events.PlanUpdatedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.model.ImplementationPlan;

class PlanPanelTest {

    private static void awaitRefresh(PlanPanel panel) throws Exception {
        SwingWorker<String, Void> worker = panel.getActiveRefreshWorker();
        if (worker != null) {
            worker.get(5, TimeUnit.SECONDS);
        }
        SwingUtilities.invokeAndWait(() -> {});
    }

    private static ImplementationPlan createPlan(
            List<ImplementationPlan.TechStep> steps, List<ImplementationPlan.LogEntry> logs) {
        return new ImplementationPlan(
                "Test Plan",
                "Test Goal",
                List.of("Requirement 1"),
                steps,
                false,
                null,
                null,
                null,
                logs);
    }

    private static ImplementationPlan createPlan(ImplementationPlan.TechStep... steps) {
        return createPlan(List.of(steps), List.of());
    }

    @Test
    void testBuildPlanHtmlUsesNowrapAndNonBreakingCheckbox() {
        ImplementationPlan plan =
                createPlan(
                        new ImplementationPlan.TechStep(
                                "Long step description that should wrap in its column without squishing the index or checkbox.",
                                false),
                        new ImplementationPlan.TechStep("Completed step", true));

        String html = PlanPanel.buildPlanHtml(plan);
        assertTrue(html.contains("<p><b>Title:</b> Test Plan</p>"));
        assertTrue(html.contains("<p><b>Goal:</b> Test Goal</p>"));
        assertTrue(html.contains("[&nbsp;]"));
        assertFalse(html.contains(">[ ]<"));
        assertTrue(html.contains("<b>[x]</b>"));
        assertTrue(html.contains("nowrap='nowrap'"));
        assertTrue(html.contains("<td width='100%'"));
        assertTrue(html.contains("<h3>Changes:</h3>"));
        assertTrue(html.contains("No files changed"));

        String htmlWithChanges =
                PlanPanel.buildPlanHtml(plan, " M src/App.java\n?? src/NewFile.java\n");
        assertTrue(htmlWithChanges.contains("<h3>Changes:</h3>"));
        assertTrue(htmlWithChanges.contains("<pre> M src/App.java\n?? src/NewFile.java</pre>"));
        assertFalse(htmlWithChanges.contains("No files changed"));

        ImplementationPlan planWithLogs =
                createPlan(
                        List.of(new ImplementationPlan.TechStep("Completed step", true)),
                        List.of(
                                new ImplementationPlan.LogEntry(
                                        "2026-04-06T12:00:00", "User", "Approved")));
        String htmlWithLogs = PlanPanel.buildPlanHtml(planWithLogs);
        assertFalse(htmlWithLogs.contains("bgcolor='#2d2d2d'"));
        assertFalse(
                htmlWithLogs.contains("#888888")
                        || htmlWithLogs.contains("#569cd6")
                        || htmlWithLogs.contains("#28a745"));
        assertTrue(
                htmlWithLogs.contains("class='plan-step-done'")
                        && htmlWithLogs.contains("class='plan-step-completed'")
                        && htmlWithLogs.contains("class='plan-log-user'"));
        assertTrue(
                htmlWithLogs.contains("class='system-entry'")
                        || htmlWithLogs.contains("class='md-th'"));
    }

    @Test
    void testPlanPanelRendersNonZeroMinimumSpanForNowrapCells() throws Exception {
        PlanService planService = mock(PlanService.class);
        GitService gitService = mock(GitService.class);

        when(planService.getCurrentPlan())
                .thenReturn(
                        createPlan(
                                new ImplementationPlan.TechStep(
                                        "Update src/main/resources/prompts/plan_phase.md to remove stale planService method names and refer to the provided JEXL API documentation.",
                                        false)));

        PlanPanel panel = new PlanPanel(planService, gitService);
        awaitRefresh(panel);

        JEditorPane editorPane = panel.getDisplayArea();
        ViewFactory factory = ((HTMLEditorKit) editorPane.getEditorKit()).getViewFactory();
        Element root = ((HTMLDocument) editorPane.getDocument()).getDefaultRootElement();

        Element td0 = SwingTestUtils.findTdWithText(root, "0.");
        assertNotNull(td0);
        assertTrue(hasPositiveInlineMinSpan(td0, factory));

        Element tdCheckbox = SwingTestUtils.findTdWithText(root, "[");
        assertNotNull(tdCheckbox);
        assertTrue(hasPositiveInlineMinSpan(tdCheckbox, factory));

        Element tdDesc = SwingTestUtils.findTdWithText(root, "Update src/main/resources");
        assertNotNull(tdDesc);
        assertFalse(hasPositiveInlineMinSpan(tdDesc, factory));
    }

    @Test
    void testBuildPlanMarkdownFormatsPlanHandoffLogAndChanges() {
        assertEquals("", PlanPanel.buildPlanMarkdown(null));

        ImplementationPlan plan =
                new ImplementationPlan(
                        "Visibility Feature",
                        "Improve visibility",
                        List.of("Show turns", "Export markdown"),
                        List.of(
                                new ImplementationPlan.TechStep("Update ChatPanel", true),
                                new ImplementationPlan.TechStep("Update PlanPanel", false)),
                        true,
                        "2026-04-06T12:00:00",
                        "user",
                        "reviewer",
                        "2026-04-06T13:00:00",
                        List.of(
                                new ImplementationPlan.LogEntry(
                                        "2026-04-06T12:05:30.123",
                                        "Architect",
                                        "Created plan | verified\ndetails")));

        String html = PlanPanel.buildPlanHtml(plan);
        assertTrue(html.contains("<h3>Approvals:</h3>"));
        assertTrue(
                html.contains(
                        "<li><b>Move to Code:</b> Approved by user on 2026-04-06T12:00:00</li>"));
        assertTrue(
                html.contains(
                        "<li><b>Final Acceptance:</b> Accepted by reviewer on 2026-04-06T13:00:00</li>"));

        String md = PlanPanel.buildPlanMarkdown(plan);
        assertTrue(md.contains("# Implementation Plan: Visibility Feature"));
        assertTrue(md.contains("**Goal:** Improve visibility"));
        assertTrue(md.contains("## Approvals"));
        assertTrue(md.contains("- **Move to Code:** Approved by user on 2026-04-06T12:00:00"));
        assertTrue(
                md.contains("- **Final Acceptance:** Accepted by reviewer on 2026-04-06T13:00:00"));
        assertTrue(md.contains("## Requirements\n- Show turns\n- Export markdown"));
        assertTrue(
                md.contains(
                        "## Technical Steps\n- [x] 0. Update ChatPanel\n- [ ] 1. Update PlanPanel"));
        assertTrue(md.contains("## Handoff Log"));
        assertTrue(md.contains("| Timestamp | User | Message |"));
        assertTrue(
                md.contains("| 2026-04-06 12:05:30 | Architect | Created plan ")
                        && md.contains("| verified details |"));
        assertTrue(md.contains("## Changes\nNo files changed"));

        String mdWithChanges =
                PlanPanel.buildPlanMarkdown(plan, " M src/App.java\n?? src/NewFile.java\n");
        assertTrue(mdWithChanges.contains("## Changes"));
        assertTrue(mdWithChanges.contains(" M src/App.java\n?? src/NewFile.java"));
    }

    @Test
    void testCopyMarkdownButtonAndGitChangesRefreshOnEvents() throws Exception {
        PlanService planService = mock(PlanService.class);
        GitService gitService = mock(GitService.class);

        AtomicBoolean calledOnEdt = new AtomicBoolean(false);
        when(planService.getCurrentPlan()).thenReturn(null);
        when(gitService.getStatus())
                .thenAnswer(
                        inv -> {
                            if (SwingUtilities.isEventDispatchThread()) calledOnEdt.set(true);
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

        when(gitService.getStatus())
                .thenAnswer(
                        inv -> {
                            if (SwingUtilities.isEventDispatchThread()) calledOnEdt.set(true);
                            return " M src/Main.java\n?? src/Extra.java\n";
                        });
        panel.onFileChanged(new FileChangedEvent("src/Main.java"));
        awaitRefresh(panel);

        assertTrue(panel.getDisplayArea().getText().contains("M src/Main.java"));
        assertTrue(panel.getDisplayArea().getText().contains("?? src/Extra.java"));

        clearInvocations(gitService);
        SwingUtilities.invokeAndWait(() -> panel.getCopyMarkdownButton().doClick());

        verify(gitService, never()).getStatus();
        assertEquals("Copied!", panel.getCopyMarkdownButton().getText());
        String copied = panel.getLastCopiedMarkdown();
        assertNotNull(copied);
        assertTrue(copied.contains("# Implementation Plan: Test Plan"));
        assertTrue(copied.contains("- [x] 0. Step 0"));
        assertTrue(copied.contains("## Changes"));
        assertTrue(copied.contains(" M src/Main.java"));

        SwingUtilities.invokeAndWait(panel::updateUI);
        verify(gitService, never()).getStatus();

        when(gitService.getStatus())
                .thenAnswer(
                        inv -> {
                            if (SwingUtilities.isEventDispatchThread()) calledOnEdt.set(true);
                            return "";
                        });
        panel.onProjectChanged(new ProjectChangedEvent(Path.of("/tmp/other")));
        awaitRefresh(panel);

        assertTrue(panel.getDisplayArea().getText().contains("No files changed"));
        assertFalse(calledOnEdt.get());
    }

    @Test
    void testStaleRefreshWorkerDiscarded() throws Exception {
        PlanService planService = mock(PlanService.class);
        GitService gitService = mock(GitService.class);

        when(planService.getCurrentPlan())
                .thenReturn(createPlan(new ImplementationPlan.TechStep("Step 0", false)));
        when(gitService.getStatus()).thenReturn("");

        PlanPanel panel = new PlanPanel(planService, gitService);
        awaitRefresh(panel);

        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        AtomicBoolean firstCall = new AtomicBoolean(true);

        when(gitService.getStatus())
                .thenAnswer(
                        inv -> {
                            if (firstCall.compareAndSet(true, false)) {
                                slowStarted.countDown();
                                releaseSlow.await(5, TimeUnit.SECONDS);
                                return " M src/StaleFile.java\n";
                            }
                            return " M src/FreshFile.java\n";
                        });

        panel.refresh();
        SwingWorker<String, Void> staleWorker = panel.getActiveRefreshWorker();
        assertTrue(slowStarted.await(5, TimeUnit.SECONDS));

        panel.refresh();
        SwingWorker<String, Void> freshWorker = panel.getActiveRefreshWorker();
        assertNotSame(staleWorker, freshWorker);

        freshWorker.get(5, TimeUnit.SECONDS);
        releaseSlow.countDown();
        staleWorker.get(5, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(() -> {});

        assertEquals(" M src/FreshFile.java\n", panel.getLastGitStatus());
        assertTrue(panel.getDisplayArea().getText().contains("FreshFile.java"));
        assertFalse(panel.getDisplayArea().getText().contains("StaleFile.java"));
    }

    private boolean hasPositiveInlineMinSpan(Element elem, ViewFactory factory) {
        if (factory.create(elem) instanceof InlineView iv) {
            return iv.getMinimumSpan(View.X_AXIS) > 0;
        }
        for (int i = 0; i < elem.getElementCount(); i++) {
            if (hasPositiveInlineMinSpan(elem.getElement(i), factory)) {
                return true;
            }
        }
        return false;
    }
}
