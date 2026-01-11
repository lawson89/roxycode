# update-no-plan-message

## Goal
Instead of displaying an error when no current plan is active, display "No current plan".

## Proposed Changes
- Modify `PlanService.getCurrentPlanMarkdown()` to return `null` if the current plan name is null or blank.
- Update `CurrentPlanView.refresh()` to display "No current plan" when no markdown is available.

## Implementation Steps
- [ ] Update `src/main/java/org/roxycode/core/tools/service/plans/PlanService.java` to handle null/blank plan names in `getCurrentPlanMarkdown()`.
- [ ] Update `src/main/java/org/roxycode/ui/views/CurrentPlanView.java` to display the specific message "No current plan" when no active plan is found.
- [ ] Verify the fix by clearing the current plan (if any) and navigating to the Plan view.

## Implementation Progress

## Agent Context

