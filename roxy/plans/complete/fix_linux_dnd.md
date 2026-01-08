# Fix Linux Drag and Drop (URI List)

The user reported that dragging and dropping files on Linux does not work (files do not show up in attachments).
This is likely due to Linux using `text/uri-list` flavor instead of `javaFileListFlavor`.

## Plan

- [x] Investigate `ChatView.java` to see how DnD is handled.
- [x] Modify `ChatView.java` to support `text/uri-list` flavor.
- [x] Implement parsing of `text/uri-list` (handling CRLF, comments, URI encoding).
- [x] Handle edge cases:
    - [x] URIs with spaces (not encoded).
    - [x] Raw file paths (not starting with `file:`).
- [x] Add unit tests for `text/uri-list` handling.
    - [x] `ChatViewDndTest` updated with new tests.
- [x] Verify fix with tests.

## Implementation Progress

- Analyzed `ChatView.java`.
- Added `URI_LIST_FLAVOR` constant.
- Updated `canImport` to check for `URI_LIST_FLAVOR`.
- Updated `importData` to handle `URI_LIST_FLAVOR`.
- Implemented robust URI/path parsing.
- Updated `ChatViewDndTest.java` with comprehensive test cases.
- All tests passed.
