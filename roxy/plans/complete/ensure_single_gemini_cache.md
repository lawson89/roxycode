
# Plan: Ensure Single Gemini Cache per Project/Model

Somehow multiple Gemini caches are being created for the same project/model combination. This plan aims to ensure that only one live Gemini cache exists per project/model/user by cleaning up any existing caches with the same display name before pushing a new one.

## Analysis
- Currently, `GeminiCacheService.pushCache` only checks local metadata to find and delete an existing cache.
- If local metadata is missing or out of sync, `pushCache` will create a new cache on Gemini without deleting the old one.
- The `displayName` of the cache is set to a unique `cacheKey` (derived from project path, user, and model).
- We can use the Gemini API to list all caches and find those with the same `displayName`.

## Proposed Changes

### 1. Update `GeminiCacheService.pushCache`
- Synchronize the method to prevent concurrent pushes.
- Before pushing a new cache, list all caches from Gemini.
- Filter the list for caches whose `displayName` matches the current `cacheKey`.
- Delete all such caches found on Gemini.
- Ensure local metadata for these caches is also removed.

### 2. Update `GeminiCacheService.deleteCache`
- Fix the redundant/incorrect metadata deletion call.
- It should use `geminiId` for both Gemini deletion and local metadata cleanup.

### 3. Add Unit Tests
- Add a test case to `GeminiCacheServiceTest` (or a new test) that simulates pushing a cache when one or more already exist on Gemini with the same display name.

## Implementation Steps
- [ ] Modify `GeminiCacheService.java`:
    - [ ] Synchronize `pushCache(Path projectPath)`.
    - [ ] Add logic to `pushCache` to list and delete existing Gemini caches with the same `displayName`.
    - [ ] Refactor existing deletion logic in `pushCache` to use the new cleanup logic.
    - [ ] (Optional) Improve `deleteCache` method to be more robust.
- [ ] Verify changes with a test.
- [ ] Run existing tests to ensure no regressions.

## Progress
- [ ] Plan created.
- [ ] Implementation started.
- [ ] Implementation completed.
- [ ] Tests passed.
