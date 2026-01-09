# Goal
Fix JsToHtmlConverter to correctly highlight multiline JavaScript code.

# Proposed Changes
- Modify JsToHtmlConverter.java to process input line by line.
- Pass tokenization state between lines.

# Implementation Steps
- [ ] Create reproduction test case
- [ ] Implement line-by-line processing
- [ ] Pass state using TokenMaker
- [ ] Verify with tests

# Implementation Progress
- [x] Created reproduction test case in JsToHtmlConverterTest
- [x] Implemented line-by-line processing in JsToHtmlConverter
- [x] Passed tokenization state between lines
- [x] Verified fix with unit tests
