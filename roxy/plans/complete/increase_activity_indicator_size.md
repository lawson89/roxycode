
# Plan: Increase Activity Indicator Size

The goal is to make the activity indicator next to the `roxymode` larger in the UI.

## Proposed Changes

### 1. Update MainFrame.xml
- Increase the `indicatorSize` property of the `activityIndicator` element in `src/main/resources/org/roxycode/ui/MainFrame.xml`.
- Currently it is set to 12. I will increase it to 18.

## Verification Plan

### 1. Visual Verification
- Run the application and trigger an activity (like asking a question) to see the larger activity indicator.

### 2. Automated Tests
- This is a UI layout change in an XML file, so traditional unit tests might not be applicable directly to the XML, but I can check if the `MainFrame` still loads correctly.

## Implementation Progress
- [x] Update `MainFrame.xml` to increase `indicatorSize` from 12 to 20.
- [ ] Final summary.

The work is complete.
