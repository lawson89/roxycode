package org.roxycode.app.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImplementationPlanTest {
    @Test
    void testImplementationPlan() {
        ImplementationPlan.TechStep step = new ImplementationPlan.TechStep("Step 1", false);
        ImplementationPlan plan =
                new ImplementationPlan(
                        "Title",
                        "Goal",
                        List.of("Req 1"),
                        List.of(step),
                        false,
                        null,
                        null,
                        null,
                        null);
        assertEquals("Title", plan.title());
        assertEquals("Goal", plan.goal());
        assertEquals(1, plan.requirements().size());
        assertEquals(1, plan.technicalSteps().size());
        assertFalse(plan.technicalSteps().get(0).completed());
        assertNotNull(plan.handoffLog());
        assertTrue(plan.handoffLog().isEmpty());
    }

    @Test
    void testImmutabilityAndSafety() {
        ImplementationPlan.LogEntry handoffEntry =
                new ImplementationPlan.LogEntry("now", "user", "Handoff 1");

        ImplementationPlan plan =
                new ImplementationPlan(
                        "Title",
                        "Goal",
                        null,
                        null,
                        false,
                        null,
                        null,
                        null,
                        List.of(handoffEntry));
        assertNotNull(plan.requirements());
        assertNotNull(plan.technicalSteps());
        assertEquals(1, plan.handoffLog().size());

        // Ensure immutability
        ImplementationPlan.LogEntry failEntry =
                new ImplementationPlan.LogEntry("now", "user", "fail");
        assertThrows(UnsupportedOperationException.class, () -> plan.handoffLog().add(failEntry));
    }

    @Test
    void testWithCopyHelperMethodsPreserveFieldsAndImmutability() {
        ImplementationPlan base =
                new ImplementationPlan(
                        "Title",
                        "Goal",
                        List.of("Req 1"),
                        List.of(new ImplementationPlan.TechStep("Step 1", false)),
                        false,
                        null,
                        null,
                        null,
                        List.of(new ImplementationPlan.LogEntry("t0", "u0", "init")));

        List<ImplementationPlan.TechStep> mutableSteps = new ArrayList<>();
        mutableSteps.add(new ImplementationPlan.TechStep("Step 1", true));
        mutableSteps.add(new ImplementationPlan.TechStep("Step 2", false));

        ImplementationPlan withSteps = base.withTechnicalSteps(mutableSteps);
        mutableSteps.clear(); // verify defensive copy
        assertEquals("Title", withSteps.title());
        assertEquals("Goal", withSteps.goal());
        assertEquals(List.of("Req 1"), withSteps.requirements());
        assertEquals(2, withSteps.technicalSteps().size());
        assertTrue(withSteps.technicalSteps().get(0).completed());
        assertFalse(withSteps.userApproved());
        assertEquals(1, withSteps.handoffLog().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> withSteps.technicalSteps().add(new ImplementationPlan.TechStep("X", false)));

        ImplementationPlan approved = withSteps.withApproval("alice", "2026-01-01T00:00:00Z");
        assertTrue(approved.userApproved());
        assertEquals("alice", approved.approvedBy());
        assertEquals("2026-01-01T00:00:00Z", approved.approvalTimestamp());
        assertEquals("Title", approved.title());
        assertEquals(2, approved.technicalSteps().size());
        assertNull(approved.completedOn());

        ImplementationPlan withLogEntry =
                approved.withAddedHandoffLogEntry("t1", "bob", "Reviewed changes");
        assertEquals(2, withLogEntry.handoffLog().size());
        assertEquals("bob", withLogEntry.handoffLog().get(1).user());
        assertEquals("Reviewed changes", withLogEntry.handoffLog().get(1).message());
        assertTrue(withLogEntry.userApproved());
        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        withLogEntry
                                .handoffLog()
                                .add(new ImplementationPlan.LogEntry("t2", "u2", "m2")));

        List<ImplementationPlan.LogEntry> customLog = new ArrayList<>();
        customLog.add(new ImplementationPlan.LogEntry("t9", "sys", "Replaced"));
        ImplementationPlan replacedLog = withLogEntry.withHandoffLog(customLog);
        customLog.clear();
        assertEquals(1, replacedLog.handoffLog().size());
        assertEquals("Replaced", replacedLog.handoffLog().get(0).message());
        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        replacedLog
                                .handoffLog()
                                .add(new ImplementationPlan.LogEntry("t2", "u2", "m2")));

        ImplementationPlan completed = replacedLog.withCompletedOn("2026-01-02T00:00:00Z");
        assertEquals("2026-01-02T00:00:00Z", completed.completedOn());
        assertTrue(completed.userApproved());
        assertEquals("alice", completed.approvedBy());
        assertEquals(2, completed.technicalSteps().size());
        assertEquals(1, completed.handoffLog().size());
    }
}
