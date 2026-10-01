package org.roxycode.app.ai.services.plan;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PlanTest {

    @Test
    void testPlanCreation() {
        Plan.TaskItem task = new Plan.TaskItem("Task 1", Plan.TaskStatus.PENDING);
        Plan plan = new Plan(
            "Test Plan",
            "user1",
            "request1",
            "goal1",
            true,
            "2023-10-27T10:00:00Z",
            null,
            List.of("req1"),
            List.of("con1"),
            List.of("note1"),
            List.of(task),
            PlanStatus.PLAN
        );

        assertEquals("Test Plan", plan.name());
        assertEquals(PlanStatus.PLAN, plan.status());
        assertEquals("PLAN", plan.getStatusString());
        assertEquals(1, plan.tasks().size());
        assertEquals("[PENDING] Task 1", plan.tasks().get(0).toString());
    }

    @Test
    void testTaskStatusEnum() {
        assertEquals(Plan.TaskStatus.PENDING, Plan.TaskStatus.valueOf("PENDING"));
        assertEquals(Plan.TaskStatus.IN_PROGRESS, Plan.TaskStatus.valueOf("IN_PROGRESS"));
        assertEquals(Plan.TaskStatus.COMPLETED, Plan.TaskStatus.valueOf("COMPLETED"));
        assertEquals(Plan.TaskStatus.FAILED, Plan.TaskStatus.valueOf("FAILED"));
    }
}