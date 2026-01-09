
# Plan: Cleanup Expired and Orphaned Cache Metadata

This plan outlines the steps to implement a startup cleanup process for project cache metadata files. It ensures that expired metadata files and those pointing to non-existent online Gemini caches are removed.

## Requirements
- On startup, scan the `.cache` folder for all `ProjectCachemeta` files.
- Delete any metadata files that have expired.
- For remaining metadata files, check if their corresponding Gemini cache exists online.
- Delete any metadata files that do not have a valid online Gemini cache.

## Implementation Steps

### 1. Update ProjectCacheMetaService
- [x] Add `listAllMetadata()` method to retrieve all `ProjectCacheMeta` objects from the `.cache` directory.
- [x] Ensure necessary imports (`java.util.List`, `java.util.ArrayList`) are present.

### 2. Update CacheManagementJob
- [x] Add a `cleanupCaches()` method to perform the cleanup logic.
- [x] In `cleanupCaches()`:
    - Retrieve all local metadata.
    - Retrieve all online Gemini caches using `geminiCacheService.listCaches()`.
    - Identify expired metadata (using `projectCacheMetaService.getSecondsUntilExpiration(meta) <= 0`).
    - Identify orphaned metadata (Gemini ID not found in online caches).
    - Delete identified metadata files using `projectCacheMetaService.deleteProjectCacheMetaByCacheKey(meta.cacheKey())`.
- [x] Add a flag `cleanupDone` to ensure cleanup runs only once per application lifecycle.
- [x] Modify `manageCache()` to call `cleanupCaches()` on its first execution.
- [x] Ensure necessary imports (`java.util.List`, `java.util.Set`, `java.util.stream.Collectors`, `com.google.genai.types.CachedContent`) are present.

### 3. Verification
- [x] Add unit test `testListAllMetadata` in `ProjectCacheMetaServiceTest`.
- [x] Add unit test `testManageCache_PerformsCleanupOnFirstRun` in `CacheManagementJobTest`.
- [x] Run all tests to ensure correctness and no regressions.

## Implementation Progress
- [x] ProjectCacheMetaService updated.
- [x] CacheManagementJob updated.
- [x] Unit tests added and verified.
- [x] Work complete.
