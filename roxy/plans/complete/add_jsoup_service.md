# Goal
Add Jsoup library to the project and expose it as a script service to allow web scraping and HTML parsing in JS tools.

# Proposed Changes
- Add 'org.jsoup:jsoup:1.18.1' dependency to pom.xml.
- Create 'org.roxycode.core.tools.service.JsoupService' class.
- Annotate 'JsoupService' with '@ScriptService("jsoup")' and '@LLMDoc'.
- Implement 'fetch(url)' and 'parse(html)' methods in 'JsoupService' using Jsoup.

# Implementation Steps
- [ ] Add Jsoup dependency to pom.xml.
- [ ] Create JsoupService.java in 'src/main/java/org/roxycode/core/tools/service/'.
- [ ] Run tests to ensure everything is working correctly (create a simple test for JsoupService).

# Implementation Progress
