
# Plan: Multi-line User Input Support

The user wants the chat input field to support text wrapping and allow inserting new lines using `Alt-Enter`, while keeping `Enter` as the send command.

## Proposed Changes

### 1. Update `ChatView.java`

- In `init()` or `initListeners()`, configure `inputField` (which is a `JTextArea`) to:
    - Enable line wrapping (`setLineWrap(true)`).
    - Enable word wrapping (`setWrapStyleWord(true)`).
- Update key bindings for `inputField`:
    - Ensure `ENTER` is bound to `onSend`.
    - Bind `ALT + ENTER` to insert a newline.

## Verification Plan

### Automated Tests
- Since this is a UI change, automated testing might be limited without a full UI test framework. 
- I will check if there are existing UI tests I can leverage or if I should add a simple test case if possible.
- Actually, `ChatView` is a Micronaut singleton and uses Sierra. Testing it programmatically might require mocking dependencies.

### Manual Verification
- Launch the application.
- Type a long message to see if it wraps.
- Press `Alt-Enter` to see if a newline is inserted.
- Press `Enter` to see if the message is sent.

## Implementation Progress
- [x] Enable line wrapping in `ChatView.java`.
- [x] Bind `Alt-Enter` to newline in `ChatView.java`.
- [x] Verify changes (Tests passed, manual verification required by user).
