package org.roxycode.app.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ImplementationPlanTest {
  @Test
  void testImplementationPlan() {
    ImplementationPlan.TechStep step = new ImplementationPlan.TechStep("Step 1", false);
    ImplementationPlan plan =
        new ImplementationPlan(
            "Title", "Goal", List.of("Req 1"), List.of(step), false, null, null, null, null, null);
    assertEquals("Title", plan.title());
    assertEquals("Goal", plan.goal());
    assertEquals(1, plan.requirements().size());
    assertEquals(1, plan.technicalSteps().size());
    assertFalse(plan.technicalSteps().get(0).completed());
    assertNotNull(plan.codeReviewLog());
    assertNotNull(plan.handoffLog());
    assertTrue(plan.codeReviewLog().isEmpty());
    assertTrue(plan.handoffLog().isEmpty());
  }

  @Test
  void testImmutabilityAndSafety() {
    ImplementationPlan.LogEntry reviewEntry =
        new ImplementationPlan.LogEntry("now", "user", "Review 1");
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
            List.of(reviewEntry),
            List.of(handoffEntry));
    assertNotNull(plan.requirements());
    assertNotNull(plan.technicalSteps());
    assertEquals(1, plan.codeReviewLog().size());
    assertEquals(1, plan.handoffLog().size());

    // Ensure immutability
    ImplementationPlan.LogEntry failEntry = new ImplementationPlan.LogEntry("now", "user", "fail");
    assertThrows(UnsupportedOperationException.class, () -> plan.codeReviewLog().add(failEntry));
  }
}
