
# Plan: Fix unit tests leaking cache files

The goal of this plan is to ensure that unit tests do not leave files in the project's `.cache` folder.
Specifically, `ProjectCacheMetaServiceTest` is identified as a likely culprit because it writes metadata files to the filesystem using the real `Sandbox` root (which defaults to the project root).

## Steps

1.  **Modify `ProjectCacheMetaServiceTest`** to use a temporary directory for the sandbox root during tests.
    *   Inject the `Sandbox` component.
    *   Use `@BeforeEach` to set the sandbox root to a temporary directory provided by `@TempDir`.
    *   This will ensure that `roxyProjectService.getRoxyCacheDir()` (which uses `Sandbox.resolve`) points to a temporary location.
2.  **Verify other tests** in the same package to ensure they are also clean.
    *   `GeminiCacheServiceTest` seems fine but should be checked if it starts writing files.
3.  **Run all tests** to confirm that the `.cache` folder remains clean.

## Implementation Progress

- [x] Modify `ProjectCacheMetaServiceTest` to use `@TempDir` and `Sandbox.setRoot()`.
- [x] Ensure all tests in `ProjectCacheMetaServiceTest` are independent and clean up after themselves (or rely on `@TempDir` auto-cleanup).
- [x] Verify that `roxycode_cache_*.toml` files are no longer created in the project root after running tests.

## Completion Summary
Modified `ProjectCacheMetaServiceTest` to use a temporary directory for its sandbox root. This prevents it from writing cache metadata files to the real project's `.cache` folder during unit tests. Verified that tests pass and no new `.toml` files (except the project's own codebase cache) are created in `roxy/.cache`.
