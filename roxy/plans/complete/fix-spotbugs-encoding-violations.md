# fix-spotbugs-encoding-violations

## Goal
Fix SpotBugs violations related to default encoding (DM_DEFAULT_ENCODING).

## Proposed Changes
- Update LogCaptureService.java to specify StandardCharsets.UTF_8 when creating PrintStream and when calling buffer.toString().
- Update BuildToolService.java to specify StandardCharsets.UTF_8 when creating InputStreamReader.

## Implementation Steps
1. Modify LogCaptureService.java to use StandardCharsets.UTF_8 in startCapture and DualOutputStream.write.
2. Modify BuildToolService.java to use StandardCharsets.UTF_8 in executeCommand.
3. Run unit tests to ensure no regressions.

## Implementation Progress
- [x] Modify LogCaptureService.java to use StandardCharsets.UTF_8 in startCapture and DualOutputStream.write.
- [x] Modify BuildToolService.java to use StandardCharsets.UTF_8 in executeCommand.
- [x] Run unit tests to ensure no regressions.

## Agent Context
Fixed encoding issues in LogCaptureService and BuildToolService identified by SpotBugs.