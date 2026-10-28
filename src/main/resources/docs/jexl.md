# RoxyCode JEXL Documentation

This document describes the Apache Commons JEXL 3 syntax and safety rules for the RoxyCode environment. Always consult the injected **JEXL API Documentation** in your session context for the exact workspace services and methods available in your current phase.

## Purpose: Lightweight JS-Style Orchestration of Java Services
- **Heavy Lifting Lives in Java Services**: File I/O, RipGrep text search, Git operations, build/test execution, and agent memory management are implemented in dedicated Java `@AgentService` classes (`fileReadService`, `fileEditorService`, `grepService`, `gitService`, `buildToolService`, `memoryService`).
- **JEXL is the Glue Layer**: Use `executeJexl` strictly as a concise, JavaScript-like orchestration layer to batch and coordinate multiple service calls in a single turn (e.g., reading multiple files, applying edits, and running `buildToolService.compile()`), rather than writing complex procedural algorithms in JEXL.

## Tool Separation: Workspace Services (`executeJexl`) vs. Native Plan Tools
- **`executeJexl` (`JexlTool`)** is strictly for workspace services: `fileReadService`, `fileEditorService`, `grepService`, `gitService`, `buildToolService`, and `memoryService`.
- **`PlanService` tools** (`submitPlan`, `addTechnicalStep`, `updateTechnicalStep`, `markStepCompleted`, `markStepsCompleted`, `markStepIncomplete`, `markStepsIncomplete`, `addHandoffLogEntry`, `requestDevelopment`, `requestReview`, `requestFixes`, `requestCompletion`, `requestPlanRevision`) are dedicated native tools called directly—**NOT** inside JEXL scripts.

## JEXL Syntax Rules

### Core Rules
- **Variables**: Define with `var` (e.g., `var x = 1;`).
- **Strings**: Use single or double quotes for single-line strings (e.g., `'hello'` or `"world"`). **MULTI-LINE STRINGS MUST USE BACKTICKS (`). If you use double or single quotes for a string that spans multiple lines, JEXL will crash with a tokenization error.**
- **Control Flow**: Standard `if/else`, `for`, `while` loops.
- **Implicit Return**: The value of the last expression in the script is returned to the system.

### Multi-Line String Example (Required for multi-line edits)
// BAD: Will throw a tokenization error
var bad = "line 1
line 2";

// GOOD: Uses backticks for multi-line text
var good = `line 1
line 2`;

fileEditorService.replaceLines('src/App.java', 1, 2, good);

### Array vs. List Literals (JS-Friendly Service Boundaries)
All `@AgentService` batch methods (such as `fileReadService.readFiles(['a', 'b'])` and `fileReadService.readFilesNumbered(['a', 'b'])`) accept standard JavaScript-style array literals `[...]`.

- **Standard Array Literals (`['a', 'b']`)**: Use standard brackets `[...]` when passing arrays to `@AgentService` methods or returning fixed collections.
  ```javascript
  // Standard JS-style array literal works directly with all @AgentService methods
  var files = fileReadService.readFiles(['src/main/java/App.java', 'pom.xml']);
  ```
- **Mutable List Literals (`[..., ...]`)**: Only needed if you explicitly call `java.util.List` mutation methods like `.add()` on a local collection in your script.
  ```javascript
  // Creating a mutable java.util.List when .add() is needed
  var customList = ['First Item', 'Second Item', ...];
  ```

### Handling Result Objects (`EditorResult` and `BuildResult`)
Result objects returned by workspace services (`EditorResult` and `BuildResult`) support both **standard JavaScript property syntax** (`obj.field`) and **record method syntax** (`obj.field()`).
```javascript
var result = grepService.grep('pattern', 'src', '*.java');

if (!result.success) {
    result.errorHint;
} else {
    result.content;
}

var build = buildToolService.buildAndTest();
if (!build.success) {
    { "errors": build.errors, "failedTests": build.failedTests };
}
```

### Iterating over Collections
JEXL handles Java Lists and Arrays seamlessly in iteration.
```javascript
var files = grepService.listFiles('src');

for (var file : files) {
    // process file path
}

if (files.size() > 0) {
    var firstFile = files[0];
}
```

### ⚠️ Safety Tip: Arrays vs. Lists and the `.add()` Method
When accumulating results dynamically, prefer inline array/map initialization or batch service methods. If you do use `.add()`, remember that standard brackets `[]` create a fixed-size Java array rather than a mutable `List`.

- **❌ Incorrect**:
  ```javascript
  var results = []; 
  results.add(fileReadService.readFile("file.txt")); // Fails: arrays do not have add()
  ```
- **✅ Correct (Option 1: Batch Service Call - Preferred)**:
  ```javascript
  var results = fileReadService.readFiles(["file1.txt", "file2.txt"]);
  ```
- **✅ Correct (Option 2: Inline Array Initialization)**:
  ```javascript
  var results = [
      fileReadService.readFile("file1.txt"),
      fileReadService.readFile("file2.txt")
  ];
  ```
- **✅ Correct (Option 3: Mutable List Literal)**:
  ```javascript
  var results = [...]; 
  results.add(fileReadService.readFile("file.txt"));
  ```

### JEXL Batching & Literal Syntax Rules

1. **Prefer Built-in Batch Helpers for Multi-File Reads and Search-and-Read**:
   Instead of writing manual loops or multi-key maps to read several files, use the one-liner batch helpers on `fileReadService` and `grepService`:
   ```javascript
   // Read multiple files in one call (returns Map<String, String>, resilient to missing/binary files)
   fileReadService.readFiles(['pom.xml', 'src/main/java/org/roxycode/app/RoxyCode.java']);

   // Read multiple files with 1-based line numbers in one call
   fileReadService.readFilesNumbered(['src/main/java/App.java', 'src/main/java/Config.java']);

   // Find matching file paths and immediately read their contents in one call
   grepService.readMatchingFiles('class AuthService', 'src', '*.java');

   // Or get a deduplicated List<String> of matching file paths via RipGrep (-l)
   grepService.findMatchingFiles('@AgentService', 'src', '*.java');
   ```

2. **Map Literals MUST use curly braces `{ ... }`**:
   When returning multiple heterogeneous results in `executeJexl`, ALWAYS wrap `"key": value` pairs in `{ ... }`:
   ```javascript
   {
     "files": fileReadService.readFiles(['path/to/FileA.java', 'path/to/FileB.java']),
     "matches": grepService.findMatchingFiles('TODO', 'src', '*.java')
   }
   ```
   NEVER use square brackets `[ ... ]` with colons (`"key": value`): `[ "key": value ]` is invalid JEXL syntax and will fail with `parsing error in '['`. Square brackets `[ val1, val2 ]` are strictly for unkeyed Lists/Arrays.

### ❌ FORBIDDEN: Arbitrary Java Class Instantiation
Do NOT attempt to instantiate arbitrary Java classes using `new('com.example.ClassName')` or attempt to call static methods on native Java classes. 
- The JEXL environment is sandboxed. You ONLY have access to the specific service variables listed in the provided **JEXL API Documentation**.
- If you need to test Java application logic, you MUST write a `.java` test file and execute it using `buildToolService` (consult the provided JEXL API Documentation for available test execution methods). Do not try to run application logic inside the JEXL script itself.

### ⚠️ Pitfalls & Constraints
1. **Output Size**: Returning extremely large objects (e.g., contents of 50 files) may exceed context limits. Be selective.
2. **Map Syntax**: Use `{ "key": value, ... }` for Map literals.
3. **Error Handling**: Always check `.success` (or `.success()`) on service results within your script to avoid cascading failures.
