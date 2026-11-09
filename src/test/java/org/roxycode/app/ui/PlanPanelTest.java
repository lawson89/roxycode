package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.*;
import org.roxycode.app.model.ImplementationPlan;

class PlanPanelTest {

    @Test
    void testBuildPlanHtmlCopyMarkdownAndRefreshOnEvents() {
        assertTrue(PlanPanel.buildPlanHtml(null).contains("No implementation plan submitted yet."));

        ImplementationPlan plan = new ImplementationPlan("Test Plan", "Test Goal", List.of("Requirement 1"),
                List.of(new ImplementationPlan.TechStep("Step 0", false),
                        new ImplementationPlan.TechStep("Completed step", true)),
                true, "2026-04-06T12:00:00", "user", "reviewer", "2026-04-06T13:00:00",
                List.of(new ImplementationPlan.LogEntry("2026-04-06T12:00:00", "User", "Approved")))
                .withRecordedFileChange("src/Main.java", FileChangedEvent.ChangeType.MODIFIED);

        String html = PlanPanel.buildPlanHtml(plan);
        assertContainsAll(html, "<h1>Implementation Plan: Test Plan</h1>", "<h2>Approvals</h2>", "[ ] 0. Step 0",
                "<del>Completed step</del>", "<h2>Handoff Log</h2>", "<h2>Changes</h2>", "MODIFIED src/Main.java");

        PlanService planService = mock(PlanService.class);
        PlanPanel panel = new PlanPanel(planService);
        SwingTestUtils.flushEdt();
        assertFalse(panel.copyMarkdownButton.isEnabled());

        when(planService.getCurrentPlan()).thenReturn(plan);
        panel.onPlanUpdated(new PlanUpdatedEvent(plan));
        SwingTestUtils.flushEdt();

        assertTrue(panel.copyMarkdownButton.isEnabled());
        assertContainsAll(panel.displayArea.getText(), "MODIFIED src/Main.java");

        assertFalse(panel.copyCommitMessageButton.isVisible());
        SwingTestUtils.clickOnEdt(panel.copyMarkdownButton);
        assertEquals("Copied!", panel.copyMarkdownButton.getText());
        assertContainsAll(panel.lastCopiedMarkdown, "# Implementation Plan: Test Plan", "MODIFIED src/Main.java");

        String commitMsg = String.join(String.valueOf((char) 10), "feat: add commit copy", "",
                "- Support 1-click copy");
        ImplementationPlan planWithCommit = plan.withCommitMessage(commitMsg);
        when(planService.getCurrentPlan()).thenReturn(planWithCommit);
        panel.onPlanUpdated(new PlanUpdatedEvent(planWithCommit));
        SwingTestUtils.flushEdt();
        assertTrue(panel.copyCommitMessageButton.isVisible());
        assertTrue(panel.copyCommitMessageButton.isEnabled());
        SwingTestUtils.clickOnEdt(panel.copyCommitMessageButton);
        assertEquals("Copied!", panel.copyCommitMessageButton.getText());
        assertEquals(commitMsg, panel.lastCopiedCommitMessage);

        panel.onProjectChanged(new ProjectChangedEvent(Path.of("/tmp/other")));
        SwingTestUtils.flushEdt();
    }
}
