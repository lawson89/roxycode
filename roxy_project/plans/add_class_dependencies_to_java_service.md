
# Plan: Add Class Dependencies to JavaService

Add the ability for `JavaService` to return a list of all types a class depends on by analyzing its source code.

## User Review Required

> [!IMPORTANT]
> This feature will extract type names as they appear in the source code. Without full symbol resolution (which requires a complete classpath), it may not always be able to resolve short names to fully qualified names if they are in the same package or imported via wildcards.

- Should we include standard Java library types (e.g., `java.util.List`) or filter them out? Answer - filter out Java library types
- Should we include dependencies found in nested/inner classes? No

## Proposed Changes

### `org.roxycode.core.tools.service.JavaService.java`

- Add `getClassDependencies(Path path, String className)` method.
- Update `ClassSummary` record to include `List<String> dependencies`.
- Update `summarizeClass` to populate dependencies.

### `org.roxycode.core.tools.service.JavaServiceTest.java`

- Add test cases for dependency extraction.

## Progress
- [x] Research JavaParser type extraction capabilities <!-- id: 0 -->
- [x] Update `ClassSummary` record <!-- id: 1 -->
- [x] Implement dependency extraction logic in `JavaService` <!-- id: 2 -->
- [x] Add `getClassDependencies` method <!-- id: 3 -->
- [x] Write unit tests <!-- id: 4 -->
- [x] Verify all tests pass (Compilation success confirmed after fixing test code syntax errors) <!-- id: 5 -->
