# Plan: Update Roxy Mode in UI Scheduler

The UISchedulerService should trigger an update of the Roxy Mode in the MainFrame periodically to ensure the UI stays in sync with the current state of the RoxyProjectService.

## Steps
1.  **Modify UISchedulerService.java**:
    *   Inject `MainFrame`.
    *   Update the `updateUI` method to call `mainFrame.updateRoxyMode()` if `mainFrame` is not null.
2.  **Verify Changes**:
    *   Ensure the code compiles.
    *   Since it's a UI change that happens on a schedule, manual verification or a mock test could be used.

## Implementation Progress
- [x] Inject MainFrame into UISchedulerService
- [x] Call mainFrame.updateRoxyMode() in updateUI()
- [x] Verify compilation

## Status
**COMPLETED**