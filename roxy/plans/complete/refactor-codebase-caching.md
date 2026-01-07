# Refactor Codebase Caching

Refactor the codebase caching mechanism to use the Java skeleton instead of full file content.
This involves moving cache logic to a dedicated service and updating the `GenAIService` to use it.

## Steps

- [x] Create `JavaAnalysisService` for skeleton generation
- [x] Create `GeminiCacheService`, `ProjectCacheMetaService`, and `ProjectPackerService` in `org.roxycode.core.cache`
- [x] Update `GenAIService` to use the new cache services
- [x] Remove old `org.roxycode.cache` package and unused files
- [x] Write unit tests
- [x] Verify all tests pass

## Implementation Progress

- [x] Initial research
- [x] Created `JavaAnalysisService` for skeleton generation
- [x] Created `GeminiCacheService`, `ProjectCacheMetaService`, and `ProjectPackerService` in `org.roxycode.core.cache`
- [x] Updated `GenAIService` to use the new cache services
- [x] Removed old `org.roxycode.cache` package and unused files
- [x] Verified all tests pass

**WORK COMPLETE**
