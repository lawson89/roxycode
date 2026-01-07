
# Plan: Add refresh cache method to GeminiCacheService

The goal is to add a `refreshCache` method to `GeminiCacheService` that extends the cache lifetime by the TTL amount specified in `SettingsService`.

## Steps

1.  **Investigate Gemini SDK Update Capabilities**
    *   Confirm the correct method and configuration class for updating a cache's TTL in `com.google.genai`.
    *   Expected: `client.caches.update(cacheName, UpdateCachedContentConfig.builder().ttl(duration).build())`.

2.  **Modify `GeminiCacheService.java`**
    *   Add `public void refreshCache(String cacheName)` method.
    *   Retrieve the TTL from `settingsService.getCacheTTL()`.
    *   Call the Gemini API to update the cache.
    *   Add appropriate logging.

3.  **Update `ProjectCacheMeta` (Optional but recommended)**
    *   If we want to track when it was last refreshed, we might need to update the metadata file.
    *   However, the user only asked for the method. I'll stick to the method first.

4.  **Verification**
    *   Compile the project to ensure the API call is correct.
    *   (Optional) Add a unit test if possible, though mocking the Gemini Client might be complex.

## Implementation Progress

- [x] Investigate and confirm API
- [x] Implement `refreshCache` in `GeminiCacheService`
- [x] Verify compilation


Work is complete.