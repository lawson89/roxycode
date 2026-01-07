# Plan: Improve Header Layout

This plan outlines the changes needed to make the current project display bigger, move the git branch to the right of the project, and ensure everything in the header is in one row.

## Proposed Changes

### UI Changes

#### `src/main/resources/org/roxycode/ui/MainFrame.xml`
- Remove the `column-panel` that stacks project and branch info.
- Increase the font size of `currentProjectLabel` (change `styleClass` from `h4` to `h3`).
- Place `currentProjectLabel`, `openFolderButton`, and `gitBranchLabel` directly in the top-level `row-panel`.
- Ensure all elements are in a single row.
- Adjust spacing between elements for better readability.

## Implementation Steps

1.  **Modify `MainFrame.xml`**:
    - Update the header `row-panel` to include the project and branch elements in a single horizontal flow.
    - Change `currentProjectLabel` `styleClass` to `h3`.
2.  **Verify UI**:
    - Use `sierraPreviewService` to verify the new layout.
3.  **Run Tests**:
    - Run existing tests to ensure no regressions.

## Progress
- [x] Implementation completed and verified via preview.
- [x] Modify `MainFrame.xml`
- [x] Verify UI
- [ ] Run existing tests
