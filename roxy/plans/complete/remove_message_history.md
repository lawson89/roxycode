# Goal
Remove the Message History view and its associated service (HistoryService). This feature is no longer needed.

# Proposed Changes
- Delete `HistoryService.java` and its unit test `HistoryServiceTest.java`.
- Delete `MessageHistoryView.java` and its Sierra XML definition `MessageHistoryView.xml`.
- Remove `navMessageHistoryButton` from `MainFrame.xml`.
- Update `MainFrame.java` to remove references to `MessageHistoryView` and the navigation button.
- Update `GenAIService.java` to remove `HistoryService` and its usage (sliding window logic).
- Update `SettingsService.java` to remove `historyWindowSize` setting and related constants.
- Update `SettingsView.java` and `SettingsView.xml` to remove the UI for `historyWindowSize`.

# Implementation Steps
- [ ] Delete `src/main/java/org/roxycode/core/HistoryService.java` and `src/test/java/org/roxycode/core/HistoryServiceTest.java`.
- [ ] Delete `src/main/java/org/roxycode/ui/views/MessageHistoryView.java` and `src/main/resources/org/roxycode/ui/views/MessageHistoryView.xml`.
- [ ] Modify `src/main/resources/org/roxycode/ui/MainFrame.xml` to remove `navMessageHistoryButton`.
- [ ] Modify `src/main/java/org/roxycode/ui/MainFrame.java` to remove `messageHistoryView` and `navMessageHistoryButton` outlets, initialization, and listener logic.
- [ ] Modify `src/main/java/org/roxycode/core/SettingsService.java` to remove `KEY_HISTORY_WINDOW_SIZE`, `DEFAULT_HISTORY_WINDOW_SIZE`, and associated getter/setter.
- [ ] Modify `src/main/resources/org/roxycode/ui/views/SettingsView.xml` to remove the `historyWindowSize` text field and its label.
- [ ] Modify `src/main/java/org/roxycode/ui/views/SettingsView.java` to remove the `historyWindowSize` outlet and logic for loading/saving this setting.
- [ ] Modify `src/main/java/org/roxycode/core/GenAIService.java` to remove `HistoryService` dependency and the call to `historyService.applySlidingWindow(history)`.
- [ ] Run build and tests to ensure everything is still working correctly (except for the removed sliding window functionality).

# Implementation Progress
- [x] Deleted HistoryService.java and HistoryServiceTest.java
- [x] Deleted MessageHistoryView.java and MessageHistoryView.xml
- [x] Removed navMessageHistoryButton from MainFrame.xml
- [x] Cleaned up MainFrame.java
- [x] Cleaned up SettingsService.java
- [x] Cleaned up SettingsView.xml and SettingsView.java
- [x] Cleaned up GenAIService.java
- [x] Verified build and tests pass
