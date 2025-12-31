# Plan: JavaAnalysisService Implementation

This plan outlines the implementation of a `JavaAnalysisService` using `javaparser-core` to provide static analysis capabilities for Java source code within RoxyCode.

## Feature Overview
The `JavaAnalysisService` will allow the system to programmatically understand and manipulate Java code. This is essential for features like automated refactoring, context-aware AI assistance, and code navigation.

## Proposed Requirements
- Parse Java source files into an Abstract Syntax Tree (AST).
- Extract structural information:
    - Classes and Interfaces.
    - Methods (with signatures and bodies).
    - Fields.
    - Annotations.
    - Imports.
- Provide utility methods to:
    - Get the source code of a specific method.
    - Find all callers/callees (future scope).
    - Identify class hierarchy.
- Support basic code modification (future scope).

## Technical Implementation

### 1. Dependency Updates
- Add `com.github.javaparser:javaparser-core:3.27.1` to `pom.xml`.

### 2. Service Definition
- Create `org.roxycode.core.tools.service.JavaAnalysisService` interface.
- Create `org.roxycode.core.service.JavaAnalysisServiceImpl` implementation.

### 3. API Design (Initial Version)
```java
public interface JavaAnalysisService {
    /**
     * Parses a Java file and returns a summary of its structure.
     */
    JavaFileSummary analyzeFile(Path path) throws IOException;

    /**
     * Gets the source code of a specific method.
     */
    Optional<String> getMethodSource(Path path, String className, String methodName);

    // Records for structured data
    record JavaFileSummary(List<ClassSummary> classes, List<String> imports) {}
    record ClassSummary(String name, List<MethodSummary> methods, boolean isInterface) {}
    record MethodSummary(String name, String signature, int beginLine, int endLine) {}
}
```

## Task Checklist

### Phase 1: Foundation
- [ ] Add `javaparser-core` dependency to `pom.xml`.
- [ ] Verify build compiles with new dependency.
- [ ] Create `JavaAnalysisService` interface.
- [ ] Create `JavaAnalysisServiceImpl` with basic parsing logic.

### Phase 2: Core Functionality
- [ ] Implement `analyzeFile` to return structured summaries.
- [ ] Implement `getMethodSource` to extract method-level code.
- [ ] Add support for finding classes and methods in the project (integration with `FileSystemService`).

### Phase 3: Testing & Integration
- [ ] Write unit tests for `JavaAnalysisServiceImpl`.
- [ ] (Optional) Create a tool for GraalJS to expose this service.

## Implementation Progress

### Phase 1: Foundation
- [ ] Add `javaparser-core` dependency to `pom.xml`.
- [ ] Create `JavaAnalysisService` interface.
- [ ] Create `JavaAnalysisServiceImpl`.
