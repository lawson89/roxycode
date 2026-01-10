# Goal
Create a new service called JSoupService that uses the JSoup library to provide programmatic analysis and manipulation of HTML files within the sandbox.

# Proposed Changes
- Add org.jsoup:jsoup:1.17.2 to pom.xml
- Create JSoupService in org.roxycode.core.tools.service
- Implement analyzeFile, select, replace, updateAttribute, delete, and insert methods in JSoupService
- Annotate JSoupService with @ScriptService('jsoupService') to expose it to run_js
- Create JSoupServiceTest for unit testing

# Implementation Steps
- [ ] Add JSoup dependency to pom.xml
- [ ] Implement JSoupService class with requested methods
- [ ] Implement JSoupServiceTest and verify all methods
- [ ] Confirm JSoupService is correctly registered in ScriptServiceRegistry and visible in run_js docs

# Implementation Progress
- [x] Created JSoupService with parseHtml, extractLinks, and select methods. [DONE]
- [x] Verified JSoup dependency in pom.xml. [DONE]
- [x] Implemented JSoupServiceTest and verified it passes. [DONE]
