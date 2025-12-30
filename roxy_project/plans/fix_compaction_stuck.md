# Plan - Fix History Compaction Stuck at Beginning

The history compaction logic in `HistoryService` fails to compact when the only "safe" split point found is at index 1 (the beginning of the conversation). This often happens during long tool-use sequences. We should trigger a forced split with a synthetic message in this case.

## User Review Required

> [!IMPORTANT]
> The fix involves allowing compaction to proceed even if no "natural" user-message break point is found, by inserting a synthetic "Continuing..." message. This is already implemented for cases where NO split point is found, but not for cases where only the very first message is found.

## Proposed Changes

### `org.roxycode.core`

#### [HistoryService.java](src/main/java/org/roxycode/core/HistoryService.java)

- Modify `compactHistory` to treat `splitIndex <= 1` as a failure to find a useful safe split point, triggering `findForcedSplitIndex`.

## Implementation Progress

### 2024-05-15
- [x] Create reproduction test case in `HistoryServiceTest.java` as `testCompactHistory_StuckAtBeginning`.
- [x] Modify `HistoryService.java` to trigger `findForcedSplitIndex` when `splitIndex <= 1`.
- [x] Run all tests and verify fix.

## Verification Plan

### Automated Tests
- Create a test case in `HistoryServiceTest` (or create it if it doesn't exist) that simulates a long history with only one safe start node at index 1.
- Verify that compaction occurs and a synthetic message is inserted.

### Manual Verification
- This is harder to manually verify without a long tool-use session, but the unit test should suffice.
