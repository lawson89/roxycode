# Plan: Improve History Compaction for Tool-Heavy Conversations

The current history compaction strategy in `HistoryService` is too aggressive for Gemini (threshold of 15 messages) and struggles to find safe split points when many tool calls are present.

## Proposed Changes

### 1. Increase Thresholds
Gemini has a massive context window (1M+ tokens). A threshold of 15 messages is extremely small and causes frequent compaction attempts.
- Update `HISTORY_THRESHOLD` from 15 to 50.
- Update `CHUNK_SIZE` from 6 to 15.

### 2. Improve Split Point Detection in `HistoryService`
Currently, `findSafeSplitIndex` only looks forward. We should also look backward to find the nearest "clean" break point if the target index is in the middle of a tool chain.

- Modify `findSafeSplitIndex` to:
    1. Search forward from `targetIndex` for a "safe start node" (pure user message).
    2. If not found, search backward from `targetIndex` down to index 1.
    3. Ensure we don't return an index that would result in empty compaction.

### 3. Better Logging
- Add more context to the warning when no split point is found, explaining *why* (e.g., "History consists entirely of tool calls and model responses").

## Implementation Progress
- [x] Create Plan (this file)
- [x] Modify `HistoryService.java` with new thresholds and improved search logic
- [x] (Optional) Add unit tests for `HistoryService` to verify split point logic
- [x] Verify build and basic functionality

## Success Criteria
- Gemini can perform long sequences of tool calls without "Could not find a safe split point" warnings (until absolutely necessary).
- Compaction still works when "pure" user messages are present.
- No regression in conversation flow.
