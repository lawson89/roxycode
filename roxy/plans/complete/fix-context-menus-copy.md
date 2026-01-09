
# Plan: Fix Context Menus for Copying in UI

The user reported that right-click and copy in `ChatView` is not working. Investigation reveals a lack of consistent context menus for text components across the application. This plan aims to provide a functional "Copy" context menu for all relevant text components.

## Proposed Changes

### 1. Cleanup and Fix MarkdownPane
- `MarkdownPane.java` has redundant `setupContextMenu()` and `initContextMenu()` methods.
- I will consolidate these into a single `initContextMenu()` method.
- I will ensure the "Copy" action is correctly enabled/disabled based on text selection.

### 2. Add Context Menu to ChatView's Input Field
- The `inputField` in `ChatView.java` is an `RSyntaxTextArea`.
- I will explicitly ensure it has a functional context menu that includes a "Copy" action (and possibly Cut/Paste as it is editable).

### 3. Add Context Menu to Other Views
- **LogsView**: Add a "Copy" context menu to `logsArea`.
- **MessageHistoryView**: Add a "Copy" context menu to `messageHistoryArea`.
- **CodebaseCacheView**: Add a "Copy" context menu to `cacheContentArea`.

### 4. Implementation Details
- Create a utility method or a common way to attach a standard "Copy" context menu to `JTextComponent`s.
- For editable fields (like `RSyntaxTextArea`), include "Cut", "Copy", and "Paste".

## Verification Plan

### Automated Tests
- Update `MarkdownPaneTest.java` if needed.
- Add unit tests to verify context menu presence on other views if feasible.

### Manual Verification
- Right-click on the chat area in `ChatView` and select "Copy" when text is selected.
- Right-click on the input field in `ChatView` and verify "Cut", "Copy", "Paste" work.
- Verify "Copy" works in `LogsView`, `MessageHistoryView`, and `CodebaseCacheView`.


## Implementation Progress
- [x] Implement `UIUtils.addContextMenu(JTextComponent)`.
- [x] Update `MarkdownPane` to use `UIUtils.addContextMenu` and remove redundant logic.
- [x] Update `ChatView` to add a context menu to the input field.
- [x] Add context menus to other key views (`LogsView`, `MessageHistoryView`, `CodebaseCacheView`).
- [x] Verify that context menus work as expected (Copy for all, Cut/Paste for editable).


## Completion Summary
- Created a reusable `UIUtils.addContextMenu` method that handles both editable and non-editable text components.
- Refactored `MarkdownPane` to use this new utility, cleaning up redundant code.
- Added context menus (with Cut, Copy, Paste, Select All) to the main chat input field.
- Added context menus (with Copy and Select All) to `LogsView`, `MessageHistoryView`, and `CodebaseCacheView`.
- Verified that `MarkdownPane` also correctly uses the new utility.
- Implementation is complete.