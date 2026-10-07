# RoxyCode JEXL Documentation

This document describes the JEXL syntax and the RoxyCode API available to AI agents.

## JEXL Syntax Rules

### Core Rules
- **Variables**: Define with `var` (e.g., `var x = 1;`).
- **Strings**: Single or double quotes (e.g., `'hello'` or `"world"`).
- **Control Flow**: Standard `if/else`, `for`, `while` loops.
- **Implicit Return**: The value of the last expression in the script is returned to the system.

### Array vs. List Literals (Crucial for Java Interop)
JEXL supports distinct syntax for creating native arrays versus `java.util.List` objects. You must use the correct literal syntax to match the expected Java method signature.

- **Array Literals (`String[]`)**: Use standard brackets `[...]`.
  ```javascript
  // Passing an array literal for method arguments expecting String[]
  var exists = fileReadService.fileExists('src/main/java/App.java');
  ```
- **List Literals (`ArrayList`)**: Use brackets with a trailing comma and ellipsis `[...,...]`.
  ```javascript
  // Creating a java.util.List
  var customList = ['First Item', 'Second Item', ...];
  ```

### Handling Java Records
For Java records (like `EditorResult`), you **must** use the exact property name as a method call.
```javascript
var result = grepService.grep('pattern', 'src', '*.java');

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
var files = fileReadService.listDirectory('src');

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

## RoxyCode API Services

### `fileReadService`
- `readFile(relativePath)`: Reads the content of a file.
- `listDirectory(relativePath)`: Lists the files in a directory.
- `fileExists(relativePath)`: Returns true if the file exists.

### `fileEditorService`
- `writeFile(relativePath, content)`: Overwrites a file with new content.
- `replaceBlock(relativePath, targetBlock, replacementBlock)`: Replaces a unique block of text.
- `replaceLines(relativePath, startLine, endLine, content)`: Replaces a range of lines (1-based).
- `insertAtLine(relativePath, line, content)`: Inserts content at a specific line (1-based).

### `gitService`
- `getStatus()`: Returns the current git status.
- `getDiff()`: Returns the git diff.
- `getLog(limit)`: Returns the git log.
- `getCurrentBranch()`: Returns the name of the current branch.
- `showCommit(hash)`: Shows details for a specific commit.\n

### `grepService`
- `grep(pattern, relativePath, filePattern)`: Searches for a pattern using RipGrep. Returns an `EditorResult` record.
- `listFiles(relativePath)`: Lists files relative to project root using RipGrep.

### `planManagerService`
- `submitFunctionalSpec(spec)`: Submits a functional specification using a `FunctionalSpec` record.
- `submitFunctionalSpec(title, goal, requirements)`: Submits a functional specification using simple arguments.
- `submitFunctionalSpec(specMap)`: Submits a functional specification using a Map (keys: `title`, `goal`, `requirements`).
- `submitTechnicalSpec(spec)`: Submits a technical specification using a `TechnicalSpec` record.
- `submitTechnicalSpec(architectureGoal, constraints, implementationSteps)`: Submits a technical specification using simple arguments.
- `submitTechnicalSpec(specMap)`: Submits a technical specification using a Map (keys: `architectureGoal`, `constraints`, `implementationSteps`).

#### Spec Submission Examples
```javascript
// Example: Submitting functional spec via simple arguments
planManagerService.submitFunctionalSpec(
    "User Auth", 
    "Allow users to login", 
    ["Login via email", "Password reset", ...]
);

// Example: Submitting technical spec via Map
var techSpec = {
    "architectureGoal": "Spring Boot Microservice",
    "constraints": ["Java 17", "PostgreSQL", ...],
    "implementationSteps": ["Setup DB", "Auth API", ...]
};
planManagerService.submitTechnicalSpec(techSpec);
```
