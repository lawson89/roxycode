# Goal
Implement a comprehensive suite of unit tests for PlanService to ensure correct management of development plans, including creation, modification, status transitions, and deletion, while enforcing state rules.

# Proposed Changes
- Create src/test/java/org/roxycode/core/tools/service/plans/PlanServiceTest.java.
- Use @ExtendWith(MockitoExtension.class) and @Mock for RoxyProjectService.
- Use @TempDir to provide a temporary directory for plan storage.

# Implementation Steps
- [ ] Setup PlanService with a mocked RoxyProjectService and a temporary directory.
- [ ] Test createPlan: successful creation and file persistence in 'available' folder.
- [ ] Test createPlan: throw exception if plan already exists.
- [ ] Test updateGoal: successful update for AVAILABLE and IN_PROGRESS plans.
- [ ] Test updateGoal: throw exception for COMPLETE plans.
- [ ] Test updateProposedChanges: successful update.
- [ ] Test updateImplementationSteps: successful update.
- [ ] Test updateImplementationProgress: successful update.
- [ ] Test movePlan: valid transition AVAILABLE -> IN_PROGRESS.
- [ ] Test movePlan: valid transition IN_PROGRESS -> AVAILABLE.
- [ ] Test movePlan: valid transition IN_PROGRESS -> COMPLETE.
- [ ] Test movePlan: invalid transition AVAILABLE -> COMPLETE.
- [ ] Test movePlan: invalid transition COMPLETE -> IN_PROGRESS.
- [ ] Test deletePlan: successful deletion of AVAILABLE plan.
- [ ] Test deletePlan: throw exception for IN_PROGRESS or COMPLETE plan.
- [ ] Test listAvailablePlans, listInProgressPlans, listCompletePlans.
- [ ] Test loadPlan: verify markdown parsing and status determination.
- [ ] Test planExists.
- [ ] Test getCurrentPlan and setCurrentPlan delegation to RoxyProjectService.

# Implementation Progress
