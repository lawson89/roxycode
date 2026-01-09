# Goal
Reduce input token consumption and prevent "exceeding input token limits" errors

# Proposed Changes
- Update GeminiCacheService to bake full prompt into cache instruction.
- Remove redundant prompt from ProjectPackerService.
- Optimize GenAIService.buildSystemMessage for cached sessions.
- Implement tool output truncation in GenAIService.

# Implementation Steps
- [ ] Modify GeminiCacheService.java: Update pushCache to set the full system prompt in the cache config.
- [ ] Modify ProjectPackerService.java: Remove the system prompt from the TOML packer logic.
- [ ] Modify GenAIService.java: Update buildSystemMessage to skip the static prompt if cacheMeta.isPresent().
- [ ] Modify GenAIService.java: Implement truncation for tool results in handleFunctionCall.
- [ ] Test the changes

# Implementation Progress
- [x] X [GeminiCacheService.java] Set systemInstruction using RoxyProjectService.getStaticSystemPrompt() during cache push.
- [x] X [ProjectPackerService.java] Remove the redundant [[content]] system_prompt block from the codebase TOML generation.
- [x] X [GenAIService.java] In buildSystemMessage(), skip appending the static system prompt if a cache is present.
- [x] X [GenAIService.java] In handleFunctionCall(), truncate tool outputs to 20,000 characters to prevent context overflow.
