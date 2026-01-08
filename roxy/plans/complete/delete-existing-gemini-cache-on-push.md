
# Plan: Delete existing Gemini cache before pushing a new one

The goal is to ensure that when a new cache is pushed to Gemini, any existing cache for the same project, user, and model is deleted both from Gemini and from the local disk.

## Requirements
- Identify existing cache for the current project, user, and model before pushing a new one.
- Delete the existing cache from Gemini using its Gemini resource ID.
- Delete the corresponding local metadata file.
- Ensure the new cache is pushed successfully after deletion.

## Proposed Changes

### GeminiCacheService.java (`src/main/java/org/roxycode/core/cache/GeminiCacheService.java`)
- Modify `pushCache(Path projectPath)`:
    - Use `projectCacheMetaService.getProjectCacheMeta(projectPath)` to find existing metadata.
    - If metadata exists, call `deleteCache(meta.geminiCacheId())`.
    - Remove the existing incorrect attempt to call `deleteCache(cacheKey)`.

### ProjectCacheMetaService.java (`src/main/java/org/roxycode/core/cache/ProjectCacheMetaService.java`)
- The existing `deleteProjectCacheMetaByGeminiId(String geminiId)` and `deleteProjectCacheMetaByCacheKey(String cacheKey)` methods seem sufficient, but I should double check if they are used correctly in `GeminiCacheService.deleteCache`.

## Implementation Progress

- [x] Modify `GeminiCacheService.pushCache` to delete existing cache correctly.
- [x] Verify `deleteCache` in `GeminiCacheService` and `ProjectCacheMetaService`.
- [ ] Add unit tests to verify the behavior.
- [ ] Run existing tests to ensure no regressions.


## Implementation Progress
- [x] Modified `ProjectCacheMetaService` to add `deleteProjectCacheMetaByGeminiId` and `findByGeminiId`.
- [x] Modified `GeminiCacheService` to add `deleteCache` and update `pushCache` to delete existing cache before pushing.
- [x] Added unit tests in `GeminiCacheServicePushTest`.
- [x] Verified implementation with logs and tests.

**WORK COMPLETE**