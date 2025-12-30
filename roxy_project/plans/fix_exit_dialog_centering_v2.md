# Plan: Fix Exit Dialog Centering (Attempt 2)

The previous fix for the exit dialog centering (manual bounds calculation) failed to resolve the issue where the dialog appears at the top-left (0,0) of the screen after chatting. This suggests that either the `MainFrame` bounds are reported incorrectly after chat interactions, or the manual calculation is flawed in edge cases.

## Analysis
-   **Current Behavior:** Dialog appears at (0,0) after chatting.
-   **Current Logic:** Calculates center relative to `MainFrame.getBounds()`.
-   **Hypothesis:** `MainFrame` might be reporting (0,0) bounds or the calculation results in (0,0). Or `setLocation` is ineffective due to peer issues.
-   **Proposed Solution:** Simplify the logic. Instead of trying to center over the `MainFrame` (which seems unreliable), we will force centering on the screen using `setLocationRelativeTo(null)`. This is a standard and robust behavior for modal dialogs. We will also ensure `pack()` is definitely called and the size is valid.
-   **Alternative:** We could try `createDialog(null, ...)` to detach from the frame, but we lose the taskbar grouping. We will keep `createDialog(this, ...)` but override location to `null` (screen center).

## Implementation Steps
1.  Modify `src/main/java/org/roxycode/ui/MainFrame.java`.
2.  Update `confirmExit()` method.
3.  Remove the manual bounds calculation logic.
4.  Replace with `dialog.setLocationRelativeTo(null);` to center on screen.
5.  Add a log message to capture that `confirmExit` was called, for future debugging.

## Checklist
- [x] Refactor `confirmExit` to use `setLocationRelativeTo(null)`.
- [x] Verify code changes.
