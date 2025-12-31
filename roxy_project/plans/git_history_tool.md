# Plan: Git History Tool

## Goal
Implement a git history function to show recent changes for a file and expose it as a tool named `git_log`.

## Steps

### 1. Update Java Service
- **File**: `src/main/java/org/roxycode/core/tools/service/GitService.java`
- **Change**: Add a public method `log(Path projectRoot, String path, int limit)` that executes `git log -n {limit} {path}`.
- **Details**:
    - If `path` is provided, append it to the arguments.
    - Default limit can be passed from the tool, but the Java method should take an int.
    - Reuse existing `runGitCommand` method.

### 2. Update Unit Tests
- **File**: `src/test/java/org/roxycode/core/GitServiceTest.java`
- **Change**: Add a test method `testGitLog` that:
    - Creates a temp git repo.
    - Commits a few changes to a file.
    - Calls `gitService.log` and verifies the output contains the commit messages.

### 3. Create Tool Definition
- **File**: `roxy_home/tools/git_log.toml`
- **Content**:
    - Name: `git_log`
    - Description: Shows the commit history for the project or a specific file.
    - Parameters:
        - `path` (optional string): Specific file to check history for.
        - `limit` (optional integer): Number of commits to show (default 10).

### 4. Create Tool Implementation
- **File**: `roxy_home/tools/git_log.js`
- **Content**:
    - JavaScript logic to call `git.log(sandbox.getRoot(), args.path, args.limit || 10)`.
    - Handle empty output or errors gracefully.

## Verification
- Run `mvn test` to ensure `GitServiceTest` passes.
- (Optional) Use `run_js` to verify the tool manually if possible, or trust the unit tests and the consistent pattern of other tools.

## Implementation Progress
- [x] Update Java Service
- [x] Update Unit Tests
- [x] Create Tool Definition
- [x] Create Tool Implementation
