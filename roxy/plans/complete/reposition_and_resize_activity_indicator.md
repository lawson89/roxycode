# Goal
Display the activity indicator directly to the left of the mode label and resize it to match the mode icon size (16px).

# Proposed Changes
- Modify MainFrame.xml to move the activity-indicator component to the left of the roxyModeLabel.
- Update MainFrame.xml to set the indicatorSize of the activity-indicator to 16.
- Wrap the activity-indicator and roxyModeLabel in a row-panel to ensure they are on the same line if necessary, or just move it before the column-panel if it should be to the left of both labels.

# Implementation Steps
- [ ] Create the plan file.
- [ ] Switch to CODE mode (after user approval).
- [ ] Move the plan to roxy/plans/working.
- [ ] Modify src/main/resources/org/roxycode/ui/MainFrame.xml to reposition and resize the activity indicator.
- [ ] Verify the changes by running the application (if possible) or visually inspecting the XML.
- [ ] Move the plan to roxy/plans/complete.

# Implementation Progress
- [x] Repositioned activity indicator in MainFrame.xml
- [x] Resized activity indicator to 16px in MainFrame.xml
- [x] Wrapped activity indicator and mode label in a row-panel for alignment
