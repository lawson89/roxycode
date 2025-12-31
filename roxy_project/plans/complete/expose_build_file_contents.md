# Expose Build File Contents

## Goal
Expose the contents of the detected build file (pom.xml, build.gradle, etc.) through `BuildToolService`.

## Motivation
To allow other services or the UI to inspect the build configuration directly.

## Implementation Steps
- [x] Add `getBuildFileContents()` method to `BuildToolService.java`.
- [x] Implement logic to read the file based on `detect()` logic.
    - If Maven: read `pom.xml`.
    - If Gradle: read `build.gradle` or `build.gradle.kts`.
    - If Ant: read `build.xml`.
- [x] Handle exceptions (e.g. IOException) gracefully.
- [x] Add unit tests in `BuildToolServiceTest.java`.
    - [x] Test with `pom.xml`.
    - [x] Test with `build.gradle`.
    - [x] Test with `build.gradle.kts`.
    - [x] Test with `build.xml`.
    - [x] Test with no build file.

## Verification
- [x] Run `BuildToolServiceTest` to ensure all tests pass.
