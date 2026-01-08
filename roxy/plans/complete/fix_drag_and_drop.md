# Plan: Fix Drag and Drop for File Attachments

The user reports being unable to drag and drop files into the chat to attach them (seeing a red circle with a slash).

## Analysis
1.  `ChatView` implements `FileTransferHandler` to handle `DataFlavor.javaFileListFlavor`.
2.  The handler is currently applied to `viewChat` (root container) and `inputField` (text area).
3.  The `chatArea` (the large `MarkdownPane` where most of the chat history is) is NOT configured with the handler. This is likely the primary reason users see the red circle when dragging over the main part of the window.
4.  The `inputField` should technically work, but if it's also showing the red circle, there might be an issue with how the `TransferHandler` interacts with `RSyntaxTextArea` or the available `DataFlavor`s.

## Proposed Changes
1.  Update `ChatView.setupDragAndDrop()` to also apply the `FileTransferHandler` to `chatArea` and `chatScrollPane`.
2.  Improve `FileTransferHandler.canImport()` to explicitly set the drop action if needed, though standard `canImport` should be enough.
3.  Add support for `text/uri-list` flavor to improve compatibility with Linux and some other environments.
4.  Ensure that the `delegate` handler is always called if we don't handle the flavor.

## Implementation Steps
1.  Modify `ChatView.java`:
    *   Add a constant or utility to handle `text/uri-list` parsing.
    *   Update `FileTransferHandler.canImport` to check for both `javaFileListFlavor` and `text/uri-list`.
    *   Update `FileTransferHandler.importData` to handle both flavors.
    *   Update `setupDragAndDrop` to include `chatArea` and `chatScrollPane`.
2.  Verify the changes by compiling the project.
3.  (Optional) Add a unit test for `ChatView` to simulate file drop if possible.

## Progress Checklist
- [x] Research `text/uri-list` parsing in Java.
- [ ] Modify `ChatView.java` to include `chatArea` and `chatScrollPane` in drag and drop setup.
- [ ] Enhance `FileTransferHandler` to support more file flavors.
- [ ] Verify compilation.

## Implementation Progress
- [x] Researched `text/uri-list` parsing.
