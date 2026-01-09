# Goal
Extend @LLMDoc to support class-level documentation for services exposed to JavaScript, and update the API documentation generator to include these descriptions.

# Proposed Changes
- Modify @LLMDoc annotation to support ElementType.TYPE.
- Update LLMDocGenerator to check for and include class-level @LLMDoc descriptions in the generated JavaScript API documentation.
- Apply @LLMDoc descriptions to key service classes (e.g., FileEditorService, FileSystemService, GitService).

# Implementation Steps
- [ ] Update src/main/java/org/roxycode/core/tools/LLMDoc.java to include ElementType.TYPE in @Target.
- [ ] Update src/main/java/org/roxycode/core/tools/LLMDocGenerator.java to read @LLMDoc from the class and append it as a comment before the service constant definition.
- [ ] Update services like FileEditorService.java, FileSystemService.java, and GitService.java to include class-level @LLMDoc annotations.
- [ ] Verify changes by checking the output of getApiDocs() (can be done via a test or by manual inspection of logs).

# Implementation Progress
- [x] Update LLMDoc.java to include ElementType.TYPE in @Target. [DONE]
- [x] Update LLMDocGenerator.java to read @LLMDoc from the class level and include it in the generated documentation. [DONE]
- [x] Annotate FileEditorService.java with @LLMDoc class-level description. [DONE]
- [x] Annotate FileSystemService.java with @LLMDoc class-level description. [DONE]
- [x] Annotate GitService.java with @LLMDoc class-level description. [DONE]
- [x] Annotate other major services (BuildToolService, JavaService, etc.) with @LLMDoc. [DONE]
- [x] Verify that the generated documentation includes the new class-level descriptions. [DONE]
