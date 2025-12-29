# Plan: Move Build Logic to BuildToolService

This plan outlines the steps to move the compilation and test execution logic from JavaScript tools to the `BuildToolService` Java class. This will provide a more robust and maintainable way to support multiple build tools (Maven, Gradle, Ant) and operating systems (Windows, Linux, macOS).

## User Review Required

> [!IMPORTANT]
> - For Maven and Gradle, we will prefer the wrapper (`mvnw`, `gradlew`) if present in the project root.
> - We will support fallback to system-installed build tools if wrappers are missing.
> - For Ant, we assume `ant` is available on the system PATH.
> - `compile` will use `clean compile` for Maven, `classes` for Gradle, and `compile` for Ant.

## Proposed Changes

### 1. Enhance `BuildToolService.java`

- Add `compile()` method.
- Add `runTests()` method.
- Implement helper methods to:
    - Detect the OS.
    - Determine the correct command and executable based on the `BuildTool` and OS.
    - Execute the command and capture output (stdout and stderr).

#### Build Tool Command Resolution

The service will attempt to find the appropriate executable in the following order:

1. **Maven**:
   - Windows: `mvnw.cmd` (in root), then `mvn` (system path).
   - Unix: `./mvnw` (in root), then `mvn` (system path).
   - Command: `clean compile` for compilation, `test` for testing.
2. **Gradle**:
   - Windows: `gradlew.bat` (in root), then `gradle` (system path).
   - Unix: `./gradlew` (in root), then `gradle` (system path).
   - Command: `classes` for compilation, `test` for testing.
3. **Ant**:
   - Windows: `ant.bat` (system path), then `ant` (system path).
   - Unix: `ant` (system path).
   - Command: `compile` for compilation, `test` for testing.

#### Method Signatures

```java
public String compile()
public String runTests()
```

These methods will return the captured output, prefixed with a success/failure indicator (✅ or ❌).

### 2. Update JavaScript Tools

- Refactor `roxy_home/tools/build_compile.js` to call `buildTool.compile()`.
- Refactor `roxy_home/tools/build_run_tests.js` to call `buildTool.runTests()`.

### 3. Verification

- Create/Update tests in `BuildToolServiceTest.java` to verify correct commands are generated.
- Manually test with a Maven project (current project).

## Detailed Steps

### Phase 1: Java Implementation

1.  **Modify `BuildToolService.java`**:
    - Add a private method `executeCommand(List<String> command)` that uses `ProcessBuilder` to run the command in `sandbox.getRoot()`, captures output, and returns a result string formatted for the agent.
    - Implement `compile()`:
        - Detect build tool.
        - Resolve executable.
        - Call `executeCommand` with compile arguments.
    - Implement `runTests()`:
        - Detect build tool.
        - Resolve executable.
        - Call `executeCommand` with test arguments.

### Phase 2: Tool Refactoring

1.  **Update `roxy_home/tools/build_compile.js`**:
    - Replace current logic with `buildTool.compile()`.
2.  **Update `roxy_home/tools/build_run_tests.js`**:
    - Replace current logic with `buildTool.runTests()`.

### Phase 3: Testing

1.  **Enhance `BuildToolServiceTest.java`**:
    - Add unit tests for `compile()` and `runTests()` logic.
    - Mocking `ProcessBuilder` or separating command generation from execution will be necessary for effective unit testing.

## Progress
- [x] Phase 1: Java Implementation
    - [x] Add `executeCommand` to `BuildToolService`
    - [x] Implement `compile()`
    - [x] Implement `runTests()`
- [x] Phase 2: Tool Refactoring
    - [x] Update `build_compile.js`
    - [x] Update `build_run_tests.js`
- [x] Phase 3: Testing
    - [x] Update `BuildToolServiceTest.java`
    - [x] Verify build and tests pass
