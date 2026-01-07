# Plan: Move Branch Label under Project Label

## Overview
The goal is to move the Git branch label from its current horizontal position in the header to be vertically positioned underneath the Project label.

## Proposed Changes

### UI
1.  **Modify `src/main/resources/org/roxycode/ui/MainFrame.xml`**:
    *   Find the `row-panel` containing the header information.
    *   Group the "Project" labels and "Branch" labels into a `column-panel`.
    *   Adjust spacing and alignment as needed for a clean look.

## Implementation Progress
- [x] Modify `src/main/resources/org/roxycode/ui/MainFrame.xml` to group labels.
- [x] Verify layout via Sierra Preview.


### Automated Tests
*   N/A (UI layout change is hard to test with JUnit in this setup without complex UI testing framework, but I will ensure the project compiles).

### Manual Verification
*   Launch the application and verify the branch label is now positioned under the Project label.
