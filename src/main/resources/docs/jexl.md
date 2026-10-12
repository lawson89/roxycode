# RoxyCode JEXL Documentation

This document describes the Apache Commons JEXL 3 syntax and safety rules for the ColinxCode environment.

## JEXL Syntax Rules

### Core Rules
- **Variables**: Define with `var` (e.g., `var x = 1;`).
- **Strings**: Use single or double quotes for single-line strings (e.g., `'hello'` or `"world"`). **MULTI-LINE STRINGS MUST USE BACKTICKS (`). If you use double or single quotes for a string that spans multiple lines, JEXL will crash with a tokenization error.**
- **Control Flow**: Standard `if/else`, `for`, `while` loops.
- **Implicit Return**: The value of the last expression in the script is returned to the system.

### Multi-Line String Example (Required for replaceLines / replaceBlock)
// BAD: Will throw a tokenization error
var bad = "line 1
line 2";

// GOOD: Uses backticks for multi-line text
var good = `line 1
line 2`;

textEditorService.replaceLines('src/App.java', 1, 2, good);

### Array vs. List Literals (Crucial for Java Interop)
JEXL supports distinct syntax for creating native arrays versus `java.util.List` objects. You must use the correct literal syntax to match the expected Java method signature.

- **Array Literals (`String[]`)**: Use standard brackets `[...]`.
  ```javascript
  // Passing an array literal for method arguments expecting String[]
  var exists = textEditorService.exists('src/main/java/App.java');
  ```
- **List Literals (`ArrayList`)**: Use brackets with a trailing comma and ellipsis `[...,...]`.
  ```javascript
  // Creating a java.util.List
  var customList = ['First Item', 'Second Item', ...];
  ```

### Handling Java Records
For Java records (like `EditorResult`), you **must** use the exact property name as a method call.
```javascript
var result = textEditorService.grep('pattern', 'src', '*.java');

if (result.success() == false) {
    result.errorHint();
} else {
    result.content();
}
```
*Note: Do NOT use standard JavaBean getters like `getSuccess()`.*

### Iterating over Lists
JEXL handles Java Lists seamlessly in iteration.
```javascript
var files = textEditorService.listFiles('src', '*', true).files();

for (var file : files) {
    console.log(file);
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
  results.add(textEditorService.readFile("file.txt", false)); // Fails: arrays do not have add()
  ```
- **✅ Correct (Option 1: List Literal)**:
  ```javascript
  var results = [...]; 
  results.add(textEditorService.readFile("file.txt", false));
  ```
- **✅ Correct (Option 2: Inline Initialization)**:
  ```javascript
  var results = [
      textEditorService.readFile("file1.txt", false),
      textEditorService.readFile("file2.txt", false)
  ];
  ```

### ❌ FORBIDDEN: Arbitrary Java Class Instantiation
Do NOT attempt to instantiate arbitrary Java classes using `new('com.example.ClassName')` or attempt to call static methods on native Java classes. 
- The JEXL environment is sandboxed. You ONLY have access to the specific service variables listed in the **JEXL API Documentation**.
- If you need to test Java application logic, you MUST write a `.java` test file and execute it using `genericBuildToolService.runTests()` or `runSingleTest()`. Do not try to run application logic inside the JEXL script itself.

### ⚠️ Pitfalls & Constraints
1. **Output Size**: Returning extremely large objects (e.g., contents of 50 files) may exceed context limits. Be selective.
2. **Map Syntax**: Use `{ "key": value, ... }` for Map literals.
3. **Error Handling**: Always check `.success()` on service results within your script to avoid cascading failures.
