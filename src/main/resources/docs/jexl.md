# RoxyCode JEXL Documentation

This document describes the Apache Commons JEXL 3 syntax and safety rules for the RoxyCode environment. Always consult the injected **JEXL API Documentation** in your session context for the exact workspace services and methods available in your current phase.

## Tool Separation: Workspace Services (`executeJexl`) vs. Native Plan Tools
- **`executeJexl` (`JexlTool`)** is strictly for workspace services: `fileReadService`, `fileEditorService`, `grepService`, `gitService`, and `buildToolService`.
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

### Array vs. List Literals (Crucial for Java Interop)
JEXL supports distinct syntax for creating native arrays versus `java.util.List` objects. You must use the correct literal syntax to match the expected Java method signature in the provided JEXL API Documentation.

- **Array Literals (`Object[]`)**: Use standard brackets `[...]`.
  ```javascript
  // Creating a fixed-size native Java array
  var arr = ['src/main/java/App.java', 'pom.xml'];
  ```
- **List Literals (`ArrayList`)**: Use brackets with a trailing comma and ellipsis `[...,...]`.
  ```javascript
  // Creating a mutable java.util.List
  var customList = ['First Item', 'Second Item', ...];
  ```

### Handling Java Records
For Java records (like `EditorResult` and `BuildResult`), you **must** use the exact property name as a method call.
```javascript
var result = grepService.grep('pattern', 'src', '*.java');

if (result.success() == false) {
    result.errorHint();
} else {
    result.content();
}

var build = buildToolService.buildAndTest();
if (build.success() == false) {
    build.errors();
    build.failedTests();
}
```
*Note: Do NOT use standard JavaBean getters like `getSuccess()` on records.*

### Iterating over Lists
JEXL handles Java Lists seamlessly in iteration.
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
When accumulating results dynamically, do not initialize empty collections with standard brackets `[]` if you intend to use `.add()`. Standard brackets create a fixed-size native Java array, which does not support `.add()`.

- **❌ Incorrect**:
  ```javascript
  var results = []; 
  results.add(fileReadService.readFile("file.txt")); // Fails: arrays do not have add()
  ```
- **✅ Correct (Option 1: List Literal)**:
  ```javascript
  var results = [...]; 
  results.add(fileReadService.readFile("file.txt"));
  ```
- **✅ Correct (Option 2: Inline Initialization)**:
  ```javascript
  var results = [
      fileReadService.readFile("file1.txt"),
      fileReadService.readFile("file2.txt")
  ];
  ```

### JEXL Batching & Literal Syntax Rules

1. **Map Literals MUST use curly braces `{ ... }`**:
   When returning multiple named results in `executeJexl`, ALWAYS wrap `"key": value` pairs in `{ ... }`:
   ```javascript
   {
     "FileA": fileReadService.readFile("path/to/FileA.java"),
     "FileB": fileReadService.readFile("path/to/FileB.java")
   }
NEVER use square brackets [ ... ] with colons ("key": value): [ "key": value ] is invalid JEXL syntax and will fail with parsing error in '['. Square brackets [ val1, val2 ] are strictly for unkeyed Lists/Arrays.

### ❌ FORBIDDEN: Arbitrary Java Class Instantiation
Do NOT attempt to instantiate arbitrary Java classes using `new('com.example.ClassName')` or attempt to call static methods on native Java classes. 
- The JEXL environment is sandboxed. You ONLY have access to the specific service variables listed in the provided **JEXL API Documentation**.
- If you need to test Java application logic, you MUST write a `.java` test file and execute it using `buildToolService` (consult the provided JEXL API Documentation for available test execution methods). Do not try to run application logic inside the JEXL script itself.

### ⚠️ Pitfalls & Constraints
1. **Output Size**: Returning extremely large objects (e.g., contents of 50 files) may exceed context limits. Be selective.
2. **Map Syntax**: Use `{ "key": value, ... }` for Map literals.
3. **Error Handling**: Always check `.success()` on service results within your script to avoid cascading failures.
