package org.roxycode.app.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ui.SwingTestUtils;
import org.roxycode.app.util.FrontmatterUtils;

class ImplementationPlanTest {

    private static final String LF = String.valueOf((char) 10);
    private static final String CRLF = String.valueOf((char) 13) + (char) 10;

    private static ImplementationPlan createPlan(List<String> reqs, List<ImplementationPlan.TechStep> steps,
            List<ImplementationPlan.LogEntry> logs) {
        return new ImplementationPlan("Title", "Goal", reqs, steps, false, null, null, null, null, logs);
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
                        "Created plan | verified" + LF + "details")));
        String withoutLog = plan.toMarkdown(false);
        SwingTestUtils.assertContainsAll(withoutLog, "# Implementation Plan: Feature Plan", "**Goal:** Deliver feature",
                "## Approvals", "- **Move to Code:** Approved by alice on 2026-04-06T12:00:00Z",
                "- **Final Acceptance:** Accepted by bob on 2026-04-06T12:30:00Z", "## Requirements" + LF + "- Req A",
                "## Technical Steps" + LF + "- [x] 0. Step 0" + LF + "- [ ] 1. Step 1");
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
                " M src/App.java" + LF);

        assertTrue(archived.startsWith("---" + LF));
        SwingTestUtils.assertContainsAll(archived, "id: ", "20250514-143210-archive-feature", "project: ", "roxycode",
                "projectPath: ", "/path/to/roxycode", "title: ", "Archive Feature", "userApproved: true",
                "approvedBy: ", "alice", "approvalTimestamp: ", "2025-05-14T14:10:00Z", "completedBy: ", "bob",
                "completedOn: ", "2025-05-14T14:32:10Z", "# Implementation Plan: Archive Feature", "## Approvals",
                "- **Move to Code:** Approved by alice on 2025-05-14T14:10:00Z",
                "- **Final Acceptance:** Accepted by bob on 2025-05-14T14:32:10Z", "## Handoff Log",
                "REVIEW -> PLAN (User Approved): Complete", "## Changes" + LF, "M src/App.java");

        assertTrue(plan.toArchivedMarkdown("20250514-143210-archive-feature", "roxycode", "/path/to/roxycode", "")
                .contains("## Changes" + LF + "No files changed" + LF));

        assertEquals(ImplementationPlan.NO_FILES_CHANGED, ImplementationPlan.formatChangesText(null));
        assertEquals(ImplementationPlan.NO_FILES_CHANGED, ImplementationPlan.formatChangesText("   "));
        assertEquals(" M src/App.java", ImplementationPlan.formatChangesText(" M src/App.java" + LF + "  "));
        assertEquals("", FrontmatterUtils.escapeFrontmatterValue(null));
        assertEquals("Quote " + (char) 92 + '"' + " and newline",
                FrontmatterUtils.escapeFrontmatterValue("Quote " + '"' + LF + "and" + CRLF + "newline"));
        assertEquals("---" + LF + "---" + LF + LF, FrontmatterUtils.formatFrontmatter(null));
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("title", "Say " + '"' + "Hi" + '"');
        fm.put("approved", true);
        fm.put("count", 3);
        fm.put("optional", null);
        String expectedFm = FrontmatterUtils.formatFrontmatter(fm);
        SwingTestUtils.assertContainsAll(expectedFm, "title: ", "approved: true", "count: 3", "optional: ");
    }

    @Test
    void testStepNormalizationAndVerificationGateClassification() {
        assertEquals("", ImplementationPlan.normalizeStepDescription(null));
        assertEquals("", ImplementationPlan.normalizeStepDescription("   ..!:;  "));
        assertEquals("code compiles successfully",
                ImplementationPlan.normalizeStepDescription("  Code Compiles Successfully.!;:  "));

        assertTrue(ImplementationPlan.isCompileStep("Code compiles successfully"));
        assertTrue(ImplementationPlan.isCompileStep("  CODE COMPILES SUCCESSFULLY.! "));
        assertFalse(ImplementationPlan.isCompileStep("Pre-compile regex patterns"));

        assertTrue(ImplementationPlan.isUnitTestStep("Unit tests pass"));
        assertTrue(ImplementationPlan.isUnitTestStep("unit tests pass:"));
        assertFalse(ImplementationPlan.isUnitTestStep("Add unit tests for service"));

        assertTrue(ImplementationPlan.isCodeReviewStep("Automated code review passes"));
        assertTrue(ImplementationPlan.isCodeReviewStep("Automated Code Review Passes;"));
        assertFalse(ImplementationPlan.isCodeReviewStep("Update code review prompt"));

        assertTrue(ImplementationPlan.isMandatoryVerificationStep(ImplementationPlan.STEP_COMPILE));
        assertTrue(ImplementationPlan.isMandatoryVerificationStep(ImplementationPlan.STEP_UNIT_TESTS));
        assertTrue(ImplementationPlan.isMandatoryVerificationStep(ImplementationPlan.STEP_CODE_REVIEW));
        assertFalse(ImplementationPlan.isMandatoryVerificationStep("Implement feature"));
    }

    @Test
    void testWithNormalizedStepsFromCollectIncompleteAndFormatRemaining() {
        ImplementationPlan prev = createPlan(List.of("R1"),
                List.of(new ImplementationPlan.TechStep("Step 1", true),
                        new ImplementationPlan.TechStep("Step 2", false),
                        new ImplementationPlan.TechStep(ImplementationPlan.STEP_COMPILE, true)),
                List.of(new ImplementationPlan.LogEntry("t0", "u0", "prev log")));

        assertEquals(Set.of("step 1"), prev.collectCompletedNonVerificationSteps());

        ImplementationPlan nextRaw = createPlan(List.of("R1"),
                List.of(new ImplementationPlan.TechStep("Step 1.", false),
                        new ImplementationPlan.TechStep("Step 2a", false)),
                List.of());

        ImplementationPlan normalized = nextRaw.withNormalizedStepsFrom(prev);
        assertEquals(5, normalized.technicalSteps().size());
        assertTrue(normalized.technicalSteps().get(0).completed());
        assertFalse(normalized.technicalSteps().get(1).completed());
        assertEquals(ImplementationPlan.STEP_COMPILE, normalized.technicalSteps().get(2).description());
        assertFalse(normalized.technicalSteps().get(2).completed());
        assertEquals(ImplementationPlan.STEP_UNIT_TESTS, normalized.technicalSteps().get(3).description());
        assertEquals(ImplementationPlan.STEP_CODE_REVIEW, normalized.technicalSteps().get(4).description());
        assertEquals(1, normalized.handoffLog().size());

        assertEquals(List.of("1: Step 2a", "2: Code compiles successfully", "3: Unit tests pass"),
                normalized.collectIncompleteSteps(true));
        assertEquals(List.of("1: Step 2a", "2: Code compiles successfully", "3: Unit tests pass",
                "4: Automated code review passes"), normalized.collectIncompleteSteps(false));
        assertEquals(
                "Remaining incomplete steps: [1: Step 2a, 2: Code compiles successfully, 3: Unit tests pass, 4: Automated code review passes]",
                normalized.formatRemainingIncompleteSteps());

        ImplementationPlan allDone = normalized
                .withTechnicalSteps(List.of(new ImplementationPlan.TechStep("Step 1", true),
                        new ImplementationPlan.TechStep(ImplementationPlan.STEP_CODE_REVIEW, true)));
        assertTrue(allDone.collectIncompleteSteps(false).isEmpty());
        assertEquals("Remaining incomplete steps: none (all steps completed).",
                allDone.formatRemainingIncompleteSteps());
    }
}
