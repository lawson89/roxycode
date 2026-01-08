# Plan: Expand FileSystemService with useful methods

The goal is to enhance `FileSystemService` with additional methods that improve efficiency and capability for LLM-driven development tasks, such as checking for existence, distinguishing between files and directories, and performing basic file operations like move and copy.

## Proposed Changes

### 1. Update `FileSystemService.java`
Add the following methods to `org.roxycode.core.tools.service.FileSystemService`:

- `boolean exists(String path)`: Checks if a file or directory exists.
- `boolean isDirectory(String path)`: Checks if a given path is a directory.
- `boolean isFile(String path)`: Checks if a given path is a regular file.
- `void move(String source, String target)`: Moves or renames a file or directory.
- `void copy(String source, String target)`: Copies a file or directory.
- `long getSize(String path)`: Returns the size of a file in bytes.

### 2. Update Tests
Add unit tests in `FileSystemServiceTest.java` to verify the new methods.

## Implementation Steps

1. [ ] Read `src/test/java/org/roxycode/core/tools/service/FileSystemServiceTest.java` to understand existing tests.
2. [ ] Implement the new methods in `FileSystemService.java`.
3. [ ] Add corresponding tests in `FileSystemServiceTest.java`.
4. [ ] Run tests to ensure everything works as expected.

## Implementation Progress

- [x] Proposed changes drafted.
- [x] Implement new methods in FileSystemService
- [x] Add unit tests
- [x] Fix compilation error in XmlServiceTest
- [x] Verify all tests pass

**WORK COMPLETE**
