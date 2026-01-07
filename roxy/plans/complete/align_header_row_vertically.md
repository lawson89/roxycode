### Plan: Align RoxyCode Header Row Vertically

The goal is to vertically align the header row components (RoxyCode label, project label, git branch label, etc.) along the middle of the cell. Currently, the row uses `alignToBaseline="true"`, which might cause misalignment when components have different font sizes (h2 vs h3).

#### Steps:
1.  **Modify `src/main/resources/org/roxycode/ui/MainFrame.xml`**:
    *   Change `alignToBaseline="true"` to `alignToBaseline="false"` in the header `row-panel`. This will allow the components to be vertically centered by the `RowPanel` layout manager.
2.  **Verify**:
    *   Run tests (though this is a UI change and might not have direct unit tests for layout).

#### Implementation Progress:
- [x] Update `MainFrame.xml` header row alignment.
