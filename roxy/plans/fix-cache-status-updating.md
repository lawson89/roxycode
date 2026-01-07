
# Plan: Fix Cache Status Not Updating

The "Cache: Unknown" label is defined in `ChatView.xml`, but the logic to update it and the `@Outlet` annotations are in `MainFrame.java`. Since Sierra binds outlets based on the XML file loaded for a specific class, `MainFrame` cannot bind labels that are in `ChatView`'s XML.

## User Requirements
- The cache status field should update correctly instead of showing "Cache: Unknown".

## Proposed Changes

### 1. Update `ChatView.java`
- Add `@Outlet` fields for `cacheStatusLabel` and `cacheIdLabel`.
- Inject `ProjectCacheMetaService`.
- Implement `updateCacheStatus()` method in `ChatView`.
- Call `updateCacheStatus()` in `init()` and `updateChatStats()`.

### 2. Update `MainFrame.java`
- Remove `cacheStatusLabel` and `cacheIdLabel` outlets.
- Remove `updateCacheStatus()` implementation or delegate it to `chatView`.
- In `onProjectChange`, call `chatView.updateCacheStatus()`.
- Fix the weird Timer placement in the busy listener.

### 3. Verification
- Run the application.
- Change project or toggle cache settings.
- Verify the "Cache: Enabled/Disabled" label updates in the Chat view.

## Progress
- Work Complete.
- [ ] Implement changes in `ChatView.java`
- [ ] Implement changes in `MainFrame.java`
- [ ] Verify fix
