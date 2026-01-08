
# Plan: Add ability to run a single test in BuildToolService

This plan outlines the steps to add a new method to `BuildToolService` that allows running a specific test case.

## Proposed Changes

### 1. `BuildToolService.java`
- Add a new method `runTest(String testName)` annotated with `@LLMDoc`.
- Implement `getSingleTestCommand(BuildTool tool, String testName)` to generate the appropriate command for Maven and Gradle.
  - Maven: `mvn test -Dtest=testName`
  - Gradle: `gradle test --tests testName`
  - Ant: Not supported for now (or a basic implementation if possible).

### 2. `BuildToolServiceTest.java`
- Add unit tests for `getSingleTestCommand` for Maven and Gradle.

## Implementation Steps
- [ ] Create this plan in `roxy/plans/add_run_single_test_to_build_tool_service.md`
- [ ] Modify `BuildToolService.java` to add `runTest` and `getSingleTestCommand`.
- [ ] Modify `BuildToolServiceTest.java` to add test cases.
- [ ] Run tests to ensure everything works as expected.

## Implementation Progress
- [x] Plan created.
- [x] `BuildToolService.java` updated.
- [x] `BuildToolServiceTest.java` updated.
- [x] Tests passed.

## Work Completed
- Added `runTest(String testName)` to `BuildToolService`.
- Implemented `getSingleTestCommand` for Maven and Gradle.
- Added unit tests in `BuildToolServiceTest` and verified they pass.
