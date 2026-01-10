# Goal
Prevent sending chat requests to the LLM when project caching is enabled but the cache has not been built yet, and notify the user about the progress.

# Proposed Changes
- Modify `ChatView.java` to inject `NotificationService`.
- Update `ChatView.onSend` to check if caching is enabled but metadata is missing.
- Show a notification and return early if the cache is still building.

# Implementation Steps
- [ ] Inject `NotificationService` into `ChatView`.
- [ ] Implement the cache check logic in `ChatView.onSend`.
- [ ] Verify that the notification appears when the cache is enabled but not ready.

# Implementation Progress
- [x] [DONE] Implementation complete
