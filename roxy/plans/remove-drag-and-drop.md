# Plan: Remove Drag and Drop for File Attachments

The drag-and-drop functionality for file attachments in `ChatView` is being removed as requested by the user.

## Proposed Changes

### 1. Modify `ChatView.java`
- Remove `createUriListFlavor()` method and `URI_LIST_FLAVOR` constant.
- Remove call to `setupDragAndDrop()` in `init()`.
- Remove `setupDragAndDrop()` method.
- Remove `FileTransferHandler` inner class.

### 2. Delete `ChatViewDndTest.java`
- Since the drag-and-drop functionality is being removed, its corresponding test class should be deleted.

## Verification Plan

### Automated Tests
- Run all tests to ensure no regressions.
- Explicitly check that `ChatViewDndTest` is gone.

### Manual Verification
- Verify that files can still be attached via the "Attach" button.
- Verify that dragging files onto the chat area or input field no longer attaches them.

## Implementation Progress
- [x] Modify `ChatView.java`
- [x] Delete `ChatViewDndTest.java`
- [x] Verify compilation and tests

## Status
**Complete**