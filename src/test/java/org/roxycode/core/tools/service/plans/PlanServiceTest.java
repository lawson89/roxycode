package org.roxycode.core.tools.service.plans;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.roxycode.core.RoxyProjectService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanServiceTest {

    private RoxyProjectService roxyProjectService;
    private PlanService planService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        roxyProjectService = Mockito.mock(RoxyProjectService.class);
        when(roxyProjectService.getRoxyWorkingDir()).thenReturn(tempDir);
        planService = new PlanService(roxyProjectService);
    }

    @Test
    void testCreatePlan() throws IOException {
        String name = "test-plan";
        String goal = "The goal of the plan";
        List<String> changes = Arrays.asList("Change 1", "Change 2");
        List<String> steps = Arrays.asList("Step 1", "Step 2");

        planService.createPlan(name, goal, changes, steps);

        Path planPath = tempDir.resolve("plans").resolve("available").resolve(name + ".md");
        assertTrue(Files.exists(planPath), "Plan file should be created in 'available' folder");

        Plan loaded = planService.loadPlan(name);
        assertEquals(name, loaded.getName());
        assertEquals(goal, loaded.getGoal());
        assertEquals(changes, loaded.getProposedChanges());
        assertEquals(PlanStatus.AVAILABLE, loaded.getStatus());
        
        List<Plan.TaskItem> expectedSteps = Arrays.asList(
            new Plan.TaskItem("Step 1", false),
            new Plan.TaskItem("Step 2", false)
        );
        assertEquals(expectedSteps, loaded.getImplementationSteps());
    }

    @Test
    void testUpdateImplementationSteps() throws IOException {
        String name = "steps-plan";
        planService.createPlan(name, "Goal", null, null);

        List<String> newSteps = Arrays.asList("New Step");
        planService.updateImplementationSteps(name, newSteps);

        Plan loaded = planService.loadPlan(name);
        assertEquals(List.of(new Plan.TaskItem("New Step", false)), loaded.getImplementationSteps());
    }

    @Test
    void testUpdateImplementationProgress() throws IOException {
        String name = "progress-plan";
        planService.createPlan(name, "Goal", null, null);

        List<String> progress = Arrays.asList("[x] Done Step", "[ ] Pending Step", "Implicitly Done");
        planService.updateImplementationProgress(name, progress);

        Plan loaded = planService.loadPlan(name);
        List<Plan.TaskItem> expected = Arrays.asList(
            new Plan.TaskItem("Done Step", true),
            new Plan.TaskItem("Pending Step", false),
            new Plan.TaskItem("Implicitly Done", true)
        );
        assertEquals(expected, loaded.getImplementationProgress());
    }

    @Test
    void testAddProgressStep() throws IOException {
        String name = "add-progress-plan";
        planService.createPlan(name, "Goal", null, null);

        planService.addProgressStep(name, "Step A", true);
        planService.addProgressStep(name, "Step B", false);

        Plan loaded = planService.loadPlan(name);
        List<Plan.TaskItem> expected = Arrays.asList(
            new Plan.TaskItem("Step A", true),
            new Plan.TaskItem("Step B", false)
        );
        assertEquals(expected, loaded.getImplementationProgress());
    }

    @Test
    void testCompleteStep() throws IOException {
        String name = "complete-step-plan";
        planService.createPlan(name, "Goal", null, null);
        planService.addProgressStep(name, "Step 1", false);

        planService.completeStep(name, "Step 1");

        Plan loaded = planService.loadPlan(name);
        assertEquals(1, loaded.getImplementationProgress().size());
        assertTrue(loaded.getImplementationProgress().get(0).completed());
        assertEquals("Step 1", loaded.getImplementationProgress().get(0).text());
        
        // Complete a non-existent step
        planService.completeStep(name, "Step 2");
        loaded = planService.loadPlan(name);
        assertEquals(2, loaded.getImplementationProgress().size());
        assertTrue(loaded.getImplementationProgress().get(1).completed());
        assertEquals("Step 2", loaded.getImplementationProgress().get(1).text());
    }

    @Test
    void testLoadPlanWithMarkers() throws IOException {
        String name = "markers-plan";
        Path planPath = tempDir.resolve("plans").resolve("available").resolve(name + ".md");
        Files.createDirectories(planPath.getParent());
        String content = "# markers-plan\n\n## Implementation Steps\n- [ ] Unchecked\n- [x] Checked lowercase\n- [X] Checked uppercase\n- Plain bullet\n";
        Files.writeString(planPath, content);

        Plan loaded = planService.loadPlan(name);
        List<Plan.TaskItem> expected = Arrays.asList(
            new Plan.TaskItem("Unchecked", false),
            new Plan.TaskItem("Checked lowercase", true),
            new Plan.TaskItem("Checked uppercase", true),
            new Plan.TaskItem("Plain bullet", false)
        );
        assertEquals(expected, loaded.getImplementationSteps());
    }

    @Test
    void testMovePlan_ValidTransitions() throws IOException {
        String name = "move-plan";
        planService.createPlan(name, "Goal", null, null);

        // Available -> In Progress
        planService.movePlan(name, "in_progress");
        assertEquals(PlanStatus.IN_PROGRESS, planService.loadPlan(name).getStatus());

        // In Progress -> Available
        planService.movePlan(name, "available");
        assertEquals(PlanStatus.AVAILABLE, planService.loadPlan(name).getStatus());

        // Available -> In Progress
        planService.movePlan(name, "in_progress");

        // In Progress -> Complete
        planService.movePlan(name, "complete");
        assertEquals(PlanStatus.COMPLETE, planService.loadPlan(name).getStatus());
    }

    @Test
    void testMovePlan_InvalidTransition_ThrowsException() throws IOException {
        String name = "invalid-move-plan";
        planService.createPlan(name, "Goal", null, null);

        // Available -> Complete is NOT allowed
        assertThrows(IOException.class, () -> {
            planService.movePlan(name, "complete");
        });
    }

    @Test
    void testDeletePlan() throws IOException {
        String name = "delete-plan";
        planService.createPlan(name, "Goal", null, null);

        assertTrue(planService.planExists(name));
        planService.deletePlan(name);
        assertFalse(planService.planExists(name));
    }

    @Test
    void testListPlans() throws IOException {
        planService.createPlan("plan1", "Goal 1", null, null);
        planService.createPlan("plan2", "Goal 2", null, null);
        planService.createPlan("plan3", "Goal 3", null, null);

        planService.movePlan("plan2", "in_progress");
        planService.movePlan("plan3", "in_progress");
        planService.movePlan("plan3", "complete");

        List<String> available = planService.listAvailablePlans();
        List<String> inProgress = planService.listInProgressPlans();
        List<String> complete = planService.listCompletePlans();

        assertTrue(available.contains("plan1"));
        assertEquals(1, available.size());
        
        assertTrue(inProgress.contains("plan2"));
        assertEquals(1, inProgress.size());

        assertTrue(complete.contains("plan3"));
        assertEquals(1, complete.size());
    }

    @Test
    void testAgentContext() throws IOException {
        String name = "context-plan";
        planService.createPlan(name, "Goal", null, null);
        
        Plan plan = planService.loadPlan(name);
        assertEquals("", plan.getAgentContext());
        
        String context = "This is some context";
        planService.updateAgentContext(name, context);
        
        Plan loaded = planService.loadPlan(name);
        assertEquals(context, loaded.getAgentContext());
    }
    @Test
    void testSetCurrentPlan_DelegatesToRoxyProjectService() {
        String planName = "test-plan";
        planService.setCurrentPlan(planName);
        verify(roxyProjectService).setCurrentPlan(planName);
    }

}
