
# Plan: Display Cache Status in Status Bar

Add a bottom status bar to the MainFrame to display if project caching is enabled and the current cache ID.

## User Requirements
- Display in the bottom status bar if project caching is enabled.
- If enabled, display the value of the current cache ID.

## Proposed Changes

### 1. Update UI Layout
- Modify `src/main/resources/org/roxycode/ui/MainFrame.xml` to add a `row-panel` at the bottom of the main `column-panel`.
- This new `row-panel` will serve as a status bar.
- Add two labels: `cacheStatusLabel` and `cacheIdLabel`.
- Use consistent styling with other status-like labels (e.g., small font, muted color).

### 2. Update MainFrame Controller
- Modify `src/main/java/org/roxycode/ui/MainFrame.java`.
- Inject `ProjectCacheMetaService` to retrieve the current cache metadata.
- Add `@Outlet` for `cacheStatusLabel` and `cacheIdLabel`.
- Implement a method `updateCacheStatus()` that:
    - Checks if caching is enabled via `settingsService.isCacheEnabled()`.
    - Retrieves the current cache metadata using `projectCacheMetaService.getProjectCacheMeta()`.
    - Updates the labels accordingly.
- Call `updateCacheStatus()` in:
    - `run()` (after UI is loaded).
    - `onProjectChange()` to update when switching projects.
- Consider adding a timer or refreshing when views change to keep it up to date if it's updated elsewhere.

### 3. Ensure Update on Cache Changes
- Since `CodebaseCacheView` can trigger a cache push, we need a way to notify `MainFrame`.
- I will add a simple listener mechanism or just refresh the cache status when `CodebaseCacheView` completes its action.
- Actually, adding a method to `MainFrame` that can be called by other components is one way, but since we are using DI, let's see.
- Maybe a simpler approach: `MainFrame` can refresh the cache status every time it's made visible or via a periodic timer (e.g., every 5 seconds). Given this is a desktop app, a timer is acceptable.

## Implementation Steps

### Step 1: Modify MainFrame.xml
- [ ] Add the bottom status bar `row-panel`.

### Step 2: Modify MainFrame.java
- [ ] Add `ProjectCacheMetaService` injection.
- [ ] Add outlets for the new labels.
- [ ] Implement `updateCacheStatus()`.
- [ ] Update `run()` and `onProjectChange()` to call `updateCacheStatus()`.
- [ ] Add a periodic timer to refresh the cache status.

### Step 3: Verification
- [ ] Launch the application.
- [ ] Verify that "Cache: Enabled" or "Cache: Disabled" is visible at the bottom.
- [ ] Verify that the Cache ID is displayed when a cache is pushed.
- [ ] Verify that switching projects updates the status bar.

## Implementation Progress
- [x] Step 1: Modify MainFrame.xml
- [x] Step 2: Modify MainFrame.java
- [x] Step 3: Verification

**WORK COMPLETE**
