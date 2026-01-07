# Plan: Disable Input when Roxy is Processing

This plan outlines the changes needed to disable the input field and related buttons while Roxy is processing a request.

## User Review Required

> [!IMPORTANT]
> I will disable the following components when Roxy is processing:
> - `inputField` (JTextArea)
> - `sendButton` (JButton) - already handled for disabling, but need to ensure it's synced.
> - `attachButton` (JButton)
> - `clearAttachmentsButton` (JButton)

## Proposed Changes

### UI

#### [MainFrame.java](src/main/java/org/roxycode/ui/MainFrame.java)

- Update `onSend` method:
    - Disable `inputField`, `attachButton`, and `clearAttachmentsButton` before starting the chat thread.
    - Re-enable `inputField`, `attachButton`, and `clearAttachmentsButton` in the `finally` block of the chat thread.

## Verification Plan

### Automated Tests
- N/A (UI interaction is hard to test automatically without complex setup, but I can check if the code compiles).

### Manual Verification
- Launch the application.
- Type a message and click "Send".
- Verify that the input field, "Attach" button, and "Clear" (attachments) button are disabled while Roxy is "Thinking" or executing tools.
- Verify that they are re-enabled once Roxy provides the final response or an error occurs.
- Verify that the "Stop" button remains enabled during processing.

## Implementation Progress

### Step 1: Update MainFrame.java
- [x] Implement UI component disabling in `onSend`.
- [x] Implement UI component re-enabling in `onSend`'s `finally` block.

