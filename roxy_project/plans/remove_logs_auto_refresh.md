# Plan: Remove Auto Refresh from System Logs View

Remove the automatic refresh mechanism from the System Logs view while maintaining the manual refresh functionality.

## Proposed Changes

### UI Code (`MainFrame.java`)
- Remove the `logsTimer` field.
- Remove the `startLogsTimer()` method.
- Remove the `stopLogsTimer()` method.
- Remove the calls to `startLogsTimer()` and `stopLogsTimer()` in `showView(String viewName)`.

## Verification Plan

### Automated Tests
- Ensure that the project still compiles.
- Run existing tests to ensure no regressions.

### Manual Verification
- Navigate to the "System Logs" view.
- Verify that it displays logs initially.
- Verify that it does NOT auto-refresh.
- Verify that clicking the "Refresh" button manually updates the logs.
