# support_planning_plans_in_plan_mode

## Goal
Implement a "planning plan" mechanism to allow the agent to maintain state and track progress during the planning phase in PLAN mode, similar to how plans work in CODE mode.

## Proposed Changes
- Add PLANNING("planning") to PlanStatus enum.
- Update PlanService to support the PLANNING status (listing, moving, deleting).
- Modify GenAIService.buildSystemMessage to include the current plan in the system prompt even when the codebase is cached.
- Update roxy_home/AGENTS.md to include instructions for the "Planning Plan" workflow.

## Implementation Steps
- [ ] Add PLANNING status to src/main/java/org/roxycode/core/tools/service/plans/PlanStatus.java.
- [ ] Update src/main/java/org/roxycode/core/tools/service/plans/PlanService.java to include listPlanningPlans() and handle PLANNING status in movePlan(), deletePlan(), and findPlanStatus().
- [ ] Update src/main/java/org/roxycode/core/GenAIService.java to move the plan context injection logic outside of the cache check in buildSystemMessage().
- [ ] Update roxy_home/AGENTS.md with the new PLAN mode guidelines for complex tasks, including using planService.createPlan with a move to PLANNING status and setting it as current.
- [ ] Add unit tests in src/test/java/org/roxycode/core/tools/service/plans/PlanServiceTest.java to verify the new PLANNING status and related operations.
- [ ] Verify that the current plan is correctly passed to the agent even when caching is enabled (simulated or manual check).

## Implementation Progress
- [x] Add PLANNING status to PlanStatus.java
- [x] Update PlanService.java to include listPlanningPlans() and handle PLANNING status
- [x] Update GenAIService.java to move plan context outside of cache check
- [x] Update roxy_home/AGENTS.md with new PLAN mode guidelines
- [x] Add and run unit tests in PlanServiceTest.java
- [x] Verify project compiles and tests pass

## Agent Context

