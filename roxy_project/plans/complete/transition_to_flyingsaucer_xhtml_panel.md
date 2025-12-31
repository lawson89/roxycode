# Plan: Transition MarkdownPane to Flying Saucer XHTMLPanel

This plan outlines the steps to replace the current `JTextPane`-based `MarkdownPane` with a more capable `XHTMLPanel` from the Flying Saucer project.

## User Review Required

> [!IMPORTANT]
> Flying Saucer requires valid XHTML. Flexmark-java's output may need sanitization to ensure it is strict XHTML. We will use Jsoup for this purpose.

- [ ] Does the user approve the addition of `flying-saucer-core`, `flying-saucer-swing`, and `jsoup` dependencies?
- [ ] Should we support incremental rendering (rendering only the new part) or is re-rendering the whole chat history acceptable for now? (Re-rendering is simpler with `XHTMLPanel`).

## Proposed Changes

### 1. Dependency Updates
- Add `org.xhtmlrenderer:flying-saucer-core:9.1.22`
- Add `org.xhtmlrenderer:flying-saucer-swing:9.1.22`
- Add `org.jsoup:jsoup:1.17.2` (for HTML to XHTML conversion)

### 2. Refactor `MarkdownPane.java`
- Change `MarkdownPane` to extend `JPanel` with `BorderLayout`.
- Add an `XHTMLPanel` as the main component.
- Maintain a `StringBuilder` or `List<String>` for the chat history (XHTML snippets).
- Implement a `render()` method that:
    1. Wraps the snippets in a full XHTML document structure.
    2. Uses Jsoup to ensure strict XHTML validity.
    3. Calls `xhtmlPanel.setDocumentFromString()`.
- Update `updateStyle()` to generate a CSS string used in the XHTML header.
- Implement a custom `ReplacedElementFactory` to support the dynamic icons (wrench, robot, cat) currently used.

### 3. Image/Icon Handling
- The current implementation generates `BufferedImage` from `FontIcon` and stores them in an `imageCache`.
- Flying Saucer's `XHTMLPanel` uses `SharedContext.setReplacedElementFactory()`.
- We will create `RoxyReplacedElementFactory` that:
    - Delegates to the default factory for standard images.
    - Handles `http://roxycode.local/` URLs by looking up the `BufferedImage` in a local map.

### 4. Integration in `MainFrame.java`
- `MainFrame` already uses `chatScrollPane.setViewportView(chatArea)`.
- If `MarkdownPane` remains a `JComponent`, no changes should be needed in `MainFrame` other than ensuring it handles being in a `JScrollPane` correctly (Flying Saucer suggests `FSScrollPane`, but standard `JScrollPane` often works if the panel implements `Scrollable` or if we just set the view).

## Verification Plan

### Automated Tests
- Create a test to verify that `MarkdownPane` can render a sample markdown string without throwing XML parsing errors.
- Verify that CSS rules are correctly applied in dark and light modes.

### Manual Verification
- Launch the application using `launch_preview`.
- Send a message and verify it appears.
- Trigger a tool execution and verify the wrench icon appears.
- Switch themes and verify the background and text colors update.
- Verify tables and other markdown extensions still work.

## Implementation Progress

- [ ] Add dependencies to `pom.xml`
- [ ] Create `RoxyReplacedElementFactory`
- [ ] Implement `MarkdownPane` using `XHTMLPanel`
- [ ] Update `MainFrame` to ensure compatibility
- [ ] Verify styling and icon rendering
