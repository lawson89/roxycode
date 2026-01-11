# Goal
Rename llmContext to agentContext in the Plan model, PlanService, and AGENTS.md to align with agentic terminology.

# Proposed Changes
- Modify src/main/java/org/roxycode/core/tools/service/plans/Plan.java: Rename llmContext field and its getter/setter to agentContext.
- Modify src/main/java/org/roxycode/core/tools/service/plans/PlanService.java: Rename updateLlmContext to updateAgentContext and update markdown parsing/serialization to use "Agent Context".
- Modify roxy_home/AGENTS.md: Replace all occurrences of "LLM Context" and "llmContext" with "Agent Context" and "agentContext" respectively.
- Update src/test/java/org/roxycode/core/tools/service/plans/PlanServiceTest.java: Update unit tests to reflect the renamed field and methods.

# Implementation Steps
- [ ] Update Plan.java: Rename field and methods.
- [ ] Update PlanService.java: Rename methods and update markdown section header to "Agent Context".
- [ ] Update AGENTS.md: Update references to the context section.
- [ ] Update PlanServiceTest.java: Update tests to match new names and header.
- [ ] Run tests to ensure everything is working correctly.

# Implementation Progress
- [x] Renamed llmContext to agentContext in Plan.java [x]
- [x] Renamed updateLlmContext to updateAgentContext in PlanService.java [x]
- [x] Updated Javadoc and LLMDoc in PlanService.java [x]
- [x] Updated PlanServiceTest.java and verified tests pass [x]
- [x] Updated AGENTS.md to use Agent Context [x]
- [x] Work confirmed and completed [x]

