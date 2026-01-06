# Plan: Remove Exit Confirmation Dialog

The user wants to remove the confirmation dialog that appears when exiting the application. Currently, this dialog is triggered both when closing the main window and when selecting "Exit" from the menu.

## Proposed Changes

### `MainFrame.java`
- Update `initListeners()`:
    - Change `windowClosing` adapter to call `System.exit(0)` directly (or just let it close if `DISPOSE_ON_CLOSE` was set, but it's currently `DO_NOTHING_ON_CLOSE`).
    - Change `exitMenuItem` action listener to call `System.exit(0)`.
- Remove the `confirmExit()` method.
- Change `setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE)` to `JFrame.EXIT_ON_CLOSE` in the constructor.

## Verification Plan
- Build the project to ensure no compilation errors.
- (Manual) Verify that closing the window or clicking Exit exits immediately.

## Implementation Progress
- [x] Update `MainFrame.java` constructor to use `EXIT_ON_CLOSE`.
- [x] Update `initListeners()` in `MainFrame.java`.
- [x] Remove `confirmExit()` method from `MainFrame.java`.
- [x] Compile and verify.
