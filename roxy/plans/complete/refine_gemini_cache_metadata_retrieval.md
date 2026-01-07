# Plan: Refine Gemini Cache Metadata Retrieval

Add a parameterless `getProjectCacheMeta()` method to `GeminiCacheService` and move `ProjectCacheMeta` to the core beans package.

## Steps

- [ ] Move `ProjectCacheMeta.java` from `org.roxycode.cache` to `org.roxycode.core.beans`.
- [ ] Update imports in:
    - [ ] `GeminiCacheService.java`
    - [ ] `GenAIService.java`
    - [ ] `ProjectPackerService.java`
- [ ] Modify `GeminiCacheService.java`:
    - [ ] Add `public Optional<ProjectCacheMeta> getProjectCacheMeta()` which calls the existing version with `roxyProjectService.getProjectRoot()`.
- [ ] Update `GenAIService.java`:
    - [ ] Use the new parameterless `getProjectCacheMeta()` in `buildSystemContext`.
- [ ] Verify with tests.

## Implementation Progress

- [ ] Move `ProjectCacheMeta` to `org.roxycode.core.beans`.
- [ ] Add parameterless `getProjectCacheMeta` to `GeminiCacheService`.
- [ ] Update `GenAIService` and imports.
- [ ] Verify with tests.


**WORK COMPLETE**