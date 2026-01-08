
# Plan: ProjectCacheMetaService provides seconds until cache expires

The goal is to store the cache expiration time in `ProjectCacheMeta` and provide a way to retrieve the seconds remaining until expiration via `ProjectCacheMetaService`.

## Steps

1.  **Modify `ProjectCacheMeta.java`**
    - Add `String expiresAt` field to the record. This will store the ISO-8601 timestamp returned by the Gemini API.

2.  **Update `GeminiCacheService.java`**
    - In `pushCache`, use `response.expireTime()` to populate the `expiresAt` field in `ProjectCacheMeta`.
    - In `refreshCache`, update the local metadata file with the new expiration time.
      - Note: `getClient().caches.update` returns a `CachedContent` object. Use it to get the new `expireTime`.
      - I need to find the existing `ProjectCacheMeta` by `geminiId` (which is the `cacheName`) and update it.

3.  **Update `ProjectCacheMetaService.java`**
    - Add a method `long getSecondsUntilExpiration(ProjectCacheMeta meta)` that calculates the difference between `expiresAt` and the current time.
    - Add a method `Optional<ProjectCacheMeta> findByGeminiId(String geminiId)` to help `GeminiCacheService.refreshCache` update the metadata.

4.  **Verification**
    - Update/Add unit tests in `ProjectCacheMetaServiceTest.java` and `GeminiCacheServiceTest.java`.
    - Ensure all tests pass.

## Implementation Progress

- [ ] Modify `ProjectCacheMeta.java`
- [ ] Update `ProjectCacheMetaService.java`
- [ ] Update `GeminiCacheService.java`
- [ ] Verification

## Implementation Progress
- [x] Update `ProjectCacheMeta` record to include `expiresAt` field.
- [x] Implement `findByGeminiId(String geminiId)` in `ProjectCacheMetaService`.
- [x] Implement `getSecondsUntilExpiration(ProjectCacheMeta meta)` in `ProjectCacheMetaService`.
- [x] Update `GeminiCacheService.pushCache` to capture and store the `expiresAt` value from the Gemini API response.
- [x] Update `GeminiCacheService.refreshCache` to update the `expiresAt` value in the metadata after a successful refresh.
- [x] Added unit tests in `ProjectCacheMetaServiceTest` to verify expiration time calculation.
- [x] Updated existing tests to accommodate the new `ProjectCacheMeta` field.

**WORK COMPLETE**
