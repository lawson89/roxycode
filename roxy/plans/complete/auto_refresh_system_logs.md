# Goal
Implement auto-refresh for System Logs in LogsView using UISchedulerService and provide a Sierra MenuButton to toggle it.

# Proposed Changes
- Update `LogsView.xml` to include a `MenuButton` with an 'Auto-Refresh' toggle item.
- Update `LogsView.java` to handle the 'Auto-Refresh' toggle, default to ON, and implement `autoRefresh()` method.
- Implement scrolling to bottom in `LogsView.refresh()` more robustly if needed.
- Update `UISchedulerService.java` to inject `LogsView` and call `logsView.autoRefresh()` every second.

# Implementation Steps
- [ ] 1. Modify `src/main/resources/org/roxycode/ui/views/LogsView.xml` to add the `MenuButton` and `MenuItem`.
- [ ] 2. Modify `src/main/java/org/roxycode/ui/views/LogsView.java` to add `@Outlet` for the new components, state for auto-refresh, and the toggle logic.
- [ ] 3. Modify `src/main/java/org/roxycode/ui/UISchedulerService.java` to inject `LogsView` and call `autoRefresh()`.
- [ ] 4. Verify the changes by checking if logs auto-refresh and scroll when the toggle is on, and stop when off.

# Implementation Progress
- [x] ✅ Add Auto-Refresh checkbox to LogsView.xml
- [x] ✅ Implement autoRefresh() method in LogsView.java
- [x] ✅ Update UISchedulerService.java to call autoRefresh()
- [x] ✅ Verify changes by running tests
