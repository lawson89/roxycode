# Goal
Update AGENTS.md for accuracy, add llmContext section, and update the Java Plan model/service to support it.

# Proposed Changes
- Modify AGENTS.md to list 5 plan sections and clarify mode terminology.
- Update org.roxycode.core.tools.service.plans.Plan to include an llmContext field.
- Update org.roxycode.core.tools.service.plans.PlanService to handle the llmContext field in creation and updates.

# Implementation Steps
- [ ] Update org.roxycode.core.tools.service.plans.Plan class with llmContext field and getters/setters.
- [ ] Update org.roxycode.core.tools.service.plans.PlanService to include llmContext in markdown parsing and serialization.
- [ ] Add updateLLMContext method to PlanService.
- [ ] Update AGENTS.md with the new instructions and 5-section plan structure.
- [ ] Verify changes by creating a dummy plan and checking the markdown content.

# Implementation Progress
- [x] Switched to CODE mode
- [x] Moved plan to in_progress
- [x] Verified Plan and PlanService already have partial llmContext support
- [x] Ready to update AGENTS.md

