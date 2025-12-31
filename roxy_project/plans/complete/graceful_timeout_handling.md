# Plan: Graceful Timeout Handling

The goal is to provide a more user-friendly error message when a timeout occurs during the Gemini API call.

## User Review Required

> [!IMPORTANT]
> None at this time.

## Proposed Changes

### `org.roxycode.ui.MainFrame`

- Update `onSend` method's catch block to specifically detect timeouts and show a better message.
- Inspect the exception chain to find `java.io.InterruptedIOException` or check for "timeout" in the message.

### `org.roxycode.core.GenAIService`

- (Optional) Potentially re-wrap or handle the exception there to provide a more specific custom exception, but updating `MainFrame` might be enough for now.

## Implementation Progress

- [ ] Add `isTimeout` helper to `MainFrame.java`
- [ ] Update `onSend` catch block in `MainFrame.java`
- [ ] Verify with a test or by simulation


### Automated Tests
- I don't have an easy way to trigger a real timeout in automated tests without mocking the `Client`.
- I can look at existing `GenAIServiceTest.java` to see if I can add a test case that simulates a timeout if I move the logic to `GenAIService`.

### Manual Verification
- Since I cannot easily trigger a real network timeout, I will rely on code analysis and perhaps a temporary short timeout to test.
- Check that the UI displays a helpful message like "The request timed out. Please check your internet connection or try again later."
