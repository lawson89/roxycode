# Goal
Move the activity indicator to the left of the mode display and align it properly with the mode and plan labels.

# Proposed Changes
- Modify MainFrame.xml to move the activity-indicator before the mode column-panel.
- Adjust activity-indicator size to match the combined height of the mode and plan labels.
- Ensure proper alignment of the activity indicator with the text labels.

# Implementation Steps
- [ ] Update src/main/resources/org/roxycode/ui/MainFrame.xml with the new layout.
- [ ] Verify the layout by launching the application or using Sierra preview if possible.

# Implementation Progress
- [x] Updated MainFrame.xml to move activity-indicator and adjust its size.
