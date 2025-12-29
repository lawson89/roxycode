# Plan: Secure Tool Execution Context

As identified, exposing the full `ApplicationContext` to JavaScript and Groovy tools is a security risk. This plan outlines the steps to remove `applicationContext` and instead expose specific, required services via short names.

## Proposed Changes

### 1. Update `ToolExecutionService.java`

- **Dependency Injection**:
    - Inject the following services:
        - `GrepService`
        - `GitService`
        - `TikaService`
        - `JavaAnalysisService`
        - `ObjectMapper` (to replace the need for tools to get it from context for JSON output)
    - Keep `Sandbox` and `FileSystemService` (already injected).
    - Remove `ApplicationContext`.

- **Bindings Update (`executeJavaScript` and `executeGroovy`)**:
    - Remove the `ctx` binding.
    - Add the following bindings:
        - `grep` -> `GrepService`
        - `git` -> `GitService`
        - `tika` -> `TikaService`
        - `java` -> `JavaAnalysisService`
        - `json` -> `ObjectMapper` (optional but recommended for tools like `java_analyze_file.js`)
    - Keep existing `sandbox`, `fs`, and `args`.

### 2. Update Tool Scripts in `roxy_home/tools`

Several scripts currently rely on `ctx.getBean()` or have outdated service references.

- **`grep.js`**: Update to use the `grep` object directly.
- **`java_analyze_file.js`**: Update to use `java` and `json` objects.
- **`java_get_method_source.js`**: Update to use `java` object.
- **`read_document.js`**: Update to use `tika` object.
- **`git_status.js`**: Refactor to use the `git` service instead of manual `ProcessBuilder`.
- **`git_diff.js`**: Refactor to use the `git` service.
- **Other scripts**: Audit for any remaining `ctx` usage.

### 3. Implementation Steps

#### Phase 1: Java Changes
- [ ] Modify `ToolExecutionService` constructor to include all services.
- [ ] Update `executeJavaScript` to use new bindings and remove `ctx`.
- [ ] Update `executeGroovy` to use new bindings and remove `ctx`.
- [ ] Run `compile_project` to ensure everything builds.

#### Phase 2: Script Updates
- [ ] Update `roxy_home/tools/grep.js`
- [ ] Update `roxy_home/tools/java_analyze_file.js`
- [ ] Update `roxy_home/tools/java_get_method_source.js`
- [ ] Update `roxy_home/tools/read_document.js`
- [ ] Update `roxy_home/tools/git_status.js`
- [ ] Update `roxy_home/tools/git_diff.js`
- [ ] Audit and update any other `.js` or `.groovy` files in `roxy_home/tools`.

#### Phase 3: Verification
- [ ] Run `run_tests` to ensure no regressions in tool execution tests.
- [ ] Manually test key tools (grep, git status, read document) via the UI or by triggering them through `GenAIService` if possible.

## Security Considerations
- Removing `ApplicationContext` prevents tools from accessing internal Micronaut beans (e.g., `SettingsService`, `ToolRegistry`, or even the `ApplicationContext` itself).
- Providing specific services ensures a "least privilege" approach.

## Progress Tracking
- [ ] Implementation Started
- [ ] Java Changes Complete
- [ ] Script Updates Complete
- [ ] Verification Complete
