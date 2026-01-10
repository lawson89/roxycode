# Goal
Enable SkillService to persist newly created skills to the local file system and load them on startup.

# Proposed Changes
- Update SkillService to save new skills as .js files in a 'skills' directory within roxy_home.
- Update SkillService to load all .js files from the 'skills' directory on startup.
- Ensure 'skills' directory is created if it doesn't exist.

# Implementation Steps
- [ ] Modify SkillService to inject RoxyProjectService to get the roxy_home path.
- [ ] Implement saveSkillToFile(String name, String source) in SkillService.
- [ ] Update addSkill(String name, String source) to call saveSkillToFile.
- [ ] Implement loadSkillsFromDisk() in SkillService and call it from an @PostConstruct init method.
- [ ] Add unit tests in SkillServiceTest (if it exists, or create one) to verify that skills are saved and loaded correctly.

# Implementation Progress
