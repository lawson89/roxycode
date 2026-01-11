# Goal
Extend JavaService to provide Javadoc information for classes, methods, and fields.

# Proposed Changes
- Update MethodSummary record to include a javadoc field (String).
- Update ClassSummary record to include a javadoc field (String).
- Update FieldSummary record to include a javadoc field (String).
- Update summarizeMethod to extract Javadoc from MethodDeclaration.
- Update summarizeClass to extract Javadoc from ClassOrInterfaceDeclaration.
- Update summarizeClass (field part) to extract Javadoc from FieldDeclaration.
- Add getMethodJavadoc method to JavaService.
- Add getClassJavadoc method to JavaService.
- Add getFieldJavadoc method to JavaService.

# Implementation Steps
- [ ] Modify JavaService.java to update the records MethodSummary, ClassSummary, and FieldSummary.
- [ ] Update the summarizeMethod and summarizeClass methods to include Javadoc extraction logic.
- [ ] Implement getMethodJavadoc, getClassJavadoc, and getFieldJavadoc methods in JavaService.
- [ ] Add necessary imports if required (e.g., com.github.javaparser.ast.comments.Javadoc if using Javadoc object, or just use Optional<Comment>).
- [ ] Test the new functionality with a sample Java file.

# Implementation Progress
- [x] Modified JavaService.java to extract Javadocs during file analysis
- [x] Added getClassJavadoc, getMethodJavadoc, and getFieldJavadoc to JavaService
- [x] Updated summary records to include javadoc field
- [x] Added unit tests for Javadoc extraction in JavaAnalysisServiceTest
- [x] Verified all tests pass
