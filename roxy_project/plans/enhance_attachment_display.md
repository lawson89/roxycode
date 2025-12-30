# Plan: Enhance Attachment Display with Icons

Display icons and filenames for attached files in the chat view instead of a comma-separated list.

## Proposed Changes

### 1. Update UI Layout
- Modify `src/main/resources/org/roxycode/ui/ChatView.xml` to replace the `attachmentsLabel` with a `row-panel` named `attachmentsContainer`.

### 2. Update `MainFrame.java`
- Update `@Outlet` fields:
    - Remove `attachmentsLabel`.
    - Add `private JPanel attachmentsContainer;` (Note: `row-panel` can be injected as `JPanel` or `RowPanel`).
- Implement `getIconForFile(File file)` helper method.
- Refactor `updateAttachmentsLabel()` to dynamically populate `attachmentsContainer` with labels containing icons and file names.
- Ensure proper styling (e.g., small gap between attachments).

### 3. Icon Selection Logic
- `.java`, `.py`, `.js`, `.html`, `.css`, `.xml`, `.json` -> `MaterialDesignF.FILE_CODE_OUTLINE`
- `.pdf` -> `MaterialDesignF.FILE_PDF_BOX_OUTLINE`
- `.png`, `.jpg`, `.jpeg`, `.gif` -> `MaterialDesignF.FILE_IMAGE_OUTLINE`
- `.txt`, `.md` -> `MaterialDesignF.FILE_DOCUMENT_OUTLINE`
- Default -> `MaterialDesignF.FILE_OUTLINE`

## Verification Plan
- Attach various file types and verify icons and names appear correctly.
- Clear attachments and verify the container is emptied (shows "None" or becomes empty).
- Verify the layout doesn't break when many files are attached.

## Implementation Progress
- [x] Update `ChatView.xml`
- [x] Update `MainFrame.java` outlets and imports
- [x] Implement `getIconForFile` and `updateAttachmentsLabel` in `MainFrame.java`
- [x] Test and Verify (Compiled successfully, UI previewed)

