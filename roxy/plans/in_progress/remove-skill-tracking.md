# Goal
Remove tracking of the current skill at the project level and from the system prompt, as it is no longer needed.

# Proposed Changes
- Remove `currentSkill` field, `getCurrentSkill()`, and `setCurrentSkill(Skill)` from `RoxyProjectService.java`.
- Remove the use of `roxyProjectService.getCurrentSkill()` in `GenAIService.buildSystemMessage`.
- Update `SlashCommandService.handleSkillCommand` to only display skill information and not set a current skill.
- Remove skill activation/deactivation logic from `SlashCommandService`.
- Update tests that might rely on `getCurrentSkill` or `setCurrentSkill`.

# Implementation Steps
- [ ] Identify and list all references to `getCurrentSkill` and `setCurrentSkill` in the codebase.
- [ ] Modify `RoxyProjectService.java` to remove the skill-related field and methods.
- [ ] Modify `GenAIService.java` to remove skill injection into the system message.
- [ ] Modify `SlashCommandService.java` to update the `/skill` command behavior.
- [ ] Run tests and fix any compilation errors or failures.
- [ ] Verify that skills can still be listed and viewed via `/skill` but are not 'activated'.

# Implementation Progress

# Agent Context

