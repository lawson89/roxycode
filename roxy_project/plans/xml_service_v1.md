# XML Service v1

Plan for a feature similar to `JavaService` that allows summary information and editing of XML documents without search/replace or regex.

## Requirements
- Provide a summary of an XML document (root element, namespaces, structure).
- Retrieve the source (XML) of a specific element identified by XPath.
- Replace an element identified by XPath with new XML content.
- Update attributes of elements identified by XPath.
- Perform edits using structured APIs (DOM/XPath) to avoid fragile regex-based editing.

## Proposed Architecture
The `XmlService` will be a Micronaut `@Singleton` service in the `org.roxycode.core.tools.service` package.

### Data Structures
- `XmlFileSummary`: Contains root element name, namespaces, and a summary of child elements.
- `ElementSummary`: Name, XPath, attributes summary.

### Service Methods
- `XmlFileSummary analyzeFile(Path path)`
- `Optional<String> getElementSource(Path path, String xpath)`
- `void replaceElement(Path path, String xpath, String newXml)`
- `void updateAttribute(Path path, String xpath, String attrName, String attrValue)`

## Implementation Steps

### Phase 1: Core Service
- [x] Create `XmlService.java` in `src/main/java/org/roxycode/core/tools/service/`.
- [x] Implement `analyzeFile` using `DocumentBuilderFactory` and `XPath`.
- [x] Implement `getElementSource` using `XPath` and `Transformer` (for XML to String).
- [x] Implement `replaceElement` using `XPath` and DOM manipulation.
- [x] Implement `updateAttribute` using `XPath` and DOM manipulation.
- [x] Ensure XML output is formatted (pretty-printed).

### Phase 2: Unit Testing
- [x] Create `XmlServiceTest.java` in `src/test/java/org/roxycode/core/service/`.
- [x] Test analysis of various XML structures.
- [x] Test element retrieval and replacement.
- [x] Test attribute updates.

### Phase 3: Tool Integration
- [x] Create `xml_analyze_file.toml` and `xml_analyze_file.js` in `roxy_home/tools/`.
- [x] Create `xml_get_element_source.toml` and `xml_get_element_source.js` in `roxy_home/tools/`.
- [x] Create `xml_replace_element.toml` and `xml_replace_element.js` in `roxy_home/tools/`.
- [x] Create `xml_update_attribute.toml` and `xml_update_attribute.js` in `roxy_home/tools/`.

## Progress
- [x] Phase 1: Core Service
- [x] Phase 2: Unit Testing
- [x] Phase 3: Tool Integration
