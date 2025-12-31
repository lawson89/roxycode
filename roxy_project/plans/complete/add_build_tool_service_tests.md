# Plan: Add Unit Tests for BuildToolService

This plan outlines the steps to add unit tests for the `BuildToolService` to ensure it correctly detects different build tools (Maven, Gradle, Ant) and handles the unknown case.

## User Review Required

> [!IMPORTANT]
> None.

## Proposed Changes

### Create BuildToolServiceTest.java
- Create `src/test/java/org/roxycode/core/tools/service/BuildToolServiceTest.java`.
- Use `@TempDir` to simulate a project root.
- Test `detect()` for:
    - Maven (presence of `pom.xml`)
    - Gradle (presence of `build.gradle`)
    - Gradle Kotlin (presence of `build.gradle.kts`)
    - Ant (presence of `build.xml`)
    - Unknown (no build files)
    - Multiple build files (check priority if any, though the current implementation returns the first match in a specific order: Maven > Gradle > Ant)

## Verification Plan

### Automated Tests
- Run `mvn test -Dtest=BuildToolServiceTest` to execute the new tests.
- Verify all tests pass.

## Implementation Progress

- [x] Create `BuildToolServiceTest.java`
- [x] Run tests and verify results
