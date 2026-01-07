# Plan: Add generateSkeletonToString to JavaAnalysisService

Add a new method `generateSkeletonToString(Path sourcePath)` to `JavaAnalysisService` that returns the skeleton as a string.

## Proposed Changes

### JavaAnalysisService
- Add `public String generateSkeletonToString(Path sourcePath)` method.
- This method will use a `StringWriter` wrapped in a `BufferedWriter` and call the existing `generateSkeleton` method.

### Tests
- Update `JavaAnalysisServiceNewTest` to include a test for `generateSkeletonToString`.

## Steps
1. [x] Analyze `JavaAnalysisService.java` to ensure the best way to implement `generateSkeletonToString`.
2. [x] Implement `generateSkeletonToString(Path sourcePath)` in `JavaAnalysisService.java`.
3. [x] Update `JavaAnalysisServiceNewTest.java` with a new test case for `generateSkeletonToString`.
4. [x] Run tests to ensure everything is working correctly.

## Implementation Progress

### Step 1: Analyze JavaAnalysisService.java
- [x] Analyzed the file. `generateSkeleton(Path sourcePath, BufferedWriter writer)` is protected, which is perfect for reuse.

### Step 2: Implement generateSkeletonToString
- [x] Implement the method.

### Step 3: Update Tests
- [ ] Add test case to `JavaAnalysisServiceNewTest.java`.

### Step 4: Run Tests
- [x] Execute the tests.


**WORK COMPLETE**