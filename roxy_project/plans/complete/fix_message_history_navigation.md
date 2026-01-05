# Plan - Fix Message History View Navigation

The user reported that clicking on the "Message History" view in the navigation menu does nothing. 

## Analysis
1. In `MainFrame.initListeners()`, the `navMessageHistoryButton` action listener calls `updateMessageHistoryView()` directly.
2. `updateMessageHistoryView()` updates the content of the view but does not handle switching to that view (i.e., making it visible and hiding others).
3. All other navigation buttons call `showView(String)`, which handles the visibility logic.
4. `showView("MESSAGE_HISTORY")` already exists and correctly calls `updateMessageHistoryView()` and sets the view to visible.

Additionally, I noticed that `navSummaryQueueButton` calls `showView("SUMMARY_QUEUE")`, but `SUMMARY_QUEUE` is missing from the `switch` statement in `showView()`.

## Proposed Changes

### `src/main/java/org/roxycode/ui/MainFrame.java`

1. Update `initListeners()`:
   - Change `navMessageHistoryButton` listener to call `showView("MESSAGE_HISTORY")`.

2. Update `showView(String)`:
   - Add a case for `"SUMMARY_QUEUE"` to switch to `viewSummaryQueue`.

## Verification Plan
- Manually verify the code changes.
- Since this is UI logic, it's hard to unit test without more extensive mocking, but I can check if there are existing UI tests I can leverage.
- Ensure the project compiles.

## Progress
- [x] Update `initListeners()` in `MainFrame.java`
- [x] Update `showView()` in `MainFrame.java`
- [x] Verify compilation

## Implementation Complete
The changes have been implemented and verified by a successful build. Clicking "Message History" and "Summary Queue" should now correctly navigate to their respective views.
