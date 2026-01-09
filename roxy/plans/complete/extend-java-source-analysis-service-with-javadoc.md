# Goal
Ensure JavaSourceAnalysisService correctly extracts and includes Javadoc comments in the generated skeleton code, and update the language level to Java 21.

# Proposed Changes
- Update ParserConfiguration in JavaSourceAnalysisService to use Java 21.
- Refine getJavadoc helper in SkeletonVisitor to better handle Javadoc formatting and ensure it captures Javadoc comments correctly.
- Verify that Class, Method, and Field declarations in the skeleton include their respective Javadocs.
- Add a unit test in JavaAnalysisServiceNewTest to verify Javadoc inclusion.

# Implementation Steps
- [ ] Modify JavaSourceAnalysisService.java to update language level.
- [ ] Update SkeletonVisitor.getJavadoc to improve extraction and formatting.
- [ ] Add testJavadocInSkeleton to JavaAnalysisServiceNewTest.java.
- [ ] Run tests to verify the fix.

# Implementation Progress
- [x] X Modify JavaSourceAnalysisService.java to update language level.
- [x] X Update SkeletonVisitor.getJavadoc to improve extraction and formatting.
- [x] X Add testJavadocInSkeleton to JavaAnalysisServiceNewTest.java.
- [x] X Run tests to verify the fix.
