
# Plan: Improve Roxy Mode Display

## Goal
Make the Roxy mode in the header bar more prominent and colorful with appropriate icons.

## Steps
1. **Identify Icons and Colors for each RoxyMode**:
    - DISCOVERY: `MaterialDesignM.MAGNIFY`, Blue
    - PLANNING: `MaterialDesignN.NOTE_EDIT_OUTLINE`, Orange
    - IMPLEMENTING: `MaterialDesignC.COG_PLAY_OUTLINE`, Green
    - FEEDBACK: `MaterialDesignC.COMMENT_QUOTE_OUTLINE`, Purple
2. **Modify `MainFrame.java`**:
    - Update `updateRoxyMode()` to set the icon, foreground color, and possibly font style of `roxyModeLabel` based on the current mode.
3. **Modify `MainFrame.xml`**:
    - Remove the static "Mode:" label or keep it and make the `roxyModeLabel` stand out.
    - I'll remove "Mode:" and just have the mode label with icon, which should be self-explanatory if it's prominent.
4. **Test the changes**:
    - Since I can't easily run the full UI and interact with it, I'll rely on code inspection and potentially unit tests if applicable (though UI testing is hard here). I'll verify the code compiles.

## Progress
- [x] Identify Icons and Colors
- [x] Modify MainFrame.java
- [x] Modify MainFrame.xml
- [x] Verify compilation
