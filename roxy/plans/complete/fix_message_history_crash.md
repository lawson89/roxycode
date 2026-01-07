# Plan: Fix Message History Crash and Improve Performance

The application crashes with `IllegalArgumentException: offset out of bounds` when viewing the Message History, especially after large files have been read or large outputs generated. This is likely due to Swing's HTML renderer struggling with very large HTML tables containing `<pre>` blocks, exacerbated by double-rendering of HTML through the Markdown parser.

## Proposed Changes

### 1. UI Components

#### `MarkdownPane.java`
- [x] Add `setHtml(String html)` method to allow setting HTML content directly without running it through the Markdown parser again. It should still wrap the content in `<html><body>...</body></html>` to ensure consistent styling.

### 2. Main Application Window

#### `MainFrame.java`
- [x] Update `updateMessageHistoryView()` to use `messageHistoryArea.setHtml()` instead of `setMarkdown()`.

### 3. Core Services

#### `HistoryService.java`
- [x] Implement truncation logic in `renderContentToHtmlRow()` to prevent extremely large messages ( > 20,000 chars) from overloading Swing's HTML renderer.

## Verification Plan

### Automated Tests
- [x] Build and compile check.
- (Manual) Verify that clicking "Message History" no longer crashes the application after a large file has been processed.

### Manual Verification
1. Run the application.
2. Perform an action that results in large output (e.g., `fs_read_file` of a large source file).
3. Switch to the "Message History" view.
4. Verify no crash occurs and the content is displayed.

## Progress
- [x] Plan created
- [x] `MarkdownPane.java` updated with `setHtml`
- [x] `MainFrame.java` updated to use `setHtml` for message history
- [x] `HistoryService.java` updated with truncation logic
- [x] Build successful
