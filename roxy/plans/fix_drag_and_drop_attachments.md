# Fix Drag and Drop Attachments

## Problem
The user reports that dragging a file works (the drag operation is accepted), but the file does not appear in the attachments list.

## Investigation
1.  [x] Analyze `src/main/java/org/roxycode/ui/views/ChatView.java` specifically the `FileTransferHandler` inner class.
    - `importData` adds files to `attachedFiles` and calls `updateAttachmentsLabel`.
    - `updateAttachmentsLabel` updates the UI container.
2.  [ ] Create a reproduction test case in `src/test/java/org/roxycode/ui/views/ChatViewDndTest.java` covering `importData`.
    - Need to expose `attachedFiles` for verification.
3.  [ ] Run the test to see if it passes.
4.  [ ] If the test passes, the issue might be in the UI integration (e.g., `attachmentsContainer` not valid, or specific OS/DND behavior).

## Implementation
1.  [ ] Add `List<File> getAttachedFiles()` to `ChatView.java` (package-private).
2.  [ ] Implement `testImportData` in `ChatViewDndTest.java`.
3.  [ ] Fix the issue if reproduced.
    - Potential issue: `DataFlavor` support check logic might be flawed or strict.
    - Potential issue: `URI_LIST_FLAVOR` handling on Linux/Windows vs Mac.
    - Potential issue: `attachmentsContainer` is not repainting correctly.

