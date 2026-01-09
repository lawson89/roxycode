# Goal
Create a new view in RoxyCode to display the API documentation for script services.

# Proposed Changes
- Create src/main/resources/org/roxycode/ui/views/ApiDocsView.xml with a layout for displaying documentation.
- Create src/main/java/org/roxycode/ui/views/ApiDocsView.java to handle loading and refreshing the API docs using a MarkdownPane.
- Modify src/main/resources/org/roxycode/ui/MainFrame.xml to add a navigation button for the API Docs view.
- Modify src/main/java/org/roxycode/ui/MainFrame.java to inject and show the new view, and register its markdown pane for theme updates.

# Implementation Steps
- [ ] Create the XML layout file for the API Docs view.
- [ ] Implement the ApiDocsView Java class, wiring it to ScriptServiceRegistry.
- [ ] Add the 'API Docs' toggle button to the main navigation menu in MainFrame.xml.
- [ ] Integrate ApiDocsView into MainFrame.java: inject it, add to stack, set icon, and add navigation logic.
- [ ] Verify the view works correctly and displays the expected documentation.

# Implementation Progress
- [x] Created `ApiDocsView.java` and `ApiDocsView.xml`.
- [x] Modified `MainFrame.java` to include `ApiDocsView` as a new tab.
- [x] Updated `UISchedulerService.java` to refresh `ApiDocsView`.
- [x] Verified compilation.
- [x] Work confirmed complete by user.
