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

    @Test
    void testBuildPlanHtmlUsesNowrapAndNonBreakingCheckbox() {
        ImplementationPlan plan =
                new ImplementationPlan(
                        "Test Plan",
                        "Test Goal",
                        List.of("Requirement 1"),
                        List.of(
                                new ImplementationPlan.TechStep(
                                        "Long step description that should wrap in its column without squishing the index or checkbox.",
                                        false),
                                new ImplementationPlan.TechStep("Completed step", true)),
                        false,
                        null,
                        null,
                        null,
                        List.of());

        String html = PlanPanel.buildPlanHtml(plan);

        assertTrue(
                html.contains("<p><b>Title:</b> Test Plan</p>"),
                "Title should be wrapped in a <p> element");
        assertTrue(
                html.contains("<p><b>Goal:</b> Test Goal</p>"),
                "Goal should be wrapped in a <p> element");
        assertTrue(html.contains("[&nbsp;]"), "Unchecked checkbox should use non-breaking space");
        assertFalse(
                html.contains(">[ ]<"), "Unchecked checkbox should not use regular breaking space");
        assertTrue(html.contains("<b>[x]</b>"), "Completed checkbox should contain [x]");
        assertTrue(html.contains("nowrap='nowrap'"), "Index and checkbox cells should have nowrap");
        assertTrue(
                html.contains("<td width='100%'"),
                "Step description cell should take 100% remaining width");
        assertTrue(html.contains("<h3>Changes:</h3>"), "Plan HTML should include Changes header");
        assertTrue(
                html.contains("No files changed"),
                "Plan HTML should display 'No files changed' when git status is blank");

        String htmlWithChanges =
                PlanPanel.buildPlanHtml(plan, " M src/App.java\n?? src/NewFile.java\n");
        assertTrue(htmlWithChanges.contains("<h3>Changes:</h3>"));
        assertTrue(htmlWithChanges.contains("<pre> M src/App.java\n?? src/NewFile.java</pre>"));
        assertFalse(htmlWithChanges.contains("No files changed"));

        ImplementationPlan planWithLogs =
                new ImplementationPlan(
                        "Test Plan",
                        "Test Goal",
                        List.of("Requirement 1"),
                        List.of(new ImplementationPlan.TechStep("Completed step", true)),
                        false,
                        null,
                        null,
                        null,
                        List.of(
                                new ImplementationPlan.LogEntry(
                                        "2026-04-06T12:00:00", "User", "Approved")));
        String htmlWithLogs = PlanPanel.buildPlanHtml(planWithLogs);
        assertFalse(
                htmlWithLogs.contains("bgcolor='#2d2d2d'"),
                "Handoff log table header should not hardcode dark mode bgcolor='#2d2d2d'");
        assertFalse(
                htmlWithLogs.contains("#888888")
                        || htmlWithLogs.contains("#569cd6")
                        || htmlWithLogs.contains("#28a745"),
                "Plan HTML should not hardcode dark-theme hex colors");
        assertTrue(
                htmlWithLogs.contains("class='plan-step-done'")
                        && htmlWithLogs.contains("class='plan-step-completed'")
                        && htmlWithLogs.contains("class='plan-log-user'"),
                "Plan HTML should use theme-aware CSS classes");
        assertTrue(
                htmlWithLogs.contains("class='system-entry'")
                        || htmlWithLogs.contains("class='md-th'"),
                "Handoff log table header should use theme-aware CSS classes");
    }

    @Test
    void testPlanPanelRendersNonZeroMinimumSpanForNowrapCells() throws Exception {
        PlanService planService = mock(PlanService.class);
        GitService gitService = mock(GitService.class);

        ImplementationPlan plan =
                new ImplementationPlan(
                        "Test Plan",
                        "Test Goal",
                        List.of("Req"),
                        List.of(
                                new ImplementationPlan.TechStep(
                                        "Update src/main/resources/prompts/plan_phase.md to remove stale planService method names and refer to the provided JEXL API documentation.",
                                        false)),
                        false,
                        null,
                        null,
                        null,
                        List.of());

        when(planService.getCurrentPlan()).thenReturn(plan);

        PlanPanel panel = new PlanPanel(planService, gitService);
        awaitRefresh(panel);

        JEditorPane editorPane = panel.getDisplayArea();
        HTMLEditorKit kit = (HTMLEditorKit) editorPane.getEditorKit();
        ViewFactory factory = kit.getViewFactory();
        HTMLDocument doc = (HTMLDocument) editorPane.getDocument();

        Element td0 = SwingTestUtils.findTdWithText(doc.getDefaultRootElement(), "0.");
        assertNotNull(td0, "Should find index <td> element");
        assertTrue(
                hasPositiveInlineMinSpan(td0, factory),
                "Nowrap index cell inline content should preserve positive minimum X span so it does not wrap");

        Element tdCheckbox = SwingTestUtils.findTdWithText(doc.getDefaultRootElement(), "[");
        assertNotNull(tdCheckbox, "Should find checkbox <td> element");
        assertTrue(
                hasPositiveInlineMinSpan(tdCheckbox, factory),
                "Nowrap checkbox cell inline content should preserve positive minimum X span so it does not wrap");

        Element tdDesc =
                SwingTestUtils.findTdWithText(
                        doc.getDefaultRootElement(), "Update src/main/resources");
        assertNotNull(tdDesc, "Should find description <td> element");
        assertFalse(
                hasPositiveInlineMinSpan(tdDesc, factory),
                "Description cell inline content should have 0 minimum X span to allow wrapping");
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
                        null,
                        List.of(
                                new ImplementationPlan.LogEntry(
                                        "2026-04-06T12:05:30.123",
                                        "Architect",
                                        """
                                        Created plan | verified
                                        details""")));

        String md = PlanPanel.buildPlanMarkdown(plan);

        assertTrue(md.contains("# Implementation Plan: Visibility Feature"));
        assertTrue(md.contains("**Goal:** Improve visibility"));
        assertTrue(
                md.contains(
                        """
                        ## Requirements
                        - Show turns
                        - Export markdown"""));
        assertTrue(
                md.contains(
                        """
                        ## Technical Steps
                        - [x] 0. Update ChatPanel
                        - [ ] 1. Update PlanPanel"""));
        assertTrue(md.contains("## Handoff Log"));
        assertTrue(md.contains("| Timestamp | User | Message |"));
        assertTrue(
                md.contains("| 2026-04-06 12:05:30 | Architect | Created plan ")
                        && md.contains("| verified details |"));
        assertTrue(
                md.contains(
                        """
                        ## Changes
                        No files changed"""));

        String mdWithChanges =
                PlanPanel.buildPlanMarkdown(plan, " M src/App.java\n?? src/NewFile.java\n");
        assertTrue(
                mdWithChanges.contains(
                        """
                        ## Changes
                        ```text
                         M src/App.java
                        ?? src/NewFile.java
                        ```"""));
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
                            if (SwingUtilities.isEventDispatchThread()) {
                                calledOnEdt.set(true);
                            }
                            return "";
                        });
        PlanPanel panel = new PlanPanel(planService, gitService);
        awaitRefresh(panel);

        assertFalse(
                panel.getCopyMarkdownButton().isEnabled(),
                "Copy button should be disabled when plan is null");

        ImplementationPlan plan =
                new ImplementationPlan(
                        "Test Plan",
                        "Test Goal",
                        List.of("Req 1"),
                        List.of(new ImplementationPlan.TechStep("Step 0", true)),
                        false,
                        null,
                        null,
                        null,
                        List.of());
        when(planService.getCurrentPlan()).thenReturn(plan);
        panel.onPlanUpdated(new PlanUpdatedEvent(plan));
        awaitRefresh(panel);

        assertTrue(
                panel.getCopyMarkdownButton().isEnabled(),
                "Copy button should be enabled when plan is present");
        assertEquals("Copy Plan as Markdown", panel.getCopyMarkdownButton().getText());
        assertTrue(
                panel.getDisplayArea().getText().contains("No files changed"),
                "Actual text: " + panel.getDisplayArea().getText());

        when(gitService.getStatus())
                .thenAnswer(
                        inv -> {
                            if (SwingUtilities.isEventDispatchThread()) {
                                calledOnEdt.set(true);
                            }
                            return " M src/Main.java\n?? src/Extra.java\n";
                        });
        panel.onFileChanged(new FileChangedEvent("src/Main.java"));
        awaitRefresh(panel);

        assertTrue(panel.getDisplayArea().getText().contains("M src/Main.java"));
        assertTrue(panel.getDisplayArea().getText().contains("?? src/Extra.java"));

        clearInvocations(gitService);
        SwingUtilities.invokeAndWait(() -> panel.getCopyMarkdownButton().doClick());

        // Copy on the EDT must use cached lastGitStatus without spawning git status
        verify(gitService, never()).getStatus();
        assertEquals("Copied!", panel.getCopyMarkdownButton().getText());
        assertNotNull(panel.getLastCopiedMarkdown());
        assertTrue(panel.getLastCopiedMarkdown().contains("# Implementation Plan: Test Plan"));
        assertTrue(panel.getLastCopiedMarkdown().contains("- [x] 0. Step 0"));
        assertTrue(panel.getLastCopiedMarkdown().contains("## Changes"));
        assertTrue(panel.getLastCopiedMarkdown().contains(" M src/Main.java"));

        // updateUI() on theme changes must not trigger gitService.getStatus()
        SwingUtilities.invokeAndWait(panel::updateUI);
        verify(gitService, never()).getStatus();

        when(gitService.getStatus())
                .thenAnswer(
                        inv -> {
                            if (SwingUtilities.isEventDispatchThread()) {
                                calledOnEdt.set(true);
                            }
                            return "";
                        });
        panel.onProjectChanged(new ProjectChangedEvent(Path.of("/tmp/other")));
        awaitRefresh(panel);

        assertTrue(panel.getDisplayArea().getText().contains("No files changed"));
        assertFalse(calledOnEdt.get(), "gitService.getStatus() must never be called on the EDT");
    }

    @Test
    void testStaleRefreshWorkerDiscarded() throws Exception {
        PlanService planService = mock(PlanService.class);
        GitService gitService = mock(GitService.class);

        ImplementationPlan plan =
                new ImplementationPlan(
                        "Test Plan",
                        "Test Goal",
                        List.of("Req 1"),
                        List.of(new ImplementationPlan.TechStep("Step 0", false)),
                        false,
                        null,
                        null,
                        null,
                        List.of());
        when(planService.getCurrentPlan()).thenReturn(plan);
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

        // Trigger a newer refresh while the first worker is still running
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
        View view = factory.create(elem);
        if (view instanceof InlineView) {
            return view.getMinimumSpan(View.X_AXIS) > 0;
        }
        for (int i = 0; i < elem.getElementCount(); i++) {
            if (hasPositiveInlineMinSpan(elem.getElement(i), factory)) {
                return true;
            }
        }
        return false;
    }
}
