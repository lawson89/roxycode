
# Plan: Improve Changes View Formatting

The user wants to remove the bullet points from the changed files list in the Changes view and display the list using a monospace font.

## Proposed Changes

### 1. Update `ChangesView.java`

- Modify `populateChangesList` to:
    - Remove the `* ` prefix from each line.
    - Wrap the entire list in a `<div style='font-family: monospace;'>` or use `<pre>` if appropriate. Given `MarkdownPane`'s styles for `pre`, a `div` with inline style might be cleaner to avoid unwanted padding/background if not desired.
    - Ensure each file entry is on a new line.
    - Improve alignment by using the two-character status from git porcelain output.

## Implementation Steps

- [x] Modify `org.roxycode.ui.views.ChangesView#populateChangesList` (Used `div` with `font-family: monospace`, removed bullet point, used `<br/>` for line breaks)
    - Use a `div` with monospace font family for the list container.
    - Format each line without the bullet point.
    - Use `<br/>` for line breaks.
    - Adjust status display for better alignment in monospace.
- [x] Verify the changes by checking the UI (if possible) or reviewing the code.
- [x] Ensure unit tests pass (if any relevant tests exist).

## Verification Plan

- Manually verify the appearance of the Changes view.
- Check that the font is indeed monospace.
- Check that bullet points are gone.


## Implementation Progress

- Work complete. Updated `ChangesView.java` to use monospace font and removed bullet points in the changes list.