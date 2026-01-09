# Goal
Fix JavaParser parse errors related to Text Block Literals by ensuring Java 21 language level is configured for all Java analysis services.

# Proposed Changes
- src/main/java/org/roxycode/core/analysis/JavaSourceGraphService.java: Add @PostConstruct init method to set StaticJavaParser configuration to JAVA_21.
- src/main/java/org/roxycode/core/tools/service/StructuralSearchService.java: Update init method to also set language level to JAVA_21.

# Implementation Steps
- [ ] Modify JavaSourceGraphService.java to include an init() method annotated with @PostConstruct that configures StaticJavaParser to use Java 21.
- [ ] Update StructuralSearchService.java to include setting the language level to Java 21 in its init() method.
- [ ] Audit codebase for any other direct uses of StaticJavaParser and ensure they are covered by the global configuration.

# Implementation Progress
- [x] Identified all services using JavaParser: JavaService, StructuralSearchService, RefactoringService, JavaSourceAnalysisService, JavaSourceGraphService.
- [x] Updated RefactoringService with init() method to set language level to JAVA_21.
- [x] Ensured StructuralSearchService uses JAVA_21 and fixed minor import issues.
- [x] Verified JavaSourceAnalysisService, JavaSourceGraphService, and JavaService already use JAVA_21.
- [x] Verified compilation and ran relevant unit tests.
- [x] All tests passed.
