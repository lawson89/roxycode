# Goal
Enhance the codebase cache by including a visual file tree and providing source file paths within the Java skeleton.

# Proposed Changes
- JavaSourceAnalysisService.java: Update skeleton generation to include relative file paths as comments above class/interface declarations.
- ProjectPackerService.java: Update TOML streaming to include a new 'project_tree' section containing the project's file structure.

# Implementation Steps
- [ ] Modify JavaSourceAnalysisService.generateSkeleton to track and output the relative path for each parsed file.
- [ ] Update SkeletonVisitor to print '// File: <path>' before visiting a class or interface.
- [ ] Modify ProjectPackerService.streamFilesToToml to call FileSystemService.tree (or equivalent logic) and write it to the 'project_tree' section.
- [ ] Verify the new cache format by building the project cache and inspecting the output.
- [ ] Ensure existing tests for ProjectPackerService and JavaSourceAnalysisService pass, updating them if necessary to account for the new content.

# Implementation Progress
- [x]  [x] Inject `FileSystemService` into `ProjectPackerService`.
- [x]  [x] Update `ProjectPackerService.streamFilesToToml` to call `fileSystemService.tree(".")` and include the output in the TOML.
- [x]  [x] Update `ProjectPackerServiceTest` to reflect the dependency change and verify the new behavior.
- [x]  [x] Ensure all tests pass.
