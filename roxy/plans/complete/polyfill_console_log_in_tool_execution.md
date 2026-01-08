
# Plan: Polyfill console.log in ToolExecutionService

This plan outlines the steps to capture console logs from JavaScript tools and return them to the LLM.

## Proposed Changes

### Core
1.  **Modify `ToolExecutionService.java`**:
    *   Capture `stdout` and `stderr` from the GraalJS `Context`.
    *   Update `executeJavaScript` to use a `ByteArrayOutputStream` for capturing output.
    *   Prepend captured logs to the script execution result.
    *   Ensure that errors also include any logs captured before the error occurred.

## Implementation Steps

1.  **Modify `ToolExecutionService.java`**
    *   Import `java.io.ByteArrayOutputStream`.
    *   In `executeJavaScript`, create a `ByteArrayOutputStream` for capturing logs.
    *   Configure `Context.newBuilder("js")` to use this stream for `out` and `err`.
    *   After `context.eval`, get the string from the stream.
    *   If the stream is not empty, prepend it to the result: `"[LOGS]\n" + logs + "\n[RESULT]\n" + result`.
    *   If no logs were captured, just return the result (to keep it clean when not logging).

2.  **Verify Implementation**
    *   Run a test script that uses `console.log` and verify the output contains the logs.
    *   Run a test script that fails and verify it still includes logs captured before the failure.

## Implementation Progress

- [ ] Modify `ToolExecutionService.java` to capture output
- [ ] Update result formatting to include logs
- [ ] Verify with test scripts
