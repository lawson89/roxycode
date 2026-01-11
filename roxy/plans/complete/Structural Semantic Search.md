# Goal
Implement a structural semantic search capability for Java code to find complex patterns like empty catch blocks or deprecated elements without Javadoc using JavaParser and Symbol Solver.

# Proposed Changes
- Add javaparser-symbol-solver-core dependency to pom.xml
- Create SearchResult record to represent a match
- Create StructuralSearchService.java in org.roxycode.core.tools.service
- Integrate JavaParser with CombinedTypeSolver in StructuralSearchService
- Implement findEmptyCatchBlocks(String directory)
- Implement findDeprecatedWithoutJavadoc(String directory)
- Implement findMethodsWithTooManyParameters(String directory, int threshold)
- Implement findLargeClasses(String directory, int lineThreshold)
- Expose StructuralSearchService to the JS environment via @ScriptService
- Add unit tests for StructuralSearchService

# Implementation Steps
- [ ] Update pom.xml with javaparser-symbol-solver-core
- [ ] Create SearchResult.java in org.roxycode.core.tools.service
- [ ] Create StructuralSearchService.java with skeleton and @ScriptService annotation
- [ ] Implement directory walking logic to analyze all .java files in a path
- [ ] Implement findEmptyCatchBlocks logic
- [ ] Implement findDeprecatedWithoutJavadoc logic (checking both annotations and javadoc presence)
- [ ] Implement findMethodsWithTooManyParameters logic
- [ ] Implement findLargeClasses logic
- [ ] Verify initialization with CombinedTypeSolver (ReflectionTypeSolver and JavaParserTypeSolver)
- [ ] Write unit tests covering each search type
- [ ] Run tests and verify functionality

# Implementation Progress
- [x] Updated pom.xml with javaparser-symbol-solver-core
- [x] Created SearchResult.java
- [x] Created StructuralSearchService.java
- [x] Implemented findEmptyCatchBlocks
- [x] Implemented findDeprecatedWithoutJavadoc
- [x] Implemented findMethodsWithTooManyParameters
- [x] Implemented findLargeClasses
- [x] Verified with unit tests
- [x] Implementation complete
