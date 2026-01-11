
# Add Task List Support to PlanService

## Goal
Enhance `PlanService` to explicitly support Markdown task lists in the Implementation Progress section, allowing steps to be marked as complete ([x]) or incomplete ([ ]).

## Proposed Changes
1.  **Modify `PlanService.java`**:
    *   Update `getLinesInSection` to support parsing task list items (`- [ ]` and `- [x]`) and preserve their state.
    *   Update `convertToMarkdownList` or create a specialized version for task lists that respects the completion status.
    *   Update `updateImplementationProgress` to work with task list items.
    *   Add `completeStep(String planName, String stepText)` to mark a specific step in the Implementation Progress as complete. If the step does not exist, it should be added as complete.
    *   Add `addProgressStep(String planName, String stepText, boolean completed)` to add a step with a specific status.
2.  **Modify `Plan.java`**:
    *   Consider changing `implementationProgress` from `List<String>` to `List<String>` where the string includes the `[ ]` or `[x]` prefix (e.g., `[x] Step description`).
    *   Alternatively, introduce a `TaskItem` record/class to represent a task with its text and completion status. *Decision: Use `TaskItem` for better type safety and clarity.*
3.  **Update `PlanServiceTest.java`**:
    *   Add tests for the new methods and the updated parsing logic.

## Implementation Steps
1.  Define a `TaskItem` record (or nested class) in `Plan.java`.
2.  Refactor `Plan.java` to use `List<TaskItem>` for `implementationSteps` and `implementationProgress`.
3.  Update `PlanService.java`:
    *   Update `getLinesInSection` to parse task markers and return `List<TaskItem>`.
    *   Update `convertToMarkdownList` to handle `TaskItem` objects.
    *   Update existing update methods to adapt to `TaskItem`.
    *   Implement `addProgressStep` and `completeStep`.
4.  Verify changes with unit tests.

## Implementation Progress
- [ ] Define TaskItem record
- [ ] Refactor Plan.java
- [ ] Update PlanService parsing and conversion logic
- [ ] Implement addProgressStep and completeStep
- [ ] Update and run PlanServiceTest

## Agent Context
- The user specifically requested support for task lists (- [ ] and - [x]).
- Capital [X] should also be supported for completion.
- Existing methods like `updateImplementationSteps` should now default to creating unchecked task items.
- `updateImplementationProgress` should preserve existing markers if provided, or default to checked.
