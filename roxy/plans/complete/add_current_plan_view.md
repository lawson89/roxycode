# add_current_plan_view

## Goal
Add a "Current Plan" view to the RoxyCode UI that displays the markdown of the active plan and autoupdates via UISchedulerService.

## Proposed Changes
- Create `CurrentPlanView` class in `org.roxycode.ui.views` extending `JPanel`.
- Use `MarkdownPane` within `CurrentPlanView` to render the plan markdown.
- Add a `refresh()` method to `CurrentPlanView` that uses `PlanService.getCurrentPlanMarkdown()`.
- Register `CurrentPlanView` in `UISchedulerService` to call `refresh()` periodically.
- Inject and add `CurrentPlanView` to `MainFrame` as a new tab or panel.
- Update the UI definition (Sierra) to include the new view.

## Implementation Steps
- [ ] Create `src/main/java/org/roxycode/ui/views/CurrentPlanView.java`.
- [ ] Modify `src/main/java/org/roxycode/ui/UISchedulerService.java` to include `CurrentPlanView` and call its `refresh()` method in `updateUI()`.
- [ ] Modify `src/main/java/org/roxycode/ui/MainFrame.java` to inject `CurrentPlanView` and add it to the main content area.
- [ ] Update `src/main/resources/org/roxycode/ui/MainFrame.sierra` (or equivalent) to add the "Current Plan" tab.
- [ ] Verify that the "Current Plan" view updates when a plan is created, moved, or deleted.

## Implementation Progress
- [x] X Create `CurrentPlanView.java` in `org.roxycode.ui.views`.
- [x] X Update `MainFrame.java` to include `CurrentPlanView` as a tab.
- [x] X Update `UISchedulerService.java` to refresh `CurrentPlanView`.
- [x] X Verify that the "Plan" tab is visible and displays the current plan markdown.

## Agent Context

