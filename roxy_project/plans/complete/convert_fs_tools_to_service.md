# Plan - Convert `fs_*` tools to use `FileSystemService`

The goal is to refactor all tools starting with `fs_` in `roxy_home/tools` to use the `FileSystemService` instead of directly using Java NIO or other methods. This ensures consistency and leverages the security/sandbox features of the service.

## User Review Required

> [!IMPORTANT]
> - `fs_read_files.js` currently returns an `ArrayList` of file contents. `FileSystemService.readFiles` returns a single formatted string containing all file contents. This change in return type might affect how the output is displayed or consumed, but generally, tools return a string for UI display.
> - `fs_write.js` currently ensures parent directories exist. `FileSystemService.writeFile` needs to be updated to include this functionality.

## Proposed Changes

### 1. `FileSystemService` Enhancements

- Update `writeFile` to create parent directories if they don't exist, matching the current behavior of `fs_write.js`.
- Add unit test for `writeFile` with non-existent parent directories.

### 2. Tool Refactoring

#### `fs_read_file.js`
- Replace direct `java.nio.file.Files` usage with `fs.readFile(args.path)`.
- Simplify the script to a single line or a cleaner try-catch.

#### `fs_read_files.js`
- Replace manual loop and `ArrayList` with `fs.readFiles(args.paths)`.

#### `fs_replace_in_file.js`
- Replace regex replacement logic with `fs.replaceInFile(args.path, args.search, args.replace)`.

#### `fs_tree.js`
- Replace recursive `listDirectory` function with `fs.tree(args.path || ".")`.

#### `fs_write.js`
- Replace direct `Files.writeString` and directory creation with `fs.writeFile(args.path, args.content)`.

#### `delete_file.js` (Optional but recommended)
- Although not starting with `fs_`, it is a core file system tool.
- Replace manual `FileVisitVisitor` with `fs.delete(args.path)`.

## Verification Plan

### Automated Tests
- Run `FileSystemServiceTest` to ensure no regressions.
- Add new test case to `FileSystemServiceTest` for `writeFile` creating parent directories.
- Run `mvn clean compile` to ensure no syntax errors.

### Manual Verification
- Execute each tool via the `ToolExecutionService` (or through the UI if available) to verify they still work as expected.
- Specifically verify:
    - `fs_write` creates missing parent directories.
    - `fs_tree` returns the expected tree structure.
    - `fs_read_files` returns the formatted string with all files.

## Implementation Progress

- [x] Update `FileSystemService.writeFile` to create parent directories.
- [x] Add unit test for `FileSystemService.writeFile` directory creation.
- [x] Convert `fs_read_file.js` to use `fs.readFile`.
- [x] Convert `fs_read_files.js` to use `fs.readFiles`.
- [x] Convert `fs_replace_in_file.js` to use `fs.replaceInFile`.
- [x] Convert `fs_tree.js` to use `fs.tree`.
- [x] Convert `fs_write.js` to use `fs.writeFile`.
- [x] Convert `delete_file.js` to use `fs.delete`.
