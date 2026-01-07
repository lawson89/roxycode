# Plan: Add Auto-scroll Toggle for System Logs

The user reported that auto-scrolling to the bottom of the system log view doesn't work well and suggested adding a toggle to enable/disable it in the settings.

## Proposed Changes

### 1. `SettingsService.java`
- Add a new setting key `KEY_LOG_AUTO_SCROLL`.
- Add a default value `DEFAULT_LOG_AUTO_SCROLL = true`.
- Add getter and setter for `isLogAutoScroll()`.

### 2. `SettingsView.xml`
- Add a `check-box` component for "Auto-scroll Logs" in the "Global Settings" tab.

### 3. `MainFrame.java`
- Add an `@Outlet` field for `logAutoScrollCheckBox`.
- Update `initSettings()` to populate the checkbox from `SettingsService`.
- Update `onSaveSettings()` to save the checkbox state to `SettingsService`.
- Update `updateLogsView()` to only scroll to the bottom if `settingsService.isLogAutoScroll()` is true.
- Wrap the scroll-to-bottom logic in `SwingUtilities.invokeLater` to ensure it works correctly after the text area has been updated.

## Verification Plan

### Manual Test
1. Open the application.
2. Go to the "Logs" view.
3. Observe if it auto-scrolls to the bottom.
4. Go to "Settings".
5. Uncheck "Auto-scroll Logs" and click "Save Settings".
6. Go back to "Logs" (or refresh logs).
7. Observe that it no longer auto-scrolls to the bottom.
8. Re-enable "Auto-scroll Logs" and verify it starts scrolling again.

### Automated Test
- Create a unit test for `SettingsService` to verify the new property can be saved and retrieved.
- (Optional) If possible, test `updateLogsView` logic, though UI testing might be complex.
