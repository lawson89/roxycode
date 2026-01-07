# Plan: Modularize MainFrame UI

Refactor the `MainFrame` class by extracting major UI sections into their own view classes and Sierra XML layouts. This improves maintainability, separates concerns, and allows for cleaner layout definitions.

## Proposed Changes

### 1. View Extraction
- Create individual view classes in `org.roxycode.ui.views`:
    - `ChatView`
    - `SettingsView`
    - `FilesView`
    - `UsageView`
    - `LogsView`
    - `GeminiOnlineCachesView`
    - `CodebaseCacheView`
    - `SystemPromptView`
    - `MessageHistoryView`
- Each class will have its own `init()` method to load its Sierra layout and bind components.
- Each class will have a `refresh()` method (where applicable) to update its state.

### 2. MainFrame Refactoring
- Update `MainFrame.java` to use these new view classes instead of managing all components directly.
- Clean up `MainFrame.xml` to only contain the high-level layout (sidebar and content stack).

### 3. Layout Modernization
- Use consistent padding (24) and spacing (16) across all views.
- Add descriptive headers and help text to each view.
- Apply consistent borders (matte 1,1,1,1) to scroll panes and tables.
- Use FlatLaf typography styles (`h2`, `font: -1`, `font: -2`).

## Progress Checklist
- [x] Refactor `MainFrame` to extract views into separate classes
- [x] Modularize all remaining views (Usage, Logs, Caches, etc.)
- [x] Modernize all XML layout files with consistent styles and spacing
- [x] Verify all views initialize and refresh correctly in the new structure

## Implementation Progress
- [x] Initial planning
- [x] Extracted ChatView, SettingsView, FilesView
- [x] Extracted all other views (Usage, Logs, Caches, System Prompt, Message History)
- [x] Refactored MainFrame to use the new view classes
- [x] Modernized all XML layout files

## Verification
- Run `mvn compile` to ensure no regressions.
