# Plan: Fix Exit Dialog Centering Bug

The user reports a subtle bug where the exit confirmation dialog is centered correctly the first time, but appears in the top-left corner on subsequent attempts after interacting with the chat.

## Analysis
-   Existing implementation uses `JOptionPane.createDialog(this, ...)` and `dialog.setLocationRelativeTo(this)`.
-   The previous attempt to fix this issue moved from `showConfirmDialog` to `createDialog`, but the issue persists or recurs.
-   The symptom (top-left positioning) suggests that `setLocationRelativeTo` fails to calculate the correct position relative to the `MainFrame`, possibly due to state changes in the `MainFrame` or `JDialog` parent resolution after UI interactions (like `MarkdownPane` updates).
-   To resolve this reliably, we will implement explicit manual centering logic based on the `MainFrame`'s bounds.

## Implementation Steps
1.  Modify `src/main/java/org/roxycode/ui/MainFrame.java`.
2.  Locate `confirmExit()` method.
3.  Replace the `dialog.setLocationRelativeTo(this)` call with manual bounds calculation:
    -   Get `this.getBounds()`.
    -   Get `dialog.getSize()`.
    -   Calculate `x` and `y` to center the dialog.
    -   Set `dialog.setLocation(x, y)`.
4.  Ensure `dialog.pack()` is called (it is called by `createDialog` but we should verify size is valid before calculation).
5.  Retain the check for `System.exit(0)`.

## Checklist
- [x] Refactor `confirmExit` in `MainFrame.java`.
- [x] Verify compilation.
