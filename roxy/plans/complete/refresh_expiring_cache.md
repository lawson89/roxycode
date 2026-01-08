
# Plan: Refresh expiring cache

The goal is to ensure that the cache is refreshed if it's within 5 minutes of expiring, provided that caching is enabled.

## Proposed Changes

### 1. CacheManagementJob
- Verify that `manageCache()` checks if caching is enabled.
- Verify that `manageCache()` calculates the time until expiration.
- If the remaining time is less than 300 seconds (5 minutes), call `geminiCacheService.refreshCache()`.

### 2. Unit Tests
- Update `CacheManagementJobTest` to include a test case where the cache is NOT expiring soon and verify it is NOT refreshed.
- Ensure existing test `testManageCache_WhenExpiringSoon` is correct.

## Implementation Progress

### Step 1: Verification
- [x] Check `CacheManagementJob.java` for existing logic. (Logic already exists)
- [x] Check `GeminiCacheService.java` for `refreshCache` implementation. (Logic already exists)
- [x] Check `ProjectCacheMetaService.java` for `getSecondsUntilExpiration` implementation. (Logic already exists)

### Step 2: Testing
- [x] Add test case to `CacheManagementJobTest` for "not expiring soon".
- [x] Run all tests in `CacheManagementJobTest`.

## Conclusion
- Work is complete. The logic was already present in the codebase and has been verified with existing and new unit tests.
