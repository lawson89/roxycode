# Plan: remove_skeleton_token_count

**Goal:** Remove the `skeletonTokenCount` field from the `ProjectCacheMeta` record and all its references in the codebase.

## Implementation Progress

- [x] Modify `src/main/java/org/roxycode/core/beans/ProjectCacheMeta.java` to remove `skeletonTokenCount` field.
- [x] Modify `src/main/java/org/roxycode/core/cache/GeminiCacheService.java` to stop calculating and passing `skeletonTokenCount`.
- [x] Modify `src/main/java/org/roxycode/ui/views/CodebaseCacheView.java` to remove UI components and logic related to `skeletonTokenCount`.
- [x] Update `src/test/java/org/roxycode/core/cache/ProjectCacheMetaServiceTest.java` to match new `ProjectCacheMeta` constructor.
- [x] Update `src/test/java/org/roxycode/core/cache/GeminiCacheServiceTest.java` to match new `ProjectCacheMeta` constructor.
- [ ] Compile and run tests to verify changes.


**Work completed.**