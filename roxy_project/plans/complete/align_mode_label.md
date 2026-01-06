# Plan: Align Roxy Mode with Project and Branch Info

This plan outlines the changes needed to move the `roxyModeLabel` and its associated label into the same `column-panel` as the project and branch information in `MainFrame.xml`.

## Proposed Changes

### UI Changes

#### `src/main/resources/org/roxycode/ui/MainFrame.xml`
- Move the "Mode:" label and `roxyModeLabel` into the `column-panel` that contains "Project" and "Branch" info.
- Wrap them in a `row-panel` with `spacing="4"` to match the existing layout.
- Adjust spacers as needed.

## Implementation Steps

1.  **Modify `MainFrame.xml`**:
    - Locate the `column-panel` containing `currentProjectLabel` and `gitBranchLabel`.
    - Add a new `row-panel` inside this `column-panel` containing the "Mode:" label and the `roxyModeLabel`.
    - Remove the old "Mode" label and `roxyModeLabel` from the parent `row-panel`.
2.  **Verify UI**:
    - Use `sierra_preview` to verify the new layout.
3.  **Run Tests**:
    - Run existing tests to ensure no regressions.

## Progress
- [x] Modify `MainFrame.xml`
- [x] Verify UI with `sierra_preview`
- [x] Run existing tests
