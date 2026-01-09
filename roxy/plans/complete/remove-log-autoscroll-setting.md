# Goal
Remove the 'auto-scroll logs' setting from the application and make logs auto-scroll by default.

# Proposed Changes
- Remove `isLogAutoScroll` and `setLogAutoScroll` from `SettingsService.java`.
- Update `LogsView.java` to always scroll to the bottom on refresh.
- Remove `logAutoScrollCheckBox` from `SettingsView.java` and its logic.
- Remove `logAutoScrollCheckBox` from `SettingsView.xml`.

# Implementation Steps
- [ ] Identify and remove log auto-scroll methods and constants in `SettingsService.java`.
- [ ] Modify `refresh()` in `LogsView.java` to make auto-scroll unconditional.
- [ ] Remove the checkbox UI element and associated code in `SettingsView.java`.
- [ ] Remove the checkbox from `SettingsView.xml`.
- [ ] Verify changes and ensure the project compiles.

# Implementation Progress
