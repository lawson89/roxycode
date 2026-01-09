# Goal
Extract redundant rate-limiting logic in GenAIService.chat into a dedicated private method.

# Proposed Changes
- Add a private method `Optional<String> waitForRateLimit(long turnStart, long minTurnDurationMillis)` to GenAIService.
- The new method will encapsulate the calculation of elapsed time and the Thread.sleep call, handling InterruptedException.
- Update GenAIService.chat to use this new method in both instances where rate limiting is applied.

# Implementation Steps
- [ ] Analyze GenAIService.java for method placement.
- [ ] Implement the `waitForRateLimit` method.
- [ ] Refactor the `chat` method loop to use `waitForRateLimit` when tool calls occur.
- [ ] Refactor the `chat` method end-of-loop to use `waitForRateLimit` before returning the final response.
- [ ] Verify compilation.

# Implementation Progress
- [x] x Analyze GenAIService.java for method placement.
- [x] x Implement the `waitForRateLimit` method.
- [x] x Refactor the `chat` method loop to use `waitForRateLimit` when tool calls occur.
- [x] x Refactor the `chat` method end-of-loop to use `waitForRateLimit` before returning the final response.
- [x] x Verify compilation.
