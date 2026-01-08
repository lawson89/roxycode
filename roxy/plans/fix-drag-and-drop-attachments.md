
# Plan: Fix Drag and Drop for Attachments

The user reports that drag and drop is not adding attachments and shows a GDK cursor error on Linux. This is likely due to the default drop action being set to `MOVE` when it should be `COPY`.

## Steps:

1.  **Modify `ChatView.java`**:
    *   In `FileTransferHandler.canImport`, explicitly set the drop action to `TransferHandler.COPY` when a supported file flavor is detected.
    *   This should resolve the GDK cursor issue and ensure the drop is accepted correctly.

2.  **Implementation Details**:
    *   Update `canImport` to:
        ```java
        @Override
        public boolean canImport(TransferSupport support) {
            boolean supported = support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || 
                   (URI_LIST_FLAVOR != null && support.isDataFlavorSupported(URI_LIST_FLAVOR));
            
            if (supported) {
                support.setDropAction(COPY);
                return true;
            }
            
            return (delegate != null && delegate.canImport(support));
        }
        ```

3.  **Verification**:
    *   Verify that the code compiles.
    *   Since automated DND testing is difficult, I will perform a manual review of the logic.
    *   If possible, I will create a unit test that mocks `TransferSupport` and `Transferable` to verify the `canImport` and `importData` logic in a separate test class or by making the handler more accessible.

## Progress Checklist:
- [x] Modify `ChatView.java` <!-- id: 0 -->
- [x] Verify compilation <!-- id: 1 -->
- [x] Add unit test for FileTransferHandler <!-- id: 2 -->


## Summary
- Fixed the Drag and Drop cursor issue on Linux by explicitly setting the drop action to `COPY` in `FileTransferHandler.canImport`.
- Made `FileTransferHandler` package-private to allow unit testing.
- Added `ChatViewDndTest` to verify the fix.
- All tests passed.

**Status: Complete**