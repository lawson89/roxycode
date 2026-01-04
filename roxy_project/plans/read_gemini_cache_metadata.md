# Plan: Read Gemini Cache Metadata

Modify `GeminiCacheService` to read the cache metadata from the file system if it exists.

## Proposed Changes

### 1. GeminiCacheService

- File: `src/main/java/org/roxycode/cache/GeminiCacheService.java`
- Add import for `java.util.Optional`.
- Implement `public Optional<CodebaseCacheMeta> getProjectCacheMeta()`:
    - Determine the current `cacheKey` using `codebasePackerService.getCacheKey(projectPath, user, currentModel)`.
    - Locate the metadata file in the project's cache directory: `roxyProjectService.getRoxyProjectCacheDir().resolve(cacheKey + ".toml")`.
    - If the file exists, use `objectMapper` to read it into a `CodebaseCacheMeta` object.
    - Return the metadata wrapped in an `Optional`.

### 2. Testing

- File: `src/test/java/org/roxycode/cache/GeminiCacheServiceTest.java` (New File)
- Create unit tests for `getProjectCacheMeta()`:
    - Test that it returns `Optional.empty()` when the metadata file is missing.
    - Test that it returns the correct `CodebaseCacheMeta` when the file exists and is valid.
    - Use Mockito to mock `SettingsService`, `CodebasePackerService`, `RoxyProjectService`, and `ObjectMapper`.

## Checklist

- [x] Add `getProjectCacheMeta` to `GeminiCacheService`.
- [x] Implement `getProjectCacheMeta` logic.
- [x] Create `GeminiCacheServiceTest.java`.
- [x] Verify implementation with tests.
- [x] Ensure all existing tests pass.

## Progress

- [x] Phase 1: Implementation
- [x] Phase 2: Testing
