package org.roxycode.app.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ui.SwingTestUtils;

class ImplementationPlanTest {

    private static ImplementationPlan createPlan(List<String> reqs, List<ImplementationPlan.TechStep> steps,
            List<ImplementationPlan.LogEntry> logs) {
        return new ImplementationPlan("Title", "Goal", reqs, steps, false, null, null, null, null, logs);
    }

    @Test
    void testImplementationPlan() {
        ImplementationPlan plan = createPlan(List.of("Req 1"),
                List.of(new ImplementationPlan.TechStep("Step 1", false)), null);
        assertEquals("Title", plan.title());
        assertEquals("Goal", plan.goal());
        assertEquals(1, plan.requirements().size());
        assertEquals(1, plan.technicalSteps().size());
        assertFalse(plan.technicalSteps().get(0).completed());
        assertNull(plan.completedBy());
        assertNull(plan.completedOn());
        assertNotNull(plan.handoffLog());
        assertTrue(plan.handoffLog().isEmpty());
    }

    @Test
    void testImmutabilityAndSafety() {
        ImplementationPlan plan = createPlan(null, null,
                List.of(new ImplementationPlan.LogEntry("now", "user", "Handoff 1")));
        assertNotNull(plan.requirements());
        assertNotNull(plan.technicalSteps());
        assertEquals(1, plan.handoffLog().size());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.handoffLog().add(new ImplementationPlan.LogEntry("now", "user", "fail")));
    }

    @Test
    void testWithCopyHelperMethodsPreserveFieldsAndImmutability() {
        ImplementationPlan base = createPlan(List.of("Req 1"),
                List.of(new ImplementationPlan.TechStep("Step 1", false)),
                List.of(new ImplementationPlan.LogEntry("t0", "u0", "init")));

        List<ImplementationPlan.TechStep> mutableSteps = new ArrayList<>(List
                .of(new ImplementationPlan.TechStep("Step 1", true), new ImplementationPlan.TechStep("Step 2", false)));

        ImplementationPlan withSteps = base.withTechnicalSteps(mutableSteps);
        mutableSteps.clear();
        assertEquals("Title", withSteps.title());
        assertEquals("Goal", withSteps.goal());
        assertEquals(List.of("Req 1"), withSteps.requirements());
        assertEquals(2, withSteps.technicalSteps().size());
        assertTrue(withSteps.technicalSteps().get(0).completed());
        assertFalse(withSteps.userApproved());
        assertEquals(1, withSteps.handoffLog().size());
        assertThrows(UnsupportedOperationException.class,
                () -> withSteps.technicalSteps().add(new ImplementationPlan.TechStep("X", false)));

        ImplementationPlan approved = withSteps.withApproval("alice", "2026-01-01T00:00:00Z");
        assertTrue(approved.userApproved());
        assertEquals("alice", approved.approvedBy());
        assertEquals("2026-01-01T00:00:00Z", approved.approvalTimestamp());
        assertEquals("Title", approved.title());
        assertEquals(2, approved.technicalSteps().size());
        assertNull(approved.completedBy());
        assertNull(approved.completedOn());

        ImplementationPlan withLogEntry = approved.withAddedHandoffLogEntry("t1", "bob", "Reviewed changes");
        assertEquals(2, withLogEntry.handoffLog().size());
        assertEquals("bob", withLogEntry.handoffLog().get(1).user());
        assertEquals("Reviewed changes", withLogEntry.handoffLog().get(1).message());
        assertTrue(withLogEntry.userApproved());
        assertThrows(UnsupportedOperationException.class,
                () -> withLogEntry.handoffLog().add(new ImplementationPlan.LogEntry("t2", "u2", "m2")));

        List<ImplementationPlan.LogEntry> customLog = new ArrayList<>(
                List.of(new ImplementationPlan.LogEntry("t9", "sys", "Replaced")));
        ImplementationPlan replacedLog = withLogEntry.withHandoffLog(customLog);
        customLog.clear();
        assertEquals(1, replacedLog.handoffLog().size());
        assertEquals("Replaced", replacedLog.handoffLog().get(0).message());
        assertThrows(UnsupportedOperationException.class,
                () -> replacedLog.handoffLog().add(new ImplementationPlan.LogEntry("t2", "u2", "m2")));

        ImplementationPlan completedWithUser = replacedLog.withCompletion("charlie", "2026-01-02T00:00:00Z");
        assertEquals("charlie", completedWithUser.completedBy());
        assertEquals("2026-01-02T00:00:00Z", completedWithUser.completedOn());
        assertTrue(completedWithUser.userApproved());
        assertEquals("alice", completedWithUser.approvedBy());
        assertEquals(2, completedWithUser.technicalSteps().size());
        assertEquals(1, completedWithUser.handoffLog().size());
    }

    @Test
    void testToMarkdownWithAndWithoutHandoffLogAndApprovals() {
        ImplementationPlan unapproved = createPlan(List.of("Req A"),
                List.of(new ImplementationPlan.TechStep("Step 0", false)), List.of());
        assertFalse(unapproved.toMarkdown(true).contains("## Approvals"));

        ImplementationPlan plan = new ImplementationPlan("Feature Plan", "Deliver feature", List.of("Req A"),
                List.of(new ImplementationPlan.TechStep("Step 0", true),
                        new ImplementationPlan.TechStep("Step 1", false)),
                true, "2026-04-06T12:00:00Z", "alice", "bob", "2026-04-06T12:30:00Z",
                List.of(new ImplementationPlan.LogEntry("2026-04-06T12:05:30.123", "Architect",
                        "Created plan | verified\ndetails")));
        String withoutLog = plan.toMarkdown(false);
        SwingTestUtils.assertContainsAll(withoutLog, "# Implementation Plan: Feature Plan", "**Goal:** Deliver feature",
                "## Approvals", "- **Move to Code:** Approved by alice on 2026-04-06T12:00:00Z",
                "- **Final Acceptance:** Accepted by bob on 2026-04-06T12:30:00Z", "## Requirements\n- Req A",
                "## Technical Steps\n- [x] 0. Step 0\n- [ ] 1. Step 1");
        assertFalse(withoutLog.contains("## Handoff Log"));

        String withLog = plan.toMarkdown(true);
        SwingTestUtils.assertContainsAll(withLog, "## Handoff Log", "| 2026-04-06 12:05:30 | Architect | Created plan ",
                "| verified details |");
    }

    @Test
    void testToArchivedMarkdownIncludesFrontmatterApprovalsLogAndChanges() {
        ImplementationPlan plan = new ImplementationPlan("Archive Feature", "Save completed plans",
                List.of("Store markdown"), List.of(new ImplementationPlan.TechStep("Implement archiving", true)), true,
                "2025-05-14T14:10:00Z", "alice", "bob", "2025-05-14T14:32:10Z",
                List.of(new ImplementationPlan.LogEntry("2025-05-14T14:32:10Z", "bob",
                        "REVIEW -> PLAN (User Approved): Complete")));

        String archived = plan.toArchivedMarkdown("20250514-143210-archive-feature", "roxycode", "/path/to/roxycode",
                " M src/App.java\n");

        assertTrue(archived.startsWith("---\n"));
        SwingTestUtils.assertContainsAll(archived, "id: ", "20250514-143210-archive-feature", "project: ", "roxycode",
                "projectPath: ", "/path/to/roxycode", "title: ", "Archive Feature", "userApproved: true",
                "approvedBy: ", "alice", "approvalTimestamp: ", "2025-05-14T14:10:00Z", "completedBy: ", "bob",
                "completedOn: ", "2025-05-14T14:32:10Z", "# Implementation Plan: Archive Feature", "## Approvals",
                "- **Move to Code:** Approved by alice on 2025-05-14T14:10:00Z",
                "- **Final Acceptance:** Accepted by bob on 2025-05-14T14:32:10Z", "## Handoff Log",
                "REVIEW -> PLAN (User Approved): Complete", "## Changes\n", "M src/App.java");

        assertTrue(plan.toArchivedMarkdown("20250514-143210-archive-feature", "roxycode", "/path/to/roxycode", "")
                .contains("## Changes\nNo files changed\n"));

        assertEquals(ImplementationPlan.NO_FILES_CHANGED, ImplementationPlan.formatChangesText(null));
        assertEquals(ImplementationPlan.NO_FILES_CHANGED, ImplementationPlan.formatChangesText("   "));
        assertEquals(" M src/App.java", ImplementationPlan.formatChangesText(" M src/App.java\n  "));
        assertEquals("", ImplementationPlan.escapeFrontmatterValue(null));
        assertEquals("Quote " + (char) 92 + '"' + " and newline",
                ImplementationPlan.escapeFrontmatterValue("Quote " + '"' + "\nand\r\nnewline"));
    }
}
