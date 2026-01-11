# Goal
Add a specific method to PlanService to retrieve the markdown of the current plan and use it in GenAIService for better encapsulation and clarity.

# Proposed Changes
- Add getCurrentPlanMarkdown() to PlanService.java that specifically looks for the current plan in the in_progress directory.
- Refactor GenAIService.java to use this new method instead of manually fetching the plan name and calling getPlanMarkdown.
- Add unit test for getCurrentPlanMarkdown in PlanServiceTest.java.

# Implementation Steps
- [ ] Modify PlanService.java to include getCurrentPlanMarkdown().
- [ ] Modify GenAIService.java to use planService.getCurrentPlanMarkdown().
- [ ] Add a unit test in PlanServiceTest.java to verify getCurrentPlanMarkdown() works as expected.
- [ ] Run tests to ensure everything is working correctly.

# Implementation Progress
- [x] Add `getCurrentPlanMarkdown()` to `PlanService` [x]
- [x] Expose `getCurrentPlanMarkdown()` via `PlanService` to GraalJS [x]
- [x] Add `getCurrentPlanMarkdown()` test in `PlanServiceTest` [x]
- [x] Update `GenAIService.buildSystemMessage` to include current plan markdown [x]

