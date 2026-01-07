
# Plan: Add Copy Context Menu to Chat

Allow users to right-click on selected text in the chat response window (and other markdown panes) to copy text to the clipboard.

## Proposed Changes

### UI Components

#### [MarkdownPane.java]
- Initialize a `JPopupMenu` in the constructor.
- Add a "Copy" `JMenuItem` to the popup menu.
- Set the action for the "Copy" item to trigger the standard copy operation (`this.copy()`).
- Register the popup menu using `setComponentPopupMenu(popupMenu)`.
- Ensure the "Copy" menu item is only enabled when there is a selection. We can use a `PopupMenuListener` to update the enabled state before showing.

## Verification Plan

### Automated Tests
- Since this is a UI interaction, I will add a simple unit test for `MarkdownPane` to ensure the context menu is correctly initialized and contains the "Copy" item.
- Note: Testing actual clipboard interaction is difficult in headless environments, so manual verification will be key.

### Manual Verification
1. Open RoxyCode.
2. Go to the Chat view.
3. Send a message to get a response.
4. Select some text in the response.
5. Right-click the selected text.
6. Verify "Copy" appears in the menu.
7. Click "Copy".
8. Paste the text into an external editor to verify it was copied correctly.
9. Verify the same works in "System Prompt" and "Message History" views.

## Implementation Progress
- [x] Create/Update MarkdownPane with context menu
- [x] Add unit test for MarkdownPane context menu
- [x] Manual verification
