# Plan: Extract ProjectCacheMetaService

Extract all `ProjectCacheMeta` reading, writing, and lookup logic from `GeminiCacheService` and `ProjectPackerService` into a new `ProjectCacheMetaService`.

## Implementation Steps

### 1. Create ProjectCacheMetaService
- Create `org.roxycode.cache.ProjectCacheMetaService.java`.
- Move the following logic to it:
  - `getCacheKey(Path root, String user, String geminiModel)` (from `ProjectPackerService`).
  - `getProjectCacheMeta()` (from `GeminiCacheService`).
  - `getProjectCacheMeta(Path projectPath)` (from `GeminiCacheService`).
  - `writeProjectCacheMeta(ProjectCacheMeta codebaseCacheMeta)` (from `GeminiCacheService`).
- Inject necessary dependencies:
  - `SettingsService`
  - `RoxyProjectService`
  - `ObjectMapper` (named "toml")

### 2. Update GeminiCacheService
- Inject `ProjectCacheMetaService`.
- Remove `getProjectCacheMeta` methods and `writeProjectCacheMeta`.
- Update `pushCache` to use `ProjectCacheMetaService.getCacheKey` and `ProjectCacheMetaService.writeProjectCacheMeta`.

### 3. Update ProjectPackerService
- Inject `ProjectCacheMetaService`.
- Remove `getCacheKey` method.
- Update any internal calls to `getCacheKey` to use `ProjectCacheMetaService`.

### 4. Update GenAIService
- Inject `ProjectCacheMetaService`.
- Update `buildSystemContext` and `chat` to use `ProjectCacheMetaService.getProjectCacheMeta()` instead of `GeminiCacheService.getProjectCacheMeta()`.

### 5. Update CodebaseCacheView
- Inject `ProjectCacheMetaService`.
- Update `refresh` to use `ProjectCacheMetaService.getProjectCacheMeta()` instead of `GeminiCacheService.getProjectCacheMeta()`.

### 6. Verification
- Run existing tests to ensure no regressions.
- Create a unit test for `ProjectCacheMetaService`.

## Progress
- [ ] Create `ProjectCacheMetaService`
- [ ] Update `GeminiCacheService`
- [ ] Update `ProjectPackerService`
- [ ] Update `GenAIService`
- [ ] Update `CodebaseCacheView`
- [ ] Verification and Testing
