
# Plan: Generate and Include Code Skeleton in Cache

The goal is to modify `JavaAnalysisService` to output a skeleton of the codebase to a file in the `.cache` folder, and then have `CodebasePackerService` use this file when building the project cache.

## Checklist
- [ ] Modify `JavaAnalysisService` to support writing to a file.
- [ ] Modify `CodebasePackerService` to trigger skeleton generation and include its content.
- [ ] Verify implementation with a test.

## Proposed Changes

### 1. JavaAnalysisService
- Add `generateSkeletonToFile(Path sourcePath, Path outputPath)`.
- This method will ensure the parent directory exists, open a `BufferedWriter`, and call `generateSkeleton(sourcePath, writer)`.

### 2. CodebasePackerService
- In `streamFilesToToml`:
    - Define `skeletonFile = roxyProjectService.getRoxyProjectCacheDir().resolve("code_skeleton.txt")`.
    - Call `javaContextService.generateSkeletonToFile(rootPath, skeletonFile)`.
    - Read `skeletonFile` and write its content to the TOML writer under the `[java]` section.

## Verification Plan
- Run existing tests to ensure no regressions.
- Create a new test or modify `CodebasePackerServiceTest` to verify that `code_skeleton.txt` is created and its content is present in the generated TOML.
