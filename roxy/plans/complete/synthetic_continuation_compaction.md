# Plan: Synthetic Continuation for History Compaction

When history compaction cannot find a "safe" split point (a pure user message) within a reasonable range, it can be forced to split inside a tool chain. To maintain history validity and context, a synthetic User message will be inserted to provide a summary and prompt the model to continue.

## Proposed Changes

### 1. `HistoryService.java`

- **Update `findSafeSplitIndex`**:
    - Remains focused on finding "pure" user messages.

- **Introduce `findForcedSplitIndex`**:
    - This method will be called if `findSafeSplitIndex` returns -1.
    - It looks for a `model` message near the `targetIndex`.
    - It prefers looking forward first, then backward.
    - If a `model` message is found, it returns that index.
    - If still nothing, it returns `targetIndex` as a final fallback.

- **Enhance `compactHistory`**:
    - If `findSafeSplitIndex` returns -1, call `findForcedSplitIndex` to get a `forcedSplitIndex`.
    - If a `forcedSplitIndex` is used:
        1.  Determine the split point.
        2.  Summarize messages from index `1` up to `forcedSplitIndex` (exclusive).
        3.  Create a synthetic User message: `Content` with role `"user"` and text: `"Continuing from previous context. Summary of progress so far: [Summary]. Please proceed with the task."`
        4.  Remove messages from index `1` to `forcedSplitIndex - 1`.
        5.  Insert the synthetic message at index `1`.
        6.  The message that was at `forcedSplitIndex` now follows the synthetic User message.

### 2. `HistoryServiceTest.java`

- **Add Test Case `testCompactionWithLongToolChain`**:
    - Simulate a history that exceeds `HISTORY_THRESHOLD` but contains only `model` (tool call) and `user` (tool response) pairs, with no "pure" user messages.
    - Verify that `compactHistory` successfully reduces the history size.
    - Verify that a synthetic message is present in the resulting history.
    - Verify that the history starts with a `user` message (at index 0/1) and alternates roles correctly.

## Implementation Details

-   **Synthetic Message Content**: The message should be clear that it's a continuation.
-   **Summary Inclusion**: Use the existing `generateSummary` logic.
-   **Role Alternation**: Index 0 (System) is User. Index 1 (Synthetic) is User. Index 2 (Original Model message) is Model.
    - If Gemini rejects `User -> User`, we might need to merge them. However, we'll start with separate messages as it's cleaner.

## Implementation Progress

- [x] Create Plan (this file)
- [x] Modify `HistoryService.java` to support forced split points and synthetic messages
- [x] Update `HistoryServiceTest.java` with a tool-chain-only test case
- [x] Verify build and tests pass

## Verification Plan

### Automated Tests
- Run `mvn test -Dtest=HistoryServiceTest` to ensure all compaction scenarios (safe and forced) work as expected.

### Manual Verification
- (Optional) Run the application and simulate a long tool-heavy session to see compaction in action in the logs.
