# Include Plan Context in Sliding Window

## Goal
Ensure that the current plan context is maintained even after the conversation history is trimmed by the sliding window mechanism.

## Proposed Changes
- Modify HistoryService.applySlidingWindow to accept planContext.
- Update GenAIService to pass the plan context to HistoryService.

## Implementation Steps
- [ ] Update HistoryService.java
- [ ] Update GenAIService.java
- [ ] Verify logic

## Implementation Progress
- [x] Update HistoryService.java [DONE]
- [x] Update GenAIService.java [DONE]
- [x] Verify logic [DONE]

## Agent Context

