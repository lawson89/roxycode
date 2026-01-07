
# Plan: Fix GitService Path Conversion Error

The goal is to fix a `TypeError` when calling `gitService` methods from JavaScript. The error occurs because GraalJS cannot automatically convert a JS string (TruffleString) to a `java.nio.file.Path`.

## Problem
- `GitService` methods (e.g., `getCurrentBranch`, `getStatus`, etc.) expect a `java.nio.file.Path` object.
- When called from JS: `gitService.getCurrentBranch("/path/to/project")`, GraalJS fails to convert the string to `Path`.

## Proposed Solution
1.  Modify `GitService.java` to inject the `Sandbox` service.
2.  Update method signatures in `GitService` to accept `Object projectRoot` instead of `Path projectRoot`.
3.  Implement a private `resolvePath(Object projectRoot)` method in `GitService` that:
    - If `projectRoot` is already a `Path`, returns it.
    - If `projectRoot` is a `String`, resolves it using `sandbox.resolve()`.
    - Handles `null` or other types by falling back to `sandbox.getRoot()`.
4.  Update all methods in `GitService` to use this helper method.

## Implementation Progress
- [x] Modify `GitService.java` to inject `Sandbox` and accept `Object` for `projectRoot`.
- [x] Update `GitServiceTest.java` to test passing a `String` as a path.
- [x] Verify compilation and test execution.

## Proposed Changes

### `src/main/java/org/roxycode/core/tools/service/GitService.java`
- Add `@Inject private Sandbox sandbox;` (or use constructor injection).
- Update `getCurrentBranch`, `getStatus`, `diff`, and `log` methods.

## Verification Plan
1.  Compile the project.
2.  Run a JS script that calls `gitService.getCurrentBranch(".")` or similar with a string argument.
3.  Verify that `MainFrame` still works (it passes a `Path` object).
4.  Run existing tests for `GitService`.
