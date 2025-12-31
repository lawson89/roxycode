# Plan: Secure Tool Execution Context

The goal is to restrict the GraalVM JavaScript execution context to prevent unauthorized access (especially network access) and ensure all host interactions are mediated through safe, bound Java services.

## Checklist

- [x] Investigate current `ToolExecutionService.java` for GraalVM `Context` configuration.
- [x] Restrict `Context` to disable direct host class lookup and limit access to specifically bound objects.
- [x] Refactor existing tools that use `Java.type` to use bound services instead.
- [x] Implement a timeout for script execution (set to 60s).
- [x] Update `GenAIServiceTest` and other relevant tests to match the new `ToolExecutionService` constructor.
- [x] Verify that network access is blocked from within JS.

## Implementation Progress

- [x] Restricted GraalVM context in `ToolExecutionService.java` using `.allowHostClassLookup(className -> false)`.
- [x] Refactored `tika_read_document.js` to use `TikaService.readDocument(Path)`.
- [x] Created `PreviewService.java` to handle compilation and screenshot logic.
- [x] Refactored `launch_preview.js` to use `PreviewService`.
- [x] Bound all necessary services in `ToolExecutionService.java`.
- [x] Verified that host class lookup is disabled (verified via test).
- [x] Implemented 60s timeout for JS execution.
- [x] Verified that all existing tests pass.

## Bound Services

- `sandbox`: `Sandbox` (Read-only project root info)
- `fs`: `FileSystemService` (Controlled file operations)
- `grep`: `GrepService`
- `git`: `GitService`
- `tika`: `TikaService`
- `java`: `JavaService`
- `xml`: `XmlService`
- `toml`: `TomlService`
- `buildTool`: `BuildToolService`
- `preview`: `PreviewService`
- `json`: `ObjectMapper` (Jackson)
- `args`: Bound tool arguments
