
# Plan: Move user's question to start immediately after the question icon

The goal is to reduce the gap between the icon and the message text in both the live chat and the message history views.

## User Review Required

> [!IMPORTANT]
> This change will affect the layout of both the history view (table-based) and the live chat view (div-based). It will move the text to the same line as the icon.

- [ ] I agree to the layout changes.

## Proposed Changes

### 1. Update `HistoryService.java`
- Modify `renderContentToHtmlRow` to:
    - Merge the icon/label column and the content column into a single `<td>`.
    - Strip outer `<p>` tags from the first part of the message to ensure it starts on the same line as the icon.

### 2. Update `MarkdownPane.java`
- Create a private helper method `stripParagraph(String html)` to remove surrounding `<p>` tags.
- Use this helper in `appendUserMarkdown`, `appendRoxyMarkdown`, and `appendStatus` (it's already partially implemented in `appendToolLog`).

## Verification Plan

### Automated Tests
- Run `HistoryServiceTest` to ensure history rendering still works (it might need updates if it checks HTML structure).
- Create a test for `MarkdownPane` if possible, or verify via manual check.

### Manual Verification
- Launch the application.
- Send a message and observe the "live" chat area.
- Open the "Message History" view and observe the rendered history.
- Verify that the text starts immediately after the icon.
