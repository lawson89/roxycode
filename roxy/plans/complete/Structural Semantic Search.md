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
- [x] X Updated pom.xml with javaparser-symbol-solver-core
- [x] X Created SearchResult.java
- [x] X Created StructuralSearchService.java
- [x] X Implemented findEmptyCatchBlocks
- [x] X Implemented findDeprecatedWithoutJavadoc
- [x] X Implemented findMethodsWithTooManyParameters
- [x] X Implemented findLargeClasses
- [x] X Verified with unit tests
- [x] X Implementation complete
