# Plan: Advanced Java Refactoring Tool

This plan outlines the implementation of a new tool for performing advanced refactorings in Java projects, such as organizing imports, renaming classes project-wide, extracting methods, and extracting interfaces.

## User Requirements
- Create an "Advanced Refactoring" tool.
- Support:
    - Organize Imports (remove unused).
    - Rename Class (project-wide).
    - Extract Method (into a new private method).
    - Extract Interface from Class.
    - Move Class (to a new package).

## Implementation Progress
- [x] Phase 1: Service Skeleton & Basic Refactorings
    - [x] Create `RefactoringService` class.
    - [x] Integrate JavaParser.
    - [x] Implement `organizeImports`.
    - [x] Implement `renameClassInFile`.
    - [x] Add Unit Tests for Phase 1.
- [x] Phase 2: Project-Wide Refactorings
    - [x] Implement project-wide `renameClass`.
    - [x] Implement `moveClass` (including file movement).
    - [x] Add Unit Tests for Phase 2.
- [x] Phase 3: Structural Refactorings
    - [x] Implement `extractMethod` (basic version).
    - [x] Implement `extractInterface`.
    - [x] Add Unit Tests for Phase 3.
- [x] Phase 4: Finalization
    - [x] Ensure all tests pass.
    - [x] Add Javadoc and `@LLMDoc` annotations.
    - [x] Update documentation.

## Conclusion
The Advanced Refactoring Tool is now fully implemented and tested. It provides a robust set of refactoring operations that can be used by RoxyCode to improve the structure and quality of Java projects.
