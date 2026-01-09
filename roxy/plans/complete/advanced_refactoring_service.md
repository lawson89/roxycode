# Plan: Advanced Refactoring Service

## Goal
To implement a high-level refactoring service that automates complex code transformations across multiple files. This service will move beyond simple text replacement by using AST (Abstract Syntax Tree) manipulation to ensure code integrity.

## Proposed Steps

### 1. Refactoring Engine Foundation
- [ ] Identify a robust AST library for transformation (leveraging the existing **JavaParser** used in `JavaService`).
- [ ] Create a `RefactoringImpact` analyzer to predict which files will be affected by a change before applying it.

### 2. Implementation: Rename Refactoring
- [ ] **Rename Class/Interface**:
    - Update the declaration in the source file.
    - Rename the file on disk.
    - Update all references in the project (imports, field types, variable declarations, constructor calls).
- [ ] **Rename Method**:
    - Update the declaration.
    - Update all call sites across the project.

### 3. Implementation: Move Refactoring
- [ ] **Move Class to Package**:
    - Move the file to the new directory structure.
    - Update the `package` declaration in the file.
    - Update all import statements in other files that reference this class.
    - Add imports to the moved class if it now references classes in its old package.

### 4. Implementation: Extract Refactoring
- [ ] **Extract Method**:
    - Analyze a selected block of code for input/output variables.
    - Generate a new method with appropriate parameters and return type.
    - Replace the original block with a call to the new method.

### 5. Implementation: Code Cleanup
- [ ] **Organize Imports**:
    - Remove unused imports.
    - Sort and group imports according to standard conventions.
- [ ] **Standardize Formatting**:
    - Apply project-wide indentation and spacing rules after refactoring.

### 6. JS Environment Integration
- [ ] Expose the `refactoringService` to the JS environment:
    ```javascript
    const refactoringService = {
      renameClass(oldQualifiedName, newName): RefactoringResult,
      moveClass(qualifiedName, targetPackage): RefactoringResult,
      extractMethod(path, lineStart, lineEnd, newMethodName): RefactoringResult,
      organizeImports(path): void
    };
    ```

## Implementation Progress
- [ ] Start implementing RefactoringService.java with Organize Imports.
- [x] Phase 1: Foundation & Impact Analysis (Leveraging JavaParser via JavaService approach)
- [ ] Phase 2: Rename Implementation
- [ ] Phase 3: Move Implementation
- [ ] Phase 4: Extract Implementation
- [ ] Phase 5: Cleanup & JS Integration

## Mark as Complete
- [ ] Work is complete.
