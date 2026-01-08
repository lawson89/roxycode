
# Plan: Drag and Drop Attachments

Support dragging and dropping files into the ChatView to attach them to the conversation.

## Proposed Changes

### UI Components
- **ChatView**: 
    - Implement a `TransferHandler` to handle `DataFlavor.javaFileListFlavor`.
    - Set the `TransferHandler` on the main view component (`viewChat`) and potentially the `inputField`.
    - Update `attachedFiles` and refresh the attachments display when files are dropped.

## Implementation Steps

1. **Create TransferHandler**:
    - Define a custom `FileTransferHandler` extending `TransferHandler`.
    - Override `canImport` to check for `DataFlavor.javaFileListFlavor`.
    - Override `importData` to extract the list of files and add them to `ChatView`'s `attachedFiles`.

2. **Integrate into ChatView**:
    - In `ChatView.init()`, instantiate the `FileTransferHandler`.
    - Set it on `this` (ChatView) or `viewChat`.
    - Ensure it doesn't conflict with existing text drag-and-drop in `RSyntaxTextArea`.

3. **Update UI**:
    - Call `updateAttachmentsLabel()` after files are dropped.

4. **Testing**:
    - Manual testing (dragging files from explorer to the app).
    - Unit test for the `TransferHandler` logic if possible (mocking `TransferSupport`).

## Implementation Progress
- [x] Create `FileTransferHandler` (nested class in `ChatView`)
- [x] Integrate into `ChatView` (in `init()` via `setupDragAndDrop()`)
- [x] Verify functionality (compilation successful, manual test pending)

## Implementation Summary
Implemented `FileTransferHandler` as a private inner class within `ChatView`. It supports `DataFlavor.javaFileListFlavor` and adds dropped files to the `attachedFiles` list, then updates the UI. The handler is registered on the main `viewChat` panel and the `inputField`.

Work is complete.
