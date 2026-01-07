
# Plan: Fix Cache ID UI Update

The Cache ID shown in the UI (specifically the status bar in `MainFrame`) does not update immediately after a cache is pushed to Gemini. This is because `MainFrame` is not notified when the cache metadata changes.

## Proposed Changes

### 1. Core Services
- **ProjectCacheMetaService**:
    - Add a listener mechanism to notify interested parties when cache metadata changes.
    - Implement `addChangeListener(Runnable listener)`.
    - Trigger listeners in `writeProjectCacheMeta`, `deleteProjectCacheMetaByCacheKey`, `deleteProjectCacheMetaByGeminiId`, and `deleteAllMetadata`.

### 2. UI Components
- **MainFrame**:
    - Register a change listener with `ProjectCacheMetaService` during initialization.
    - The listener should call `updateCacheStatus()` to refresh the status bar.
- **CodebaseCacheView**:
    - (Optional) Use the listener to refresh the view, ensuring it stays in sync even if the cache is pushed from elsewhere or deleted.

## Implementation Steps

- [ ] Modify `ProjectCacheMetaService.java` to add listener support.
- [ ] Update `ProjectCacheMetaService.java` methods to trigger listeners.
- [ ] Modify `MainFrame.java` to register a listener in the `run()` method.
- [ ] (Optional) Modify `CodebaseCacheView.java` to use the listener for a more reactive UI.
- [ ] Verify the fix by pushing a cache and checking if the ID updates in both the view and the status bar.

## Progress
- [x] Initial plan created.
