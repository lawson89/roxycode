# Plan: Mock Gemini API in CacheManagementJobTest

The goal is to ensure that `CacheManagementJobTest` does not make actual calls to the Gemini API and instead uses mocks.

## Requirements
- `CacheManagementJobTest` should not call the real Gemini API.
- Use mocks for `GeminiCacheService` and other dependencies in `CacheManagementJobTest`.
- Ensure all tests in `CacheManagementJobTest` pass.

## Analysis
- `CacheManagementJobTest` already uses `@MockBean` for `GeminiCacheService`.
- However, the user reports that it's still calling the Gemini API.
- This could be because:
    1. The `@Scheduled` job in `CacheManagementJob` starts before the mocks are fully injected.
    2. Other tests are running and calling the API.
    3. The `@MockBean` is not being correctly applied to the `CacheManagementJob` instance.

## Proposed Changes
1. **Refactor Gemini Client Access**:
    - Introduce a `GeminiClientFactory` or a similar abstraction to wrap the creation of `com.google.genai.Client`.
    - Inject this factory into `GeminiCacheService` and `GenAIService`.
2. **Update CacheManagementJobTest**:
    - Ensure all dependencies of `CacheManagementJob` are correctly mocked.
    - Disable the scheduled job by default in the test if possible, or ensure it uses the mocks.
3. **Verify Implementation**:
    - Run the tests and check logs for any "Initializing/Refreshing Gemini Client..." or "Pushing cache to Gemini..." messages which indicate real API interaction.

## Implementation Steps
- [ ] Create `GeminiClientFactory` interface and implementation.
- [ ] Refactor `GeminiCacheService` to use `GeminiClientFactory`.
- [ ] Refactor `GenAIService` to use `GeminiClientFactory`.
- [ ] Update `CacheManagementJobTest` to mock `GeminiClientFactory` if necessary, or just ensure `GeminiCacheService` mock is working.
- [ ] Verify with test execution.

## Progress
- [ ] Step 1: Create `GeminiClientFactory`
- [ ] Step 2: Refactor `GeminiCacheService`
- [ ] Step 3: Refactor `GenAIService`
- [ ] Step 4: Update and Verify `CacheManagementJobTest`
