# Plan - Add Timestamps to Message History

Add timestamps to each message in the conversation history and display them in the Message History view.

## User Requirements
- Capture the timestamp when each message (user, model, or tool) is added to the history.
- Display the timestamp in the Message History view next to or below the role.

## Proposed Changes

### 1. Data Model (`org.roxycode.core.HistoryMessage`)
- Create a new `record` (or class) to wrap `com.google.genai.types.Content` and a `java.time.Instant`.

### 2. GenAIService (`GenAIService.java`)
- Change the internal `history` list to store `HistoryMessage` instead of `Content`.
- Update all methods that add to the history to include the current timestamp.
- Provide a way to retrieve the `List<Content>` for API calls (by mapping the `HistoryMessage` list).

### 3. HistoryService (`HistoryService.java`)
- Update `compactHistory` and related methods to handle `HistoryMessage`.
- When creating synthetic messages or updating the system prompt, use the current timestamp.

### 4. MainFrame Class (`MainFrame.java`)
- Update `updateMessageHistoryView()` to pass the `HistoryMessage` objects (which now contain timestamps).
- Update `renderContentToHtmlRow()` to display the formatted timestamp (e.g., `HH:mm:ss`).

## Implementation Steps
- [ ] Create `org.roxycode.core.HistoryMessage`.
- [ ] Refactor `GenAIService.java` to use `HistoryMessage`.
- [ ] Refactor `HistoryService.java` to use `HistoryMessage`.
- [ ] Update `MainFrame.java` to display timestamps.
- [ ] Update `HistoryServiceTest.java` and `GenAIServiceTest.java` (if they exist and fail).
- [ ] Test the changes.
