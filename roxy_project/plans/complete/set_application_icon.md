# Plan: Set Application Icon

## Feature Description
Change the application icon from the generic Java icon to the Roxy logo.

## Proposed Changes
- Modify `org.roxycode.ui.MainFrame`'s `run()` method to set the frame's icon image using `roxy_logo_transparent.png`.

## Implementation Steps
1. [ ] Load the Roxy logo image in `MainFrame.run()`.
2. [ ] Call `setIconImage()` on the `MainFrame` (which is a `JFrame`).
3. [ ] (Optional) Use `setIconImages()` with multiple sizes if available for better resolution on different platforms.

## Verification
1. [ ] Compile the project.
2. [ ] Launch a preview and verify the window icon.
