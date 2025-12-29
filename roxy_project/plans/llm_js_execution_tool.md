# LLM JS Execution Tool Plan

## Objective
Enable the LLM to execute arbitrary JavaScript code on the local environment to perform complex tasks or query data dynamically.

## Tasks

### 1. Define the Tool
- [x] Create `roxy_home/tools/run_js.toml`
    - Description: "Executes arbitrary JavaScript code. Available globals: fs, grep, git, tika, java, xml, toml, buildTool, preview, json, sandbox."
    - Parameter: `script` (string, required) - The JavaScript code to run.

### 2. Implement the Tool Logic
- [x] Create `roxy_home/tools/run_js.js`
    - Content: `eval(args.script)`
    - This leverages the existing `ToolExecutionService` which injects services and `args` into the context.

### 3. Verification
- [x] Verify the tool exists and is loaded (by listing tools or checking file creation).
- [x] (Optional) Run a test if possible, or verify via user interaction.

## Implementation Progress
- Defined tool in `roxy_home/tools/run_js.toml`
- Implemented logic in `roxy_home/tools/run_js.js`
- Verified files exist.
- Tool is ready for use.
