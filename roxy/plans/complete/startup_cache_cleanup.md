
# Plan: Startup Cache Cleanup

This plan outlines the steps to implement a startup cleanup task for project cache metadata. The task will delete expired metadata files and metadata files that no longer have a corresponding Gemini cache online.

## Requirements
1.  On startup, scan the `roxy/.cache` folder for `ProjectCacheMeta` files (TOML).
2.  For each metadata file:
    *   Check if it has expired based on its `expirationTime`. If expired, delete it.
    *   If not expired, check if it has a valid Gemini cache online.
    *   If no valid Gemini cache is found online for the given Gemini ID, delete the metadata file.

## Proposed Changes

### 1. Update `ProjectCacheMetaService`
- Add `List<ProjectCacheMeta> listAllMetadata()` to return all metadata entries from the current project's cache directory.

### 2. Update `CacheManagementJob`
- Add a new method `cleanupCaches()` which:
    - Lists all local metadata via `projectCacheMetaService.listAllMetadata()`.
    - Fetches online caches via `geminiCacheService.listCaches()`.
    - Identifies online Gemini IDs.
    - Iterates through local metadata:
        - Deletes if expired (using `projectCacheMetaService.getSecondsUntilExpiration(meta) <= 0`).
        - Deletes if not found in the online list.
- Call `cleanupCaches()` at the beginning of the scheduled `manageCache()` method or as a separate `@PostConstruct` or scheduled task.
    - Since it involves a network call to Gemini, it's better to do it in the background.
    - I'll add it to the start of `manageCache()`.

## Implementation Steps
- [ ] Implement `listAllMetadata()` in `ProjectCacheMetaService`.
- [ ] Implement `cleanupCaches()` logic in `CacheManagementJob`.
- [ ] Call `cleanupCaches()` from `manageCache()`.
- [ ] Add unit tests for the new functionality.

## Implementation Progress
- [x] Initializing plan.
- [ ] Implementing `listAllMetadata()`.
