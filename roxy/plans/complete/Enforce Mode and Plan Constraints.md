# Enforce Mode and Plan Constraints

## Goal
Enforce that CODE mode cannot be entered without an active plan, and ensure automatic reversion to PLAN mode if the active plan is cleared.

## Proposed Changes
- Modify RoxyProjectService.java to include validation in setCurrentMode and automatic reversion in setCurrentPlan.
- Update ModeService.java to improve documentation/LLMDoc regarding the new constraints.
- Update roxy_home/AGENTS.md to inform the LLM about the enforced constraints.

## Implementation Steps
- [ ] Create ModeEnforcementTest.java to define expected behavior and verify failure/success.
- [ ] Update RoxyProjectService.java: Add validation to setCurrentMode and auto-reversion to setCurrentPlan.
- [ ] Update RoxyProjectService.java: Ensure getModeMessage() uses getCurrentMode() (if logic is added there) or ensure state is consistent.
- [ ] Update ModeService.java: Update setCodeMode documentation.
- [ ] Update roxy_home/AGENTS.md: Add a note about enforced mode transitions.
- [ ] Run tests and ensure everything passes.

## Implementation Progress
- [x] Implement setCurrentPlan update in RoxyProjectService
- [x] Add unit test for setCurrentPlan behavior in RoxyProjectServiceTest
- [x] Add unit test for delegation in PlanServiceTest
- [x] Verify tests pass
## Agent Context

