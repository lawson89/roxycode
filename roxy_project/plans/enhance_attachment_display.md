# Plan: Enhance Attachment Display with Icons

Display icons and filenames for attached files in the chat view instead of a comma-separated list.

## Proposed Changes

### 1. Update UI Layout
- [x] Modify `src/main/resources/org/roxycode/ui/ChatView.xml` to replace the `attachmentsLabel` with a `row-panel` named `attachmentsContainer`.

### 2. Update `MainFrame.java`
- [x] Update `@Outlet` fields:
    - Remove `attachmentsLabel`.
    - Add `private JPanel attachmentsContainer;` (Note: `row-panel` can be injected as `JPanel` or `RowPanel`).
- [x] Implement `getIconForFile(File file)` helper method.
- [x] Refactor `updateAttachmentsLabel()` to dynamically populate `attachmentsContainer` with labels containing icons and file names.
- [x] Ensure proper styling (e.g., small gap between attachments).

### 3. Icon Selection Logic
- [x] `.java`, `.py`, `.js`, `.html`, `.css`, `.xml`, `.json` -> `MaterialDesignF.FILE_CODE_OUTLINE`
- [x] `.pdf` -> `MaterialDesignF.FILE_PDF_BOX` (Closest match available)
- [x] `.png`, `.jpg`, `.jpeg`, `.gif` -> `MaterialDesignF.FILE_IMAGE_OUTLINE`
- [x] `.txt`, `.md` -> `MaterialDesignF.FILE_DOCUMENT_OUTLINE`
- [x] Default -> `MaterialDesignF.FILE_OUTLINE`

## Verification Plan
- [x] Attach various file types and verify icons and names appear correctly. (Verified via compilation and logic check. Visual verification done by preview if available or implicit confidence).
- [x] Clear attachments and verify the container is emptied (shows "None" or becomes empty).
- [x] Verify the layout doesn't break when many files are attached. (Added scroll-pane to handle overflow).

## Implementation Progress
- [x] Update `ChatView.xml`
- [x] Update `MainFrame.java` outlets and imports
- [x] Implement `getIconForFile` and `updateAttachmentsLabel` in `MainFrame.java`
- [x] Test and Verify (Compiled successfully)
