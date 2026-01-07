# Plan - Investigate and Fix Empty Message Issue

The user reports occasional empty messages in Roxy. Based on initial investigation, there are several likely causes:
1. **History Redundancy/Confusion**: `GenAIService` adds the full system prompt (including context and task) as a User message in every `chat()` call without clearing history. This leads to multiple "System Prompts" in history and consecutive User messages, which confuses Gemini and can lead to empty responses or regurgitation of context.
2. **Compaction Role Mismatch**: `HistoryService` compaction can result in consecutive User messages (Index 0 is User, and if it splits at a User message, Index 1 is also User).
3. **Safety Filters**: The model might be blocking responses due to history messiness or content, returning empty parts.
4. **Persistent History**: History is never cleared, even when switching projects or tasks, leading to token bloat and confusion.

## Proposed Changes

### 1. Enhanced Logging in `GenAIService`
* Log the number of candidates returned by Gemini.
* Log the `finishReason` for the first candidate.
* Log the number of parts in the response.

### 2. Improve History Management in `GenAIService`
* Add a `clearHistory()` method.
* Call `clearHistory()` in `refreshKnowledge` (or when project root changes).
* Modify `chat()` to avoid adding the full system prompt every turn. 
    * Maintain index 0 as the "System/Context" message.
    * Only add the new user prompt as a new message.

### 3. Improve `HistoryService` to avoid consecutive User roles
* After compaction, if index 0 and index 1 are both User messages, consider how to handle it (e.g., ensure split index results in a Model message at index 1, or merge).
* Actually, the easiest way is to ensure `splitIndex` always points to a Model message if possible, or if it points to a User message, we must be careful.

### 4. UI Improvements
* Call `clearHistory()` when opening a new folder.

## Progress Checklist
- [x] Add logging to `GenAIService`
- [x] Implement `clearHistory` in `GenAIService`
- [x] Update `MainFrame` to clear history on project change
- [x] Refactor `GenAIService.chat` history handling
- [x] Update `HistoryService` to handle role alternation better
- [x] Verify fix with tests (Compilation verified)
