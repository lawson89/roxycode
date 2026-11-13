# RoxyCode JEXL Documentation

This document describes the Apache Commons JEXL 3 syntax and safety rules for the RoxyCode environment. Always consult the injected **JEXL API Documentation** in your session context for the exact workspace services and methods available in your current phase.

## Purpose: Lightweight JS-Style Orchestration of Java Services
- **Heavy Lifting Lives in Java Services**: File I/O, RipGrep text search, Universal Ctags symbol analysis, Git operations, build/test execution, agent memory management, plan/workflow management, interactive user prompts, and grounded web search are implemented in dedicated Java `@AgentService` classes (`fileReadService`, `fileEditorService`, `grepService`, `symbolService`, `gitService`, `buildToolService`, `memoryService`, `planService`, `userInputService`, `webSearchService`).
- **JEXL is the Glue Layer**: Use `executeJexl` strictly as a concise, JavaScript-like orchestration layer to batch and coordinate multiple service calls in a single turn (e.g., reading multiple files or symbols, applying edits, running `buildToolService.compile()`, and marking steps completed via `planService.markStepsCompleted([0, 1])`), rather than writing complex procedural algorithms in JEXL.

## Unified JEXL Services (`executeJexl`)
- **`executeJexl` (`JexlTool`)** exposes all workspace and workflow services according to the active phase: `fileReadService`, `fileEditorService`, `grepService`, `symbolService`, `gitService`, `buildToolService`, `memoryService`, `planService`, `userInputService`, and `webSearchService`.
- **`planService`** methods (`submitPlan`, `addTechnicalStep`, `updateTechnicalStep`, `markStepCompleted`, `markStepsCompleted`, `markStepIncomplete`, `markStepsIncomplete`, `addHandoffLogEntry`, `requestDevelopment`, `requestReview`, `requestFixes`, `requestCompletion`, `requestPlanRevision`) are invoked directly on `planService` inside `executeJexl` (e.g., `planService.markStepsCompleted([0, 1])`).

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

### Built-In JavaScript / TypeScript Polyfills & Globals (`JsPolyfillUberspect`)
To reduce syntax friction, RoxyCode's JEXL sandbox includes built-in JS-style property/method polyfills and global helpers:
- **`.length`**: Works on `String`, `Collection`/`List`, `Map`, and Java arrays.
- **Array / List methods**: `.includes(x)`, `.indexOf(x)`, `.slice(start, end?)`, `.join(sep?)`, `.push(...)` (on mutable `Collection`s), and higher-order lambda methods `.map(x -> ...)`, `.filter(x -> ...)`, `.find(x -> ...)`, `.some(x -> ...)`, `.every(x -> ...)`, and `.forEach(x -> ...)`.
- **String methods**: `.includes(s)`, `.slice(start, end?)`, `.trimStart()`, `.trimEnd()`, `.padStart(len, fill?)`, `.padEnd(len, fill?)`.
- **Map methods**: `.has(key)`, `.delete(key)`.
- **Global helpers**: `JSON.stringify(obj, replacer?, indent?)`, `JSON.parse(str)`, `Object.keys(map)`, `Object.values(map)`, `Object.entries(map)`.

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

1. **Prefer Symbol-First Targeted Reads & Built-in Batch Helpers**:
   When method names or line numbers are visible in `## REPOSITORY MAP`, prefer targeted reads (`symbolService.readSymbol`, `symbolService.readSymbols`, or `fileReadService.readLines`) before falling back to full-file `readFileNumbered` on large classes. When inspecting multiple files or searching, use the one-liner batch helpers:
   ```javascript
   // Read specific methods or line ranges using Repo Map line numbers (saves context tokens)
   symbolService.readSymbols('src/main/java/App.java', ['main', 'helper']);
   fileReadService.readLines('src/main/java/App.java', 40, 95);

   // Read multiple smaller files in one call (returns Map<String, String>, resilient to missing/binary files)
   fileReadService.readFiles(['pom.xml', 'src/main/java/org/roxycode/app/RoxyCode.java']);
   fileReadService.readFilesNumbered(['src/main/java/App.java', 'src/main/java/Config.java']);

   // Find matching file paths and immediately read their contents in one call
   grepService.readMatchingFiles('class AuthService', 'src', '*.java');
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
