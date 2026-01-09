# Goal
Centralize JavaParser configuration to ensure Java 21 features (like text blocks) are supported consistently across all services.

# Proposed Changes
- Create a centralized JavaParserProvider service to manage parser configuration.
- Refactor JavaService, RefactoringService, and StructuralSearchService to use the provider instead of StaticJavaParser where possible.
- Ensure all services consistently use JAVA_21 language level.
- Remove redundant and potentially conflicting @PostConstruct init methods in multiple services.

# Implementation Steps
- [ ] Create JavaParserProvider.java with pre-configured JavaParser and ParserConfiguration beans.
- [ ] Update JavaService to use JavaParserProvider.
- [ ] Update RefactoringService to use JavaParserProvider.
- [ ] Update StructuralSearchService to use JavaParserProvider.
- [ ] Update JavaSourceAnalysisService to use the centralized configuration.
- [ ] Update JavaSourceGraphService to use JavaParserProvider.
- [ ] Add a unit test in JavaServiceTest that specifically parses a file with text blocks to verify the fix.

# Implementation Progress
