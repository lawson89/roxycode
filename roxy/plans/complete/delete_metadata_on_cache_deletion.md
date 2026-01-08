# Plan: Delete Metadata on Cache Deletion

When a Gemini cache is deleted (either individually or all at once), the corresponding metadata file on disk should also be removed.

## Steps

1.  **Modify `ProjectCacheMetaService`**:
    *   Add a method `deleteProjectCacheMetaByGeminiId(String geminiId)` that scans the cache directory and deletes any metadata file where `geminiCacheId` matches.
    *   Add a method `deleteProjectCacheMetaByKey(String cacheKey)` that deletes the specific metadata file for a given cache key.
    *   Alternatively, a single method `deleteMetadata(String idOrKey)` that handles both.

2.  **Modify `GeminiCacheService`**:
    *   In `deleteCache(String cacheName)`, after successfully (or even if it fails, maybe?) attempting to delete from Gemini, call the new deletion method in `ProjectCacheMetaService`.
    *   Wait, if `deleteCache` is called with a `cacheKey` (like in `pushCache`), it should delete the metadata for that key.
    *   If `deleteCache` is called with a `geminiId` (like from the UI), it should delete the metadata for that ID.

3.  **Update `GeminiCacheService.deleteAllCaches()`**:
    *   This method already calls `deleteCache(name)`, so it should inherit the behavior.

4.  **Verification**:
    *   Create/update tests to ensure metadata is deleted when the cache is deleted.

## Implementation Progress

- [x] Add deletion methods to `ProjectCacheMetaService`
- [x] Update `GeminiCacheService.deleteCache` to call metadata deletion
- [x] Add/Update unit tests


## Status

Complete.