# Plan: Add Stop Chat Button

We need to provide a way for the user to stop a long-running chat session. This involves adding a button to the UI and implementing the cancellation logic in `GenAIService`.

## User Review Required

> [!IMPORTANT]
> The "Stop" button will be placed next to the "Send" button in the chat interface.

- No critical items requiring user attention.

## Proposed Changes

### Core

#### [GenAIService.java](src/main/java/org/roxycode/core/GenAIService.java)

- [x] Add `private volatile boolean stopRequested = false;`
- [x] Add `public void stopChat() { stopRequested = true; }`
- [x] In `chat(...)`:
    - [x] Reset `stopRequested = false` at the start.
    - [x] Check `stopRequested` at the start of each iteration in the `while` loop.
    - [x] Check `stopRequested` before and after tool executions.
    - [x] If `stopRequested` is true, return a "Chat stopped by user." message.

### UI

#### [MainFrame.xml](src/main/resources/org/roxycode/ui/MainFrame.xml)

- [x] Add a `JButton` with `id="stopButton"` and an appropriate icon (e.g., `mdi2-stop`).
- [x] It should be initially disabled.

#### [MainFrame.java](src/main/java/org/roxycode/ui/MainFrame.java)

- [x] Bind `stopButton` using `@Inject`.
- [x] In `onSend(...)`:
    - [x] Disable `sendButton`.
    - [x] Enable `stopButton`.
    - [x] In the `finally` block (or after the chat call completes/fails), restore button states.
- [x] Add `onStopChat(ActionEvent e)` method:
    - [x] Call `genAIService.stopChat()`.
    - [x] Disable `stopButton`.
- [x] Initialize `stopButton` listener in `initListeners()`.

## Verification Plan

### Automated Tests
- N/A for UI components, but I will check for compilation errors.

### Manual Verification
- Launch the application.
- Start a chat that triggers a long-running tool or multiple turns.
- Click the "Stop" button.
- Verify that the chat stops and the buttons return to their initial state.
