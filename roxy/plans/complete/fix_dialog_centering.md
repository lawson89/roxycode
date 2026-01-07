# Plan: Fix Dialog Centering Issue

The user is reporting that dialogs (like the Exit confirmation) are appearing in the top-left corner of the screen instead of being centered over the application.

## Analysis
-   Existing implementation uses `JOptionPane.showConfirmDialog(this, ...)` or `JOptionPane.showMessageDialog(this, ...)`.
-   While Swing is supposed to handle centering when a parent is provided, this can fail if the parent is not yet fully realized or if there are multiple monitors.
-   A more robust approach is to manually create the `JDialog` from the `JOptionPane` and use `setLocationRelativeTo(parent)`.

## Proposed Changes
-   Create a utility class `UIUtils` to encapsulate robust centering logic.
-   The logic should ensure that the dialog is packed, its location is set relative to the parent, and if it still appears at (0,0), it should be centered on the screen.

## Tasks
- [x] Create `UIUtils` class to handle common UI tasks.
- [x] Update `MainFrame.confirmExit` to use the manual dialog creation and centering logic.
- [x] Update `MainFrame.onAbout` to use the same logic.
- [x] Update `SettingsView.onSaveSettings` to use the same logic.
- [x] Update `CodebaseCacheView` and `GeminiOnlineCachesView` to use the same logic for their alerts and confirmations.
- [x] Add a fallback centering mechanism that uses the screen's initial center if the main frame hasn't fully rendered.
- [x] Verify that all modified dialogs now appear correctly centered.

### Progress Section
- **Status:** Complete
- **Date:** 2026-01-06
- **Changes made:**
  - Created `src/main/java/org/roxycode/core/utils/UIUtils.java`.
  - Updated `MainFrame.java`, `SettingsView.java`, `CodebaseCacheView.java`, `GeminiOnlineCachesView.java` to use `UIUtils.centerDialog()`.
  - Added initial screen center capture in `MainFrame.run()` for fallback centering.
