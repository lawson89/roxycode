# Plan - Refactor Message History to HTML Table

Modify the message history view to use a single `MarkdownPane` (which is a `JTextPane`) and render the conversation as a nicely formatted HTML table, instead of using multiple `JPanel`s.

## User Requirements
- Modify `addMessageToHistoryUI` in `MainFrame`.
- Use an HTML table compatible with `JTextPane`.
- Replace the use of `JPanel`s for messages.

## Proposed Changes

### 1. UI Definition (`MessageHistoryView.xml`)
- Remove the `column-panel` named `messageHistoryContainer`.
- We will inject a `MarkdownPane` into `messageHistoryScrollPane` programmatically, similar to `chatArea`.

### 2. MainFrame Class (`MainFrame.java`)
- Add a new field: `private final MarkdownPane messageHistoryArea = new MarkdownPane();`.
- Update `@Outlet`s:
    - Remove `messageHistoryContainer`.
    - Add `@Outlet private JScrollPane messageHistoryScrollPane;`.
- In `run()`:
    - Set `messageHistoryArea` as the viewport view for `messageHistoryScrollPane`.
- Update `updateMessageHistoryView()`:
    - Clear `messageHistoryArea`.
    - Build the HTML table header.
    - Iterate through history and call a modified version of `addMessageToHistoryUI` (or equivalent) to append rows.
    - Close the HTML table and set it to `messageHistoryArea`.
- Update `addMessageToHistoryUI(Content content)`:
    - It should now probably take a `StringBuilder` and append HTML rows to it.
    - Handle roles with colors (User: #4080FF, Model: #40C080, Other: Gray).
    - Render text parts, tool calls, tool responses, and inline data.

### 3. Styling
- Use a table with two columns: Role and Content.
- Use background colors for headers or alternate rows to make it "nicely formatted".
- Ensure compatibility with `JTextPane`'s limited HTML support (use basic tags and attributes).

## Implementation Steps
- [x] Create this plan (Done).
- [x] Modify `MessageHistoryView.xml`.
- [x] Modify `MainFrame.java` fields and initialization.
- [x] Refactor `addMessageToHistoryUI` and `updateMessageHistoryView`.
- [x] Test the changes.
