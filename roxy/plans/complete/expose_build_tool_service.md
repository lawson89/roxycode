# Plan: Expose BuildToolService as a Tool

This plan outlines the steps to expose `BuildToolService` as a tool available to the AI agent.

## User Review Required

> [!IMPORTANT]
> None anticipated.

## Proposed Changes

### 1. Refactor BuildToolService
- Modify `BuildToolService` to be a proper CDI singleton.
- Inject `Sandbox` into `BuildToolService`.
- Change `detect(Path projectRoot)` to `detect()` which uses the sandbox root.
- Make `detect()` non-static.

### 2. Update ToolExecutionService
- Inject `BuildToolService` into `ToolExecutionService`.
- Bind `BuildToolService` to the JavaScript context as `buildTool`.

### 3. Create Tool Definition
- Create `roxy_home/tools/detect_build_tool.toml` with the tool definition and JavaScript implementation.

## Verification Plan

### Automated Tests
- Run `mvn compile` to ensure no syntax errors.
- Since I cannot easily run a full end-to-end test of the tool execution without a running application, I'll rely on correct implementation and manual inspection.

### Manual Verification
- Check if the tool is listed in the available tools if I could (not applicable in this environment).
- I will check if the files are correctly created and modified.

## Implementation Progress

- [x] Refactor `BuildToolService`
- [x] Update `ToolExecutionService`
- [x] Create `detect_build_tool.toml`
- [x] Final Build Check
