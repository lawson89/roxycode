# Goal
Consolidate JavaParser configuration into a single provider and clean up orphan code in services.

# Proposed Changes
- Ensure JavaParserProvider correctly provides JavaParser and ParserConfiguration beans with Java 21 level.
- Remove redundant javadoc for non-existent init() methods in JavaService and StructuralSearchService.
- Add test cases for record classes in JavaServiceTest, StructuralSearchServiceTest, and JavaAnalysisServiceNewTest.

# Implementation Steps
- [ ] Verify and update src/main/java/org/roxycode/core/analysis/JavaParserProvider.java if necessary.
- [ ] Remove orphan javadocs in src/main/java/org/roxycode/core/tools/service/JavaService.java.
- [ ] Remove orphan javadocs in src/main/java/org/roxycode/core/tools/service/StructuralSearchService.java.
- [ ] Add testRecordSupport to src/test/java/org/roxycode/core/analysis/JavaAnalysisServiceNewTest.java.
- [ ] Add testRecordSupport to src/test/java/org/roxycode/core/tools/service/JavaServiceTest.java.
- [ ] Add testRecordSupport to src/test/java/org/roxycode/core/tools/service/StructuralSearchServiceTest.java.
- [ ] Run all tests to ensure consistency and correctness.

# Implementation Progress
- [x] Verify and update JavaParserProvider.java
- [x] Remove orphan javadocs in JavaService.java
- [x] Remove orphan javadocs in StructuralSearchService.java
- [x] Add testRecordSupport to JavaAnalysisServiceNewTest.java
- [x] Add testRecordSupport to JavaServiceTest.java
- [x] Add testRecordSupport to StructuralSearchServiceTest.java
