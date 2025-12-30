# Plan: Render System Prompt as Markdown

The system prompt can be long and is formatted as Markdown. Currently, it's displayed in a `JTextArea` within a `JScrollPane`. We want to replace this with a `MarkdownPane` to provide better rendering and ensure it remains scrollable.

## Proposed Changes

### 1. UI Definition (`SystemPromptView.xml`)
- Give the `scroll-pane` a name (e.g., `systemPromptScrollPane`) so it can be accessed as an `@Outlet` in `MainFrame.java`.
- Remove the `text-area` from within the `scroll-pane`.

### 2. Main Controller (`MainFrame.java`)
- Update `systemPromptArea` field:
    - Change type from `JTextArea` to `MarkdownPane`.
    - Change it from an `@Outlet` to a `private final` field initialized at declaration (like `chatArea`).
- Add a new `@Outlet` for the scroll pane: `private JScrollPane systemPromptScrollPane;`.
- In `run()`, inject `systemPromptArea` into `systemPromptScrollPane.setViewportView()`.
- Update `onRefreshSystemPrompt()`:
    - Use `systemPromptArea.setMarkdown()` instead of `setText()`.
    - Handle the "Generating..." state appropriately for `MarkdownPane`.

### 3. Implementation Details
- Ensure `MarkdownPane` is updated when the theme changes (it already is in `applyTheme`).

## Progress
- [x] Modify `SystemPromptView.xml`
- [x] Modify `MainFrame.java`
- [x] Test the changes (Compilation success)
