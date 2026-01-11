# Goal
Implement Agent Skills support in RoxyCode, following the Anthropic skill specification with a JavaScript/GraalVM twist.

# Proposed Changes
- Add jackson-dataformat-yaml dependency to pom.xml.
- Create Skill record to represent skill metadata and content.
- Create SkillService to manage scanning, loading, and metadata extraction of skills from roxy_home/skills/.
- Expose SkillService to GraalJS via ScriptServiceRegistry.
- Update GenAIService to include available skill descriptions in the system prompt.
- Register a new tool 'load_skill' that allows the LLM to read the full SKILL.md of a specific skill.

# Implementation Steps
- [ ] Add jackson-dataformat-yaml to pom.xml and refresh dependencies.
- [ ] Implement org.roxycode.core.skills.Skill record.
- [ ] Implement org.roxycode.core.skills.SkillService with methods for scanning roxy_home/skills/ and parsing SKILL.md.
- [ ] Add unit tests for SkillService.
- [ ] Register SkillService in ScriptServiceRegistry.
- [ ] Modify GenAIService.buildSystemMessage to fetch skill metadata and append it to the prompt.
- [ ] Create a new tool definition for 'load_skill' in roxy_home/tools/load_skill.toml and implement the corresponding logic (or use SkillService).
- [ ] Update AGENTS.md to explain how to use skills.

# Implementation Progress
