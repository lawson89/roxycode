# Plan: FileEditorService

Implement a `FileEditorService` that provides line-level editing capabilities.

## Requirements

The `FileEditorService` should provide the following methods:
- `getLines(String path, int startLine, int endLine)`: Returns lines from `startLine` to `endLine` (1-based index, inclusive).
- `moveLines(String path, int startLine, int endLine, int targetLine)`: Moves lines from `startLine` to `endLine` to `targetLine`.
- `deleteLines(String path, int startLine, int endLine)`: Deletes lines from `startLine` to `endLine`.
- `replaceLines(String path, int startLine, int endLine, List<String> newLines)`: Replaces lines from `startLine` to `endLine` with `newLines`.

## Proposed Changes

### 1. Create `FileEditorService` class

Create a new class `org.roxycode.core.tools.service.FileEditorService`.
- Annotate with `@ScriptService("fileEditorService")` and `@Singleton`.
- Inject `Sandbox`.
- Implement the requested methods.

### 2. Implementation details

- Line numbers will be 1-based and inclusive.
- `getLines`:
    - Read all lines of the file.
    - Validate line numbers.
    - Return the subset of lines.
- `moveLines`:
    - Read all lines.
    - Extract lines to move.
    - Remove them from the original position.
    - Insert them at the target position.
    - Write back to file.
- `deleteLines`:
    - Read all lines.
    - Remove lines in the range.
    - Write back to file.
- `replaceLines`:
    - Read all lines.
    - Remove lines in the range.
    - Insert new lines at the start position.
    - Write back to file.

### 3. Registering the service

Since it's annotated with `@ScriptService`, it should be automatically discovered if the discovery mechanism is in place.

## Verification Plan

### Unit Tests
- Create `FileEditorServiceTest` to test each method.
- Test with various scenarios:
    - Valid line ranges.
    - Out of bounds line numbers.
    - Moving lines to beginning/middle/end of file.
    - Deleting all lines.
    - Replacing with empty list of lines.

### Manual Verification
- Use the `run_js` tool to call the new service methods and verify the results on a sample file.

## Implementation Progress

- [x] Create `FileEditorService` class
- [x] Implement `getLines`
- [x] Implement `moveLines`
- [x] Implement `deleteLines`
- [x] Implement `replaceLines`
- [x] Add unit tests
- [x] Verify all tests pass


**WORK COMPLETE**