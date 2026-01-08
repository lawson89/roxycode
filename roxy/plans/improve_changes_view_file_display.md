
# Plan: Improve Changes View File Display

The goal is to modify the `ChangesView` class to display the filename first, followed by its containing folder in parentheses with a lighter font color.

## User Requirements
- List the filename first.
- Followed by a space and then the folder name in parentheses.
- The folder name should be in a lighter font.

## Proposed Changes
### 1. Modify `ChangesView.java`
- Update `populateChangesList` method to parse the `filePath`.
- Split `filePath` into filename and parent directory.
- Format the output string using HTML to apply styles to the folder part.
- Use a lighter color for the folder (e.g., `#888888` or a theme-aware CSS class if available). Since it's HTML in a `JTextPane`, inline CSS is likely the easiest way.

## Implementation Steps
- [ ] Create a new plan file.
- [ ] Modify `src/main/java/org/roxycode/ui/views/ChangesView.java`.
- [ ] Test the changes by running the application (if possible) or verifying the logic.

## Implementation Progress
- [x] Step 1: Parse filePath into filename and directory.
- [x] Step 2: Update the HTML generation in `populateChangesList`.
- [x] Step 3: Verify results.


## Completion Status
- Work is complete.