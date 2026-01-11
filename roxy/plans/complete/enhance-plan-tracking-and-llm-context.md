# Goal
Improve the LLM's ability to track the current plan by including the plan's details in the system prompt and providing a dedicated section for LLM-managed context.

# Proposed Changes
- Add `llmContext` field to `Plan.java`.
- Update `PlanService.java` to support parsing and generating the "LLM Context" section in markdown files.
- Add `updatePlanLLMContext(String name, String context)` to `PlanService.java`.
- Inject `PlanService` into `GenAIService.java`.
- Update `GenAIService.buildSystemMessage` to include the active plan's content if one is set.
- Update the system prompt construction logic to incorporate current plan details for better turn-by-turn context.

# Implementation Steps
- [ ] Modify `org.roxycode.core.tools.service.plans.Plan` to add a String field `llmContext` with getters and setters.
- [ ] Modify `org.roxycode.core.tools.service.plans.PlanService` to include "LLM Context" in `generateMarkdown` and `parseMarkdown`.
- [ ] Implement `updatePlanLLMContext(String name, String context)` in `PlanService` and mark it with `@LLMDoc`.
- [ ] Modify `org.roxycode.core.GenAIService` to include `PlanService` as a constructor dependency.
- [ ] Update `GenAIService.buildSystemMessage` to check `roxyProjectService.getCurrentPlan()` and if set, load the plan via `planService` and append its markdown content to the system message.
- [ ] Verify that the current plan is correctly included in the system message logs during a chat session.

# Implementation Progress
- [x] Added `llmContext` field to `Plan` class [x]
- [x] Added `updateLlmContext` method to `PlanService` [x]
- [x] Exposed `updateLlmContext` to the JS bridge [x]
- [x] Added unit tests for `llmContext` in `PlanServiceTest` [x]
- [x] Work completed and confirmed [x]
