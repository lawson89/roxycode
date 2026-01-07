
# Plan: Refactor Message History View to use Styled JTextPane

The goal is to improve performance of the Message History view by replacing the HTML-based `MarkdownPane` with a standard `JTextPane` using `StyledDocument`.

## Proposed Changes

### 1. HistoryService
- Add a method `renderContentToStyledDocument(Content content, StyledDocument doc, boolean isDarkTheme)`.
- This method will append the role and content parts to the document with appropriate styles (colors, bold for roles, etc.).
- It will handle text, function calls, function responses, and inline data.
- It will truncate long text as before.

### 2. MessageHistoryView
- Change the type of `messageHistoryArea` from `MarkdownPane` to `JTextPane`.
- In `init()`, configure the `JTextPane`:
    - Set it as non-editable.
    - Set the font.
- Update `refresh()`:
    - Clear the `StyledDocument`.
    - Iterate through the history and call `historyService.renderContentToStyledDocument`.
    - Add separators between messages.

### 3. ThemeService
- The current `applyTheme` only calls `updateStyle()` on `MarkdownPane`.
- If we use a standard `JTextPane` with styled document, the styles (colors) might need updating when the theme changes.
- However, since we re-render everything on `refresh()`, and we can trigger a refresh on theme change if needed, it might be fine.
- Alternatively, we can make `MessageHistoryView` listen for theme changes or just re-apply styles.

## Implementation Details

- Use `javax.swing.text.StyleContext` and `javax.swing.text.Style` to define styles for User, Model, and System roles.
- Role headers will be bold and colored.
- Content will be regular text.
- Tool/Function calls and responses will be italicized.

## Progress Checklist
- [ ] Create `renderContentToStyledDocument` in `HistoryService`
- [ ] Refactor `MessageHistoryView` to use `JTextPane`
- [ ] Implement styled rendering in `MessageHistoryView.refresh()`
- [ ] Test the new view for performance and correctness
