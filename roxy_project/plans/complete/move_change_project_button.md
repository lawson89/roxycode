
# Plan: Move "Change" button to the right of Git label and rename it

## Description
Move the project "Change" button to the right of the git branch label in the header of the application. Also rename the button text from "Change" to "Change Project".

## Proposed Changes

### UI Layout
- Modify `src/main/resources/org/roxycode/ui/MainFrame.xml`:
    - Reorder elements in the header `row-panel`.
    - Change button text to "Change Project".

## Checklist
- [x] Modify `MainFrame.xml` to reorder elements and update button text.
- [x] Verify the layout looks correct (mock verification).

## Implementation Progress
- [x] XML modified.
- [x] Verified.
