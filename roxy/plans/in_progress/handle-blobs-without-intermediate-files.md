# Goal
Implement a mechanism to handle binary data (blobs) like screenshots directly in memory, bypassing the need for intermediate files on disk. This improves security (no leaked files) and efficiency.

# Proposed Changes
- Modify `GenAIService.handleFunctionCall` to detect "data:" URI schemes in tool output.
- Ensure blob detection in `GenAIService` happens BEFORE string truncation (to allow large images).
- Update `PreviewService.launchAndScreenshot` to return a base64-encoded Data URI instead of a file path.
- Update `SierraPreviewService.previewSierra` to return a base64-encoded Data URI instead of a file path.
- Remove file-system based image handling in `GenAIService`.
- Update `PreviewService` and `SierraPreviewService` to no longer create the `roxy/previews` directory or save files there.

# Implementation Steps
- Modify `GenAIService.java`: Add logic to parse `data:<mime>;base64,<data>` in `handleFunctionCall` and create `Blob` objects. Place this before truncation.
- Modify `PreviewService.java`: Update `launchAndScreenshot` to use `ByteArrayOutputStream` and `Base64.getEncoder()` to create a Data URI.
- Modify `SierraPreviewService.java`: Update `previewSierra` to use `ByteArrayOutputStream` and `Base64.getEncoder()` to create a Data URI.
- Verify changes with existing tests or by manual inspection of code flow.
- Remove unused code/imports related to file saving in the preview services.

# Implementation Progress

# Agent Context

