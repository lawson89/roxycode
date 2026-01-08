
# Plan: Change Changes Area to JTextPane

The "Changes Area" in `ChangesView` currently uses `MarkdownPane`, which is a specialized `JTextPane` for rendering Markdown as HTML. The user wants it to be a standard `JTextPane`.

## Proposed Changes

### 1. Update `ChangesView.java`
- Change the type of `changesArea` from `MarkdownPane` to `JTextPane`.
- Remove the `MarkdownPane` import.
- Configure the `JTextPane` (e.g., set content type, font, etc.).
- Update `populateChangesList` to generate either plain text or simple HTML that `JTextPane` can handle natively without the `MarkdownPane` processing.
- Ensure `themeService.registerPane(changesArea)` still works or adapt it if necessary.

### 2. Adjust styling
- If using plain text, set a monospace font for the git status output.
- If using HTML, ensure the `HTMLEditorKit` is set up correctly.

## Steps

- [ ] Modify `ChangesView.java`
    - [ ] Change `MarkdownPane changesArea` to `JTextPane changesArea`.
    - [ ] Initialize `changesArea` as `new JTextPane()`.
    - [ ] In `init()`, configure `changesArea`:
        - [ ] `changesArea.setEditable(false);`
        - [ ] `changesArea.setContentType("text/html");` (if we stick with HTML)
        - [ ] `changesArea.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);`
    - [ ] Update `populateChangesList`:
        - [ ] Modify the `StringBuilder` to produce a format suitable for the new `changesArea`.
        - [ ] Replace `changesArea.setMarkdown(sb.toString())` with `changesArea.setText(sb.toString())`.
- [ ] Verify the UI looks correct and functional.
- [ ] Run tests to ensure no regressions.


## Implementation Progress
- [x] Basic JTextPane integration in `ChangesView.java`
- [x] Add context menu to `changesArea`
- [x] Update `ChangesView` to handle bold text correctly (HTML)
- [x] Verified with build and existing tests

**Work Complete**
