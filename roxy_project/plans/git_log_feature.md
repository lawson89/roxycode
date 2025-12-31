# Plan: Git Log Feature

## User Request
Implement a git history function to show recent changes for a file and expose it as a tool named `git_log`.

## Plan
- [x] Create `roxy_home/tools/git_log.toml` with tool definition.
- [x] Create `roxy_home/tools/git_log.js` script to invoke the service.
- [x] Update `GitService.java` to add `log(Path root, String filePath, int limit)` method.
- [x] Update `GitServiceTest.java` to test the new method.
- [x] Verify implementation with tests.

## Implementation Details
- `GitService` uses `ProcessExecutor` to call `git log`.
- Parameters: `path` (optional), `limit` (default 10).
- Tool exposed as `git_log`.
