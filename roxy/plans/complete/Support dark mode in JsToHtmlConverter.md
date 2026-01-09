# Goal
Update JsToHtmlConverter and MarkdownPane to support light and dark modes for JavaScript syntax highlighting.

# Proposed Changes
- Modify JsToHtmlConverter.java to accept a boolean flag for dark mode.
- Implement dark mode color schemes in JsToHtmlConverter.getColorForTokenType.
- Update the 'pre' tag style in JsToHtmlConverter.convertToHtml based on the theme.
- Update MarkdownPane.java to pass the current theme status (isLafDark) to JsToHtmlConverter.

# Implementation Steps
- [ ] Read and analyze JsToHtmlConverter.java.
- [ ] Update JsToHtmlConverter.java with theme support.
- [ ] Update MarkdownPane.java to use the new JsToHtmlConverter method.
- [ ] Verify changes by running existing tests if any, or manual inspection of the code.

# Implementation Progress
- [x] Started implementation
- [x] [x] Update JsToHtmlConverter.java with theme support
- [x] [x] Update MarkdownPane.java to use the new JsToHtmlConverter method
- [x] [x] Implementation complete
