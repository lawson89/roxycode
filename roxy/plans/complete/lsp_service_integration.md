# Plan: LSP Service Integration

## Goal
Integrate a Language Server Protocol (LSP) service, specifically for Java (Eclipse JDT.LS), to provide RoxyCode with advanced code intelligence, including cross-file navigation, type checking, and refactoring support.

## Proposed Steps

### 1. Research and Selection
- [ ] Evaluate **Eclipse JDT.LS** as the primary Java LSP server.
- [ ] Determine the minimal runtime requirements (Java version, memory, dependencies).
- [ ] Assess the feasibility of running the LSP server alongside the RoxyCode application.

### 2. Infrastructure Setup
- [ ] Create a mechanism to download or bundle the JDT.LS binaries.
- [ ] Implement a process manager to start, stop, and monitor the LSP server.
- [ ] Establish a communication bridge using JSON-RPC over Standard I/O or Sockets.

### 3. Core Service Implementation (Java)
- [ ] Create an `LspClient` interface in the RoxyCode core.
- [ ] Implement the LSP lifecycle (Initialize, Shutdown, Exit).
- [ ] Implement core LSP methods:
    - `textDocument/definition` (Go to Definition)
    - `textDocument/references` (Find References)
    - `textDocument/hover` (Documentation/Type info)
    - `textDocument/publishDiagnostics` (Real-time Errors/Warnings)
    - `textDocument/formatting` (Code Formatting)

### 4. JS Environment Integration
- [ ] Expose the `lspService` to the JavaScript environment via `ScriptServiceRegistry`.
- [ ] Define the JS API for the service:
    ```javascript
    const lspService = {
      getDefinition(path, line, character): Location,
      getReferences(path, line, character): Array<Location>,
      getDiagnostics(path): Array<Diagnostic>,
      formatDocument(path): void,
      // ... more methods
    };
    ```

### 5. UI Enhancements (Optional but Recommended)
- [ ] Display LSP diagnostics (errors/warnings) in the RoxyCode UI.
- [ ] Integrate "Go to Definition" into the code views.

## Implementation Progress
- [ ] Phase 1: Research and Selection
- [ ] Phase 2: Infrastructure Setup
- [ ] Phase 3: Core Service Implementation
- [ ] Phase 4: JS Environment Integration
- [ ] Phase 5: Testing and Refinement

## Mark as Complete
- [ ] Work is complete.
