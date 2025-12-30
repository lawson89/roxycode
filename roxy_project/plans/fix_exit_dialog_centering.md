# Plan: Fix Exit Dialog Centering

The user reports that the "Confirm exit" dialog displays at the top left and is not centered on the RoxyCode window.
The current implementation uses `JOptionPane.showConfirmDialog(this, ...)`. While this should work, it appears to be failing in the user's environment.

## Goals
1.  Ensure the Exit Confirmation dialog is reliably centered on the `MainFrame`.

## Implementation Details
1.  Modify `src/main/java/org/roxycode/ui/MainFrame.java`.
2.  Refactor `confirmExit()` method.
3.  Instead of `JOptionPane.showConfirmDialog`, use `JOptionPane` object to create a `JDialog`.
    -   Instantiate `JOptionPane`.
    -   Call `createDialog(this, "Confirm Exit")`.
    -   Explicitly call `dialog.setLocationRelativeTo(this)` (redundant but safe).
    -   Show the dialog and handle the result.

## Verification
-   Compile the project.
-   Cannot visually verify in this environment, but the code change uses a more explicit centering method.

## Status
- [x] Plan Created
- [x] Implementation Started
- [x] Implementation Complete

