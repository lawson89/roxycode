# Goal
Add a new service to expose JSoup functionality to the LLM via GraalJS.

# Proposed Changes
- Add jsoup dependency to pom.xml
- Create JSoupService class in org.roxycode.core.tools.service
- Annotate JSoupService with @ScriptService('jsoupService')
- Expose JSoup methods for fetching and parsing HTML
- Update run_js.toml description to include jsoupService

# Implementation Steps
- [ ] Add jsoup dependency to pom.xml
- [ ] Create JSoupService.java
- [ ] Verify JSoupService is picked up by ScriptServiceRegistry (it should be automatic via Micronaut)
- [ ] Update run_js.toml
- [ ] Create a test for JSoupService

# Implementation Progress
