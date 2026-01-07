### Plan: Align RoxyCode Header Row Vertically

The goal is to vertically align the header row components (RoxyCode label, project label, git branch label, etc.) along the middle of the cell. Currently, the row uses `alignToBaseline="true"`, which might cause misalignment when components have different font sizes (h2 vs h3).

#### Steps:
1.  **Modify `src/main/resources/org/roxycode/ui/MainFrame.xml`**:
    *   Change `alignToBaseline="true"` to `alignToBaseline="false"` in the header `row-panel`. This will allow the components to be vertically centered by the `RowPanel` layout manager.
2.  **Verify**:
    *   Check if any other `row-panel`s in `MainFrame.xml` or other views need similar adjustments if they appear misaligned.

#### Implementation Progress:
- [ ] Update `MainFrame.xml` header row alignment.
