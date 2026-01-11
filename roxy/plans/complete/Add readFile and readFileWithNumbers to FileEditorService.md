# Add readFile and readFileWithNumbers to FileEditorService

## Goal
Add ability for FileEditorService to read an entire file, optionally with line numbers.

## Proposed Changes
- Add `readFile(String path)` to `FileEditorService.java`.
- Add `readFileWithNumbers(String path)` to `FileEditorService.java`.
- Add corresponding unit tests to `FileEditorServiceTest.java`.

## Implementation Steps
- [ ] Modify `FileEditorService.java` to add `readFile` method.
- [ ] Modify `FileEditorService.java` to add `readFileWithNumbers` method.
- [ ] Update `FileEditorServiceTest.java` with tests for the new methods.
- [ ] Run tests to ensure everything is working correctly.

## Implementation Progress
- [x] ✅ Started implementation
- [x] ✅ Added readFile and readFileWithNumbers to FileEditorService.java
- [x] ✅ Updated FileEditorServiceTest.java with tests for the new methods
- [x] ✅ Ran tests and verified success

## Agent Context
The new methods will leverage existing range-based reading methods by passing 1 to Integer.MAX_VALUE as the range.
