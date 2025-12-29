# Plan: Test Stop Chat Functionality

We need to ensure that the "Stop Chat" functionality correctly interrupts the chat loop in `GenAIService`.

## User Review Required

> [!IMPORTANT]
> To test this effectively, `GenAIService` needs to be slightly refactored to allow injecting or setting a mock/stub Gemini `Client`.

- No critical items requiring user attention.

## Proposed Changes

### Core

#### [GenAIService.java](src/main/java/org/roxycode/core/GenAIService.java)

- [x] Add a protected method `doGenerateContent(...)` to allow overriding the actual Gemini API call in tests.

### Tests

#### [GenAIServiceTest.java](src/test/java/org/roxycode/core/GenAIServiceTest.java)

- [x] Add `testStopChatInterruption` test case:
    - [x] Use `Mockito.spy()` to override `doGenerateContent`.
    - [x] Configure the mock to return a response that would normally trigger another turn (e.g., a tool call).
    - [x] Call `stopChat()` after the first turn via the `onStatusUpdate` callback.
    - [x] Assert that `chat()` returns "Chat stopped by user.".

## Verification Plan

### Automated Tests
- Run `mvn test -Dtest=GenAIServiceTest` to verify the new test case passes.

## Implementation Progress

- [x] Refactor `GenAIService` for testability.
- [x] Implement `testStopChatInterruption` in `GenAIServiceTest`.
- [x] Verify test results.
