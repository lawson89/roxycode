package org.roxycode.app.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.events.FileChangedEvent;
import org.roxycode.app.events.TokenUsageEvent;
import org.roxycode.app.ui.SwingTestUtils;

class ImplementationPlanTest {

    private static final String LF = "\n";

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
                "## Technical Steps" + LF + "- [x] 0. ~~Step 0~~" + LF + "- [ ] 1. Step 1");
        assertFalse(withoutLog.contains("## Handoff Log"));

        String withLog = plan.toMarkdown(true);
        SwingTestUtils.assertContainsAll(withLog, "## Handoff Log", "| 2026-04-06 12:05:30 | Architect | Created plan ",
                "| verified details |");

        ImplementationPlan withCommit = plan
                .withCommitMessage("feat: add branch diff" + LF + LF + "- Add getBranchDiff()");
        assertEquals("feat: add branch diff" + LF + LF + "- Add getBranchDiff()", withCommit.commitMessage());
        SwingTestUtils.assertContainsAll(withCommit.toMarkdown(false), "## Proposed Commit Message",
                "feat: add branch diff", "- Add getBranchDiff()");
        SwingTestUtils.assertContainsAll(withCommit.toArchivedMarkdown("id-1", "roxy", "/roxy"),
                "## Proposed Commit Message", "feat: add branch diff");

        String promptContext = plan.formatPromptContextMarkdown();
        SwingTestUtils.assertContainsAll(promptContext, "**Title:** Feature Plan", "**Goal:** Deliver feature",
                "### Requirements" + LF + "- Req A",
                "### Technical Steps" + LF + "- [x] 0. ~~Step 0~~" + LF + "- [ ] 1. Step 1",
                "### Handoff Log" + LF + "- [2026-04-06 12:05:30] Architect: Created plan | verified" + LF + "details");
        SwingTestUtils.assertContainsNone(promptContext, "### Changes", "## Approvals", "## Token Usage & Cost");

        ImplementationPlan planWithChanges = plan
                .withRecordedFileChange("src/Feature.java", FileChangedEvent.ChangeType.CREATED)
                .withRecordedFileChange("src/App.java", FileChangedEvent.ChangeType.MODIFIED);
        String promptWithChanges = planWithChanges.formatPromptContextMarkdown();
        SwingTestUtils.assertContainsAll(promptWithChanges,
                "### Changes" + LF + "CREATED src/Feature.java" + LF + "MODIFIED src/App.java");
        assertTrue(promptWithChanges.indexOf("### Changes") < promptWithChanges.indexOf("### Handoff Log"));
    }

    @Test
    void testToArchivedMarkdownAndFileChangeRecordingStateTransitions() {
        ImplementationPlan emptyPlan = new ImplementationPlan("Archive Feature", "Save completed plans",
                List.of("Store markdown"), List.of(new ImplementationPlan.TechStep("Implement archiving", true)), true,
                "2025-05-14T14:10:00Z", "alice", "bob", "2025-05-14T14:32:10Z",
                List.of(new ImplementationPlan.LogEntry("2025-05-14T14:32:10Z", "bob",
                        "REVIEW -> PLAN (User Approved): Complete")));

        assertEquals(ImplementationPlan.NO_FILES_CHANGED, emptyPlan.formatChangesText());
        assertTrue(emptyPlan.toArchivedMarkdown("20250514-143210-archive-feature", "roxycode", "/path/to/roxycode")
                .contains("## Changes" + LF + "No files changed" + LF));

        ImplementationPlan plan = emptyPlan
                .withRecordedTokenUsage(
                        new TokenUsageEvent("task:default", "gemini-3.5-flash", 1_048_576, 40_000, 30_000, 10_000,
                                1_500, 500, 42_000, WorkflowPhase.CODE, 0.0075, 0.00225, 0.005625, 0.001875, 0.01725),
                        WorkflowPhase.CODE)
                .withRecordedFileChange("src/NewFile.java", FileChangedEvent.ChangeType.CREATED)
                .withRecordedFileChange("src/NewFile.java", FileChangedEvent.ChangeType.MODIFIED)
                .withRecordedFileChange("src/App.java", FileChangedEvent.ChangeType.MODIFIED)
                .withRecordedFileChange("src/Temp.java", FileChangedEvent.ChangeType.CREATED)
                .withRecordedFileChange("src/Temp.java", FileChangedEvent.ChangeType.DELETED)
                .withRecordedFileChange("   ", FileChangedEvent.ChangeType.MODIFIED);

        assertEquals("CREATED src/NewFile.java" + LF + "MODIFIED src/App.java" + LF + "DELETED src/Temp.java",
                plan.formatChangesText());

        String archived = plan.toArchivedMarkdown("20250514-143210-archive-feature", "roxycode", "/path/to/roxycode");

        assertTrue(archived.startsWith("---" + LF));
        SwingTestUtils.assertContainsAll(archived, "id: ", "20250514-143210-archive-feature", "project: ", "roxycode",
                "projectPath: ", "/path/to/roxycode", "title: ", "Archive Feature", "userApproved: true",
                "approvedBy: ", "alice", "approvalTimestamp: ", "2025-05-14T14:10:00Z", "completedBy: ", "bob",
                "completedOn: ", "2025-05-14T14:32:10Z", "# Implementation Plan: Archive Feature", "## Approvals",
                "- **Move to Code:** Approved by alice on 2025-05-14T14:10:00Z",
                "- **Final Acceptance:** Accepted by bob on 2025-05-14T14:32:10Z", "## Token Usage & Cost",
                "### Cost by Token Category", "| Uncached Input | 10.0k | 25% | $0.0075 |",
                "| Cached Input | 30.0k | 75% hit | $0.0023 |",
                "| **Input Subtotal** | **40.0k** | **100% in** | **$0.0098** |", "| Output | 1.5k | 75% | $0.0056 |",
                "| Thinking | 500 | 25% | $0.0019 |",
                "| **Output + Thinking Subtotal** | **2.0k** | **100% out** | **$0.0075** |",
                "| **Total (1 calls)** | **42.0k (42000)** | — | **$0.02** |", "### Cost by Workflow Phase",
                "| Plan | 0 | 0 | $0.00 |", "| Code | 1 | 42.0k | $0.02 |", "| Review | 0 | 0 | $0.00 |",
                "totalTokens: 42000", "cachedTokens: 30000", "cacheHitPercent: 75", "uncachedInputCostUsd: ", "0.0075",
                "cachedInputCostUsd: ", "0.0023", "outputCostUsd: ", "0.0056", "thoughtsCostUsd: ", "0.0019",
                "planCostUsd: ", "0.0000", "codeCostUsd: ", "0.0173", "reviewCostUsd: ", "0.0000", "estimatedCostUsd: ",
                "0.0173", "## Handoff Log", "REVIEW -> PLAN (User Approved): Complete", "## Changes" + LF,
                "CREATED src/NewFile.java", "MODIFIED src/App.java", "DELETED src/Temp.java");
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
                List.of(new ImplementationPlan.LogEntry("t0", "u0", "prev log")))
                .withRecordedFileChange("src/First.java", FileChangedEvent.ChangeType.CREATED);

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
        assertEquals(List.of(new ImplementationPlan.FileChange("src/First.java", FileChangedEvent.ChangeType.CREATED)),
                normalized.fileChanges());

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

        ImplementationPlan afterCompilePass = normalized
                .withVerificationStepCompleted("  code compiles successfully.! ", true);
        assertNotSame(normalized, afterCompilePass);
        assertTrue(afterCompilePass.technicalSteps().get(2).completed());
        assertSame(afterCompilePass,
                afterCompilePass.withVerificationStepCompleted(ImplementationPlan.STEP_COMPILE, true));
        assertSame(afterCompilePass, afterCompilePass.withVerificationStepCompleted("   ", true));
        assertSame(afterCompilePass, afterCompilePass.withVerificationStepCompleted("Non-existent step", true));

        ImplementationPlan afterCompileReset = afterCompilePass
                .withVerificationStepCompleted(ImplementationPlan.STEP_COMPILE, false);
        assertFalse(afterCompileReset.technicalSteps().get(2).completed());
    }
}
