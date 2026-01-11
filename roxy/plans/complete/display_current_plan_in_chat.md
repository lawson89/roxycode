# display_current_plan_in_chat

## Goal
Display the current plan name in the Chat View status bar and allow jumping to the plan view.

## Proposed Changes
- Add a button to the Chat View status bar to display the current plan.
- Implement updatePlanContext() in ChatView to refresh the plan name.
- Wire the button to switch to the CurrentPlanView when clicked.
- Update UISchedulerService to call updatePlanContext() periodically.

## Implementation Steps
- [ ] Check ChatView.xml for status bar layout.
- [ ] Add planContextButton to ChatView.xml.
- [ ] Add updatePlanContext() method to ChatView.java.
- [ ] Initialize the button in ChatView.init() and add action listener.
- [ ] Update UISchedulerService.java to include chatView.updatePlanContext() in the UI update loop.

## Implementation Progress

## Agent Context

