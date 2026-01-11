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
        // Steps in markdown are prefixed with "[ ] " if not already present
        assertEquals(Arrays.asList("- [ ] Step 1", "- [ ] Step 2"), loaded.getImplementationSteps());
    }

    @Test
    void testCreatePlan_AlreadyExists() throws IOException {
        String name = "existing-plan";
        planService.createPlan(name, "Goal", null, null);

        assertThrows(IOException.class, () -> {
            planService.createPlan(name, "New Goal", null, null);
        });
    }

    @Test
    void testUpdateGoal() throws IOException {
        String name = "update-goal-plan";
        planService.createPlan(name, "Old Goal", null, null);

        planService.updateGoal(name, "New Goal");

        Plan loaded = planService.loadPlan(name);
        assertEquals("New Goal", loaded.getGoal());
    }

    @Test
    void testUpdateGoal_CompletePlan_ThrowsException() throws IOException {
        String name = "complete-plan";
        planService.createPlan(name, "Goal", null, null);
        planService.movePlan(name, "in_progress");
        planService.movePlan(name, "complete");

        assertThrows(IOException.class, () -> {
            planService.updateGoal(name, "Modified Goal");
        });
    }

    @Test
    void testUpdateProposedChanges() throws IOException {
        String name = "changes-plan";
        planService.createPlan(name, "Goal", null, null);

        List<String> newChanges = Arrays.asList("New Change");
        planService.updateProposedChanges(name, newChanges);

        Plan loaded = planService.loadPlan(name);
        assertEquals(newChanges, loaded.getProposedChanges());
    }

    @Test
    void testUpdateImplementationSteps() throws IOException {
        String name = "steps-plan";
        planService.createPlan(name, "Goal", null, null);

        List<String> newSteps = Arrays.asList("New Step");
        planService.updateImplementationSteps(name, newSteps);

        Plan loaded = planService.loadPlan(name);
        assertEquals(Arrays.asList("- [ ] New Step"), loaded.getImplementationSteps());
    }

    @Test
    void testUpdateImplementationProgress() throws IOException {
        String name = "progress-plan";
        planService.createPlan(name, "Goal", null, null);

        List<String> progress = Arrays.asList("Done Step");
        planService.updateImplementationProgress(name, progress);

        Plan loaded = planService.loadPlan(name);
        assertEquals(Arrays.asList("- [x] Done Step"), loaded.getImplementationProgress());
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
    void testDeletePlan_InProgress_ThrowsException() throws IOException {
        String name = "delete-in-progress-plan";
        planService.createPlan(name, "Goal", null, null);
        planService.movePlan(name, "in_progress");

        assertThrows(IOException.class, () -> {
            planService.deletePlan(name);
        });
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
    void testGetPlanMarkdown() throws IOException {
        String name = "test-plan";
        Path planPath = tempDir.resolve("plans").resolve("available").resolve(name + ".md");
        Files.createDirectories(planPath.getParent());
        Files.writeString(planPath, "# Test Plan");

        String markdown = planService.getPlanMarkdown(name);
        assertEquals("# Test Plan", markdown);
    }
    @Test
    void testGetCurrentPlanMarkdown() throws IOException {
        String name = "current-plan";
        when(roxyProjectService.getCurrentPlan()).thenReturn(name);
        
        planService.createPlan(name, "Goal", null, null);
        planService.movePlan(name, "in_progress");
        
        String markdown = planService.getCurrentPlanMarkdown();
        assertNotNull(markdown);
        assertTrue(markdown.contains("Goal"));
    }

    void testGetCurrentPlan_DelegatesToRoxyProjectService() {
        when(roxyProjectService.getCurrentPlan()).thenReturn("current-plan");
        assertEquals("current-plan", planService.getCurrentPlan());
        verify(roxyProjectService).getCurrentPlan();
    }

    @Test
    void testSetCurrentPlan_DelegatesToRoxyProjectService() {
        planService.setCurrentPlan("new-plan");
        verify(roxyProjectService).setCurrentPlan("new-plan");
    }

    @Test
    void testLoadPlan() throws IOException {
        String name = "load-plan";
        String goal = "Load test goal";
        List<String> changes = List.of("Change 1", "Change 2");
        List<String> steps = List.of("Step 1", "Step 2");
        
        planService.createPlan(name, goal, changes, steps);
        
        Plan plan = planService.loadPlan(name);
        
        assertNotNull(plan);
        assertEquals(name, plan.getName());
        assertEquals(goal, plan.getGoal());
        assertEquals(changes, plan.getProposedChanges());
        assertEquals(List.of("- [ ] Step 1", "- [ ] Step 2"), plan.getImplementationSteps());
        assertEquals(PlanStatus.AVAILABLE, plan.getStatus());
        assertTrue(plan.getImplementationProgress().isEmpty());
    }

    @Test
    void testPlanExists() throws IOException {
        String name = "exists-test";
        assertFalse(planService.planExists(name));
        
        planService.createPlan(name, "Goal", null, null);
        assertTrue(planService.planExists(name));
        
        planService.deletePlan(name);
        assertFalse(planService.planExists(name));
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
    void testCreatePlanWithContext() throws IOException {
        String name = "context-plan-new";
        String goal = "The goal";
        String context = "Some context";
        planService.createPlan(name, goal, null, null, context);

        Plan loaded = planService.loadPlan(name);
        assertEquals(context, loaded.getAgentContext());
    }
}
