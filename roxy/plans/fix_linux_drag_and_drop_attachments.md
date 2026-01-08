# Fix Linux Drag and Drop Attachments

## Problem
Users on Linux report that dragging and dropping files onto the chat view does not result in them appearing in the attachments list, although the drop action itself seems to occur.

## Analysis
- **Component**: `ChatView` and its `FileTransferHandler`.
- **Symptom**: File dropped, but no visual attachment.
- **Hypothesis**: The data flavor extraction might be failing or the list update logic is flawed for the specific data format provided by Linux window managers (often URI lists).

## Plan
- [ ] **Explore Code**: Read `src/main/java/org/roxycode/ui/views/ChatView.java` to understand the `importData` method in `FileTransferHandler`.
- [ ] **Investigate Data Flavors**: Check what DataFlavors are supported and if Linux provides a different one (e.g., `text/uri-list`) that isn't being handled correctly.
- [ ] **Reproduction/Test**: Since I am in a headless environment, I will rely on unit tests. I will check `src/test/java/org/roxycode/ui/views/ChatViewDndTest.java` and potentially add a test case simulating Linux-style DnD data.
- [ ] **Fix**: Modify `FileTransferHandler` to support the missing DataFlavor or fix the processing logic.
- [ ] **Verify**: Ensure tests pass.

## Implementation Progress
- [ ] Explore Code
- [ ] Update Plan with Findings
- [ ] Implement Fix
- [ ] Verify
