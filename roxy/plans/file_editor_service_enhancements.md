# Plan: FileEditorService Enhancements

Enhance the `FileEditorService` with advanced line-level editing and navigation features to improve accuracy and ease of use for LLM-driven code modifications.

## Requirements

The following methods will be added to `FileEditorService`:
- `getLinesWithNumbers(String path, int startLine, int endLine)`: Returns lines with 1-based line numbers as prefixes.
- `findLine(String path, String regex, int startLine)`: Returns the 1-based line number of the first occurrence of `regex` after `startLine`.
- `indentLines(String path, int startLine, int endLine, int spaces)`: Adjusts indentation for a range of lines.
- `replaceInLines(String path, String pattern, String replacement, int startLine, int endLine)`: Performs regex replacement limited to a line range.
- `insertLines(String path, int targetLine, List<String> lines)`: Inserts lines at a specific position.
- `undo(String path)`: Reverts the last change made to the specified file by this service.

## Proposed Changes

### 1. Update `FileEditorService.java`

#### `getLinesWithNumbers`
- Iterate through the lines in the range.
- Format each line as `"{lineNumber}: {lineText}"`.

#### `findLine`
- Read all lines.
- Start searching from `startLine - 1`.
- Use `Pattern` to match the regex.
- Return the 1-based line number if found, or -1.

#### `indentLines`
- If `spaces > 0`, prepend `spaces` spaces to each line in the range.
- If `spaces < 0`, remove up to `abs(spaces)` leading spaces from each line in the range.

#### `replaceInLines`
- Iterate through the range of lines.
- Apply `line.replaceAll(pattern, replacement)` to each line.

#### `insertLines`
- Convenience wrapper around `replaceLines` with `startLine == endLine` logic, or a direct `lines.addAll(target, newLines)`.

#### Undo Mechanism
- Maintain a `Map<String, String>` (or a dedicated backup directory) to store the previous content of a file before any write operation (`moveLines`, `deleteLines`, `replaceLines`, `indentLines`, `replaceInLines`, `insertLines`).
- Implement `undo(String path)` to restore from this state.

### 2. Update Unit Tests
- Add test cases to `FileEditorServiceTest.java` for each new method.
- Test the undo functionality across different operations.

## Verification Plan

### Unit Tests
- Run `mvn test` specifically for `FileEditorServiceTest`.
- Verify edge cases:
    - Regex matching across line boundaries (not supported by line-level search).
    - Indenting empty lines.
    - Negative indentation beyond actual spaces.
    - Multiple undo operations (only the last one needs to be supported initially).

## Implementation Progress

- [x] Implement `getLinesWithNumbers`
- [x] Implement `findLine`
- [x] Implement `indentLines`
- [x] Implement `replaceInLines`
- [x] Implement `insertLines`
- [x] Implement Undo mechanism
- [x] Update `FileEditorServiceTest`
- [x] Verify all tests pass


**WORK COMPLETE**