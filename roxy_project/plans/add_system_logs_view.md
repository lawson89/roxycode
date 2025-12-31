# Plan: Add System Logs View

Add a new view to RoxyCode that displays the last N lines of `stdout` and `stderr`, refreshed every 10 seconds. The number of lines displayed will be user-configurable in the settings.

## Proposed Changes

### 1. `SettingsService.java`
- Add `KEY_LOG_LINES_COUNT` (default 100).
- Add getter and setter for `logLinesCount`.

### 2. `SettingsView.xml` & `SettingsView.java` (part of `MainFrame.java`)
- Add a new section or fields in `SettingsView.xml` for "System Logs".
- Add "Last N Lines" text field.
- Update `initSettings` and `onSaveSettings` in `MainFrame.java` to handle the new setting.

### 3. `LogCaptureService.java`
- Create a new `@Singleton` service in `org.roxycode.core`.
- Redirect `System.out` and `System.err`.
- Use a thread-safe circular buffer or a limited list to store log lines.
- Provide a method `getLogs(int count)` to retrieve the last N lines.

### 4. `LogsView.xml` & `LogsView.java`
- Create `src/main/resources/org/roxycode/ui/LogsView.xml` with a `column-panel` containing a `scroll-pane` with a `text-area` (monospaced) and a "Refresh" button.
- Implement the logic in `MainFrame.java` (since it currently handles all views) to populate this view.
- Add a `Timer` in `MainFrame.java` to refresh the logs every 10 seconds when the logs view is visible.

### 5. `MainFrame.java` & `MainFrame.xml`
- Add `navLogsButton` to `MainFrame.xml`.
- Add `@Outlet` for the new view and button.
- Add listener for `navLogsButton`.
- Update `showView` and `run` to include the logs view.
- Initialize `LogCaptureService` and start redirection.

## Verification Plan

### Automated Tests
- Create `LogCaptureServiceTest.java` to verify that `stdout` and `stderr` are correctly captured and the limit is respected.

### Manual Verification
- Start the application.
- Navigate to "Settings" and change the "Last N Lines" count.
- Navigate to "System Logs".
- Observe that logs from the application (e.g., startup logs, tool executions) are displayed.
- Verify that it refreshes every 10 seconds.
- Click "Refresh" manually and verify it updates.
- Check that both `stdout` (normal logs) and `stderr` (if any errors occur) are present.

## Progress
- [x] Create `LogCaptureService.java`
- [x] Update `SettingsService.java`
- [x] Update `SettingsView.xml` and `MainFrame.java` (init/save settings)
- [x] Create `LogsView.xml`
- [x] Update `MainFrame.java` (navigation and refresh logic)
- [x] Add unit tests for `LogCaptureService`
- [x] Final manual verification
