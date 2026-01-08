
# Plan: Fix missing 's' in 'src' in Project Changes view

The issue is caused by `GitRunner.runGitCommand` calling `.trim()` on the output of `git status --porcelain`.
When the first line of the output starts with a space (e.g., an unstaged change like ` M src/...`), `.trim()` removes that leading space.
Subsequent parsing in `ChangesView.java` assumes the path starts at index 3, but because the leading space was removed, it starts at index 2, causing the first character of the path to be cut off.

## Proposed Changes

### 1. Modify GitRunner.java
- Remove `.trim()` from `runGitCommand`.
- Instead of `.trim()`, we should only remove trailing whitespace if necessary, or let callers handle it.
- To be safe and minimal, I will change it to only trim trailing whitespace, preserving leading whitespace which is significant for porcelain output.

### 2. Update Callers of GitRunner.runGitCommand (Optional but recommended)
- Check if any callers absolutely need the leading whitespace trimmed (e.g., getting the current branch name).
- `GitService.getCurrentBranch()` and `RoxyProjectService`'s branch check should probably still be trimmed.

## Implementation Steps

- [ ] Modify `GitRunner.java` to remove the `.trim()` call on the entire output.
- [ ] Implement a way to only trim trailing whitespace in `GitRunner.java`.
- [ ] Explicitly trim the result in callers where it's appropriate (like branch name).
- [ ] Verify the fix by running a manual check or unit test.

## Verification
- Run `GitService.getStatus()` and verify it preserves leading spaces.
- Check `ChangesView.java` logic to ensure it correctly parses the status.

## Implementation Progress

- [x] Update `GitRunner.java` to stop trimming leading whitespace.
- [x] Audit and update callers of `GitRunner.runGitCommand` that expect trimmed output.
- [x] Verify `ChangesView.java` logic.
- [x] Run tests.

**Status: Complete**
