# Fix fs_read_files Tool

## Problem
The `fs_read_files` tool fails when invoked with multiple files because the JavaScript implementation passes a single String to the Java `FileSystemService.readFiles(List<String>)` method, which expects a List.

## Analysis
- Tool Definition (`roxy_home/tools/fs_read_files.toml`) defines `paths` as a `string`.
- JS Implementation (`roxy_home/tools/fs_read_files.js`) calls `fs.readFiles(args.paths)` directly.
- Java Service (`FileSystemService.java`) expects `List<String>`.
- Error observed: `Cannot convert ... to Java type 'java.util.List'`.

## Plan
1.  Modify `roxy_home/tools/fs_read_files.js` to:
    - Check if `args.paths` is a string.
    - If so, split it by `,` (comma) and trim whitespace.
    - Pass the resulting array to `fs.readFiles`.

## Verification
- Run `fs_read_files` with multiple file paths (e.g., two small text files).
- Verify content is returned for both.

## Implementation Progress
- [x] Modify `roxy_home/tools/fs_read_files.js` to handle string splitting.
- [x] Verify fix by reading multiple files.
- [x] **Complete**
