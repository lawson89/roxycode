# Plan: Add Message History View

Add a new "Message History" menu item to the left-hand navigation that displays a scrollable list of all messages in the current conversation, ordered from most recent to oldest.

## User Review Required

> [!IMPORTANT]
> The message history will show the current conversation's messages, including system context (at index 0), user messages, model responses, and tool calls/responses.

- Is the "most recent to oldest" order preferred over "oldest to newest" (which is how chat usually flows)? *The task explicitly requested most recent to oldest.*
- For "file" types, I will check for `inlineData` in the message parts.

## Proposed Changes

### UI Changes

#### `MainFrame.xml`
- Add a new `button` with name `navMessageHistoryButton` and text "Message History" to the side menu.

#### `MessageHistoryView.xml` (New)
- Create a new Sierra XML file for the Message History view.
- It should contain a `scroll-pane` that will hold the message list.
- The list will be a `column-panel` where message entries are added dynamically.

#### `MainFrame.java`
- Add `@Outlet` for `navMessageHistoryButton`.
- Add `@Outlet` for `viewMessageHistory` (the new view).
- Add `@Outlet` for `messageHistoryContainer` (the panel inside the scroll pane in `MessageHistoryView.xml`).
- Update `run()` to load `MessageHistoryView.xml`.
- Update `initListeners()` to handle `navMessageHistoryButton` clicks.
- Update `showView()` to handle "MESSAGE_HISTORY" view.
- Implement `updateMessageHistoryView()` to populate the container with message cards.
- Add an icon for the "Message History" button in `initIcons()`.

### Core Logic

- Use `genAIService.getHistory()` to get the messages.
- Iterate in reverse order to show most recent first.
- For each `Content`:
    - Determine role (User, Model, Function).
    - Iterate over `Part`s:
        - If `text`, show abbreviated text (500 chars).
        - If `inlineData`, show "File: [filename]" (if filename is available, otherwise just "File").
        - If `functionCall`, show "Tool Call: [name]".
        - If `functionResponse`, show "Tool Response: [name]".
    - Format each message as a "card" (panel with border and padding).

## Implementation Progress

- [x] UI Changes: Added `navMessageHistoryButton` to `MainFrame.xml`.
- [x] Create `MessageHistoryView.xml` with scrollable container.
- [x] MainFrame.java: Added outlets, listeners, and `updateMessageHistoryView()` logic.
- [x] Integrated "Message History" into the `mainContentStack`.
- [x] Verified build success.
- [x] Reversed history order in `updateMessageHistoryView()`.

## Verification Plan

### Automated Tests
- Since this is primarily UI, manual verification is preferred, but I can check if `GenAIService.getHistory()` returns expected content.

### Manual Verification
1. Start RoxyCode.
2. Click "Message History" in the sidebar.
3. Verify the view is empty initially.
4. Send a message in Chat.
5. Go back to Message History and verify the message (and any tool calls/responses) are visible.
6. Verify the order (most recent at the top).
7. Verify truncation of long messages.
8. Verify icons and formatting.
9. Attach a file, send, and verify it shows up correctly in history.
