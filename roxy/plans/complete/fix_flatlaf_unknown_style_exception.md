# Goal
Resolve the UnknownStyleException in FlatLaf by fixing an invalid style property in MainFrame.xml

# Proposed Changes
- Replace 'font-size: 10px' with 'font: -2' in src/main/resources/org/roxycode/ui/MainFrame.xml

# Implementation Steps
- [ ] Read src/main/resources/org/roxycode/ui/MainFrame.xml
- [ ] Replace 'font-size: 10px' with 'font: -2' in the XML file
- [ ] Verify the fix using SierraPreviewService

# Implementation Progress
- [x] Read MainFrame.xml
- [x] Replaced 'font-size: 10px' with 'font: -2'
- [x] Verified fix using SierraPreviewService (Valid Sierra file)
