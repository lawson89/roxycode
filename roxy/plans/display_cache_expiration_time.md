# Plan: Display Cache Expiration Time in UI

This plan outlines the steps to display the time until cache expiration in minutes and seconds in the `ChatView` UI, to the right of the Cache Id.

## User Requirements
- Display the time until cache expiration in minutes and seconds.
- Position the display to the right of the Cache Id.
- Update the time dynamically.

## Proposed Changes

### 1. Update UI Layout (`ChatView.xml`)
- Add a new `label` named `cacheExpiryLabel` to the bottom status bar in `ChatView.xml`.
- Position it to the right of `cacheIdLabel`.

### 2. Update `ChatView.java`
- Declare `cacheExpiryLabel` as an `@Outlet`.
- Initialize a `javax.swing.Timer` to update the cache status periodically (e.g., every 10 seconds or 1 second).
- Update `updateCacheStatus()` to:
    - Retrieve the current `ProjectCacheMeta`.
    - Calculate the remaining seconds using `projectCacheMetaService.getSecondsUntilExpiration(meta)`.
    - Format the seconds into "m:ss" format.
    - Set the text of `cacheExpiryLabel`.
- Start the timer in `init()` and potentially stop it when the component is destroyed (though in a Singleton JPanel it might live for the duration of the app).

### 3. Verification
- Verify that when a cache is active, the expiration time is displayed and updates periodically.
- Verify that if no cache is active or caching is disabled, the label is empty or hidden.

## Implementation Progress
- [x] Work complete.
- [x] Modify `ChatView.xml`
- [x] Update `ChatView.java` with `cacheExpiryLabel` and `Timer`
- [x] Implement time formatting and label update logic in `updateCacheStatus()`
- [x] Test the implementation
