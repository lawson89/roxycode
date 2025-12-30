# Fix Summary Generation 400 Error

## 1. Analysis
- **Error**: `com.google.genai.errors.ClientException: 400 . Request contains an invalid argument.`
- **Location**: `org.roxycode.core.HistoryService.generateSummary`.
- **Cause**: The Gemini API requires alternating `User` and `Model` roles. `generateSummary` prepends a `User` message (summary prompt) to a slice of history. If that slice starts with a `User` message, the payload contains two consecutive `User` messages, causing a 400 error.
- **Secondary Issue**: `compactHistory` merges consecutive `User` messages at indices 0 and 1, but only once. If history has `User, User, User...`, it leaves consecutive users, which might later become the start of a slice.

## 2. Implementation Plan

### 2.1. Update `HistoryService.java`
- **Method `generateSummary`**:
  - Check if `messages.get(0)` has role `user`.
  - If yes, merge the summary prompt text ("Summarize the following...") with the first message's text.
  - If no (it's `model`), prepend the summary prompt as a separate `User` message (existing behavior).
- **Method `compactHistory`**:
  - Change `if (history.size() > 1 && isUserNode(0) && isUserNode(1))` to `while (...)`.
  - This ensures the history always starts with `User` followed by `Model`.

### 2.2. Verification
- Run existing `HistoryServiceTest`.
- Add a new test case `testGenerateSummary_MergeConsecutiveUser` to `HistoryServiceTest` to verify that `generateContent` is called with a merged message when the input starts with User.

## 3. Progress
- [x] Update `HistoryService.java`
- [x] Update `HistoryServiceTest.java`
- [x] Verify tests pass
