# Plan: Remove Rescan Button

This plan details the steps to remove the "Rescan" button from the RoxyCode UI. The `performRescan()` functionality will be retained for automated triggers (like startup and project changes) but the manual trigger button will be removed.

## Checklist

- [x] Remove Rescan button from `MainFrame.xml`
- [x] Remove `rescanButton` outlet and related references in `MainFrame.java`
- [x] Verify that automatic rescanning still works (startup and project switch)
- [x] Run tests to ensure no regressions

## Proposed Changes

### UI Layout

#### [src/main/resources/org/roxycode/ui/MainFrame.xml](src/main/resources/org/roxycode/ui/MainFrame.xml)

- Remove the `<button name="rescanButton" text="Rescan"/>` element from the header row panel.

### Java Code

#### [src/main/java/org/roxycode/ui/MainFrame.java](src/main/java/org/roxycode/ui/MainFrame.java)

- Remove the `@Outlet` field `rescanButton`.
- Remove the icon initialization for `rescanButton` in `initIcons()`.
- Remove the action listener registration for `rescanButton` in `initListeners()`.
- **Note:** `performRescan()` and its calls in `run()` (startup) and `onOpenFolder()` must be preserved.

## Verification Plan

### Automated Tests
- Run `mvn test` to ensure the project still builds and existing tests pass.

### Manual Verification
- Launch the application and verify that the "Rescan" button is no longer visible in the header.
- Verify that "Knowledge base reloaded" message still appears in the chat on startup (indicating `performRescan()` still runs).
- Open a new folder and verify that "Knowledge base reloaded" message appears.
