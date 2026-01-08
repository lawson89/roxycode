
# Plan: Enforce Sandbox Security in Services

The goal is to ensure all services under `src/main/java/org/roxycode/core/tools/service/` use the `Sandbox` for all path access and file operations.

## User Review of Services
- [x] BuildToolService: Mostly OK, uses sandbox.getRoot().
- [x] FileSystemService: Already OK.
- [x] FileEditorService: Already OK.
- [x] GrepService: Already OK.
- [x] JavaService: Already OK.
- [ ] GitService: Needs improvement in `diff` and `log` methods to resolve paths via Sandbox.
- [ ] PreviewService: Should save screenshots inside the sandbox.
- [ ] SierraPreviewService: Needs to inject Sandbox and resolve paths. Should save previews inside the sandbox.
- [ ] TikaService: Needs to inject Sandbox and resolve paths.
- [ ] TomlService: Needs to inject Sandbox and resolve paths.
- [ ] XmlService: Needs to inject Sandbox and resolve paths.

## Implementation Steps

### 1. Update GitService
- Update `diff(boolean cached, String path)` to resolve `path` via `sandbox.resolve(path)` and then relativize it to the sandbox root for the git command.
- Update `log(String path, int limit)` to do the same.

### 2. Update PreviewService
- Ensure screenshots are saved within the sandbox directory (e.g., `roxy/previews/`).
- Use `sandbox.getRoot().resolve("roxy/previews")` and ensure the directory exists.

### 3. Update SierraPreviewService
- Inject `Sandbox`.
- Update `validateSierra(String path)` and `previewSierra(String path)` to use `sandbox.resolve(path)`.
- Save preview images within the sandbox directory.

### 4. Update TikaService
- Inject `Sandbox`.
- Change `readDocument(Path path)` to `readDocument(String pathStr)`.
- Use `sandbox.resolve(pathStr)`.

### 5. Update TomlService
- Inject `Sandbox`.
- Change method signatures to take `String` paths.
- Use `sandbox.resolve` to get the `Path`.

### 6. Update XmlService
- Inject `Sandbox`.
- Change method signatures to take `String` paths.
- Use `sandbox.resolve` to get the `Path`.

## Verification
- Run existing tests to ensure no regressions.
- Add/Update tests for the modified services to verify sandbox enforcement.
