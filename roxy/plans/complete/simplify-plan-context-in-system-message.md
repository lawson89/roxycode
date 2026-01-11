# Goal
Simplify the inclusion of the current plan in the system message by reading the raw markdown instead of manually reconstructing it from the Plan POJO.

# Proposed Changes
- Add getPlanMarkdown(String name) method to PlanService.java.
- Modify buildSystemMessage in GenAIService.java to use planService.getPlanMarkdown() and remove the manual field extraction logic.

# Implementation Steps
- [ ] Add getPlanMarkdown(String name) to PlanService.java, including @LLMDoc annotation.
- [ ] Update buildSystemMessage in GenAIService.java to use the new method.
- [ ] Verify that the system message still contains the correct plan information (via logs or testing).

# Implementation Progress
- [x] Added getPlanMarkdown(String name) to PlanService.java [x]
- [x] Updated GenAIService.java to include current plan in system prompt [x]
- [x] Added unit test for getPlanMarkdown in PlanServiceTest.java [x]

