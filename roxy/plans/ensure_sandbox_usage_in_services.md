
# Plan: Ensure all tool services use Sandbox for path access

Review and update all services in `src/main/java/org/roxycode/core/tools/service` to ensure they use the `Sandbox` service for all path access. This is crucial for security to prevent directory traversal attacks.

## Services to review:
- [ ] BuildToolService.java
- [ ] FileEditorService.java
- [ ] FileSystemService.java
- [ ] GitRunner.java (Wait, GitRunner might be a utility, let's check it)
- [ ] GitService.java
- [ ] GrepService.java
- [ ] JavaService.java
- [ ] PreviewService.java
- [ ] SierraPreviewService.java
- [ ] TikaService.java
- [ ] TomlService.java
- [ ] XmlService.java

## Implementation Steps:
1. **Analyze each service**: Check if it already has `Sandbox` injected.
2. **Inject Sandbox**: If not present, inject `Sandbox` using `@Inject`.
3. **Update Path Resolution**: Replace any direct `Path.of()`, `Paths.get()`, or file-based path manipulations with `sandbox.resolve(pathStr)`.
4. **Verify/Test**: Run existing tests for each service to ensure they still work correctly.

## Implementation Progress:
- [ ] BuildToolService.java
- [ ] FileEditorService.java
- [ ] FileSystemService.java
- [ ] GitRunner.java
- [ ] GitService.java
- [ ] GrepService.java
- [ ] JavaService.java
- [ ] PreviewService.java
- [ ] SierraPreviewService.java
- [ ] TikaService.java
- [ ] TomlService.java
- [ ] XmlService.java
