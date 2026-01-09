package org.roxycode.core.plans;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.core.RoxyProjectService;
import org.roxycode.core.tools.service.FileSystemService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
public class PlanServiceTest {

    @TempDir
    Path tempDir;

    @Inject
    PlanService planService;

    @Inject
    RoxyProjectService projectService;

    @Inject
    FileSystemService fileSystemService;

    private Path originalRoot;

    @BeforeEach
    void setUp() throws IOException {
        originalRoot = projectService.getProjectRoot();
        projectService.changeProjectRoot(tempDir);
    }

    @AfterEach
    void tearDown() {
        if (originalRoot != null) {
            projectService.changeProjectRoot(originalRoot);
        }
    }

    @Test
    void testCreateAndLoadPlan() throws IOException {
        String name = "test_plan";
        String goal = "Test Goal";
        List<String> changes = List.of("Change 1", "Change 2");
        List<String> steps = List.of("Step 1", "Step 2");

        planService.createPlan(name, goal, changes, steps);

        Plan loaded = planService.loadPlan(name);
        assertEquals(name, loaded.getName());
        assertEquals(goal, loaded.getGoal());
        assertEquals(changes, loaded.getProposedChanges());
        assertEquals(steps.size(), loaded.getImplementationSteps().size());
        assertEquals(PlanStatus.AVAILABLE, loaded.getStatus());
    }

    @Test
    void testUpdateSections() throws IOException {
        String name = "update_test";
        planService.createPlan(name, "Goal", List.of("Change"), List.of("Step"));

        planService.updateGoal(name, "New Goal");
        planService.updateProposedChanges(name, List.of("New Change"));
        planService.updateImplementationSteps(name, List.of("- [ ] New Step"));
        planService.updateImplementationProgress(name, List.of("- [x] Done"));

        Plan loaded = planService.loadPlan(name);
        assertEquals("New Goal", loaded.getGoal());
        assertEquals(List.of("New Change"), loaded.getProposedChanges());
        assertEquals(List.of("- [ ] New Step"), loaded.getImplementationSteps());
        assertEquals(List.of("- [x] Done"), loaded.getImplementationProgress());
    }

    @Test
    void testMovePlan() throws IOException {
        String name = "move_test";
        planService.createPlan(name, "Goal", List.of(), List.of());

        planService.movePlan(name, PlanStatus.WORKING);
        Plan loaded = planService.loadPlan(name);
        assertEquals(PlanStatus.WORKING, loaded.getStatus());

        List<String> workingPlans = planService.listPlans(PlanStatus.WORKING);
        assertTrue(workingPlans.contains(name));

        List<String> availablePlans = planService.listPlans(PlanStatus.AVAILABLE);
        assertFalse(availablePlans.contains(name));
    }
}
