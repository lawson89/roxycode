package org.roxycode.core.tools.service;

import jakarta.inject.Singleton;
import org.roxycode.core.tools.LLMDoc;
import org.roxycode.core.tools.ScriptService;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ScriptService("xmlService")
@Singleton
public class XmlService {

    public record XmlFileSummary(String rootElement, List<ElementSummary> elements) {
    }

    public record ElementSummary(String name, String xpath, int depth) {
    }

    @LLMDoc("Analyzes an XML file and returns a structural summary of its elements")
    public XmlFileSummary analyzeFile(Path path) throws Exception {
        Document doc = loadDocument(path);
        Element root = doc.getDocumentElement();
        List<ElementSummary> elements = new ArrayList<>();
        summarizeRecursive(root, "", 0, elements);
        return new XmlFileSummary(root.getTagName(), elements);
    }

    private void summarizeRecursive(Element element, String parentPath, int depth, List<ElementSummary> elements) {
        String name = element.getTagName();
        int index = 1;
        Node sibling = element.getPreviousSibling();
        while (sibling != null) {
            if (sibling.getNodeType() == Node.ELEMENT_NODE && sibling.getNodeName().equals(name)) {
                index++;
            }
            sibling = sibling.getPreviousSibling();
        }

        String currentPath = parentPath + (parentPath.endsWith("/") ? "" : "/") + name + "[" + index + "]";
        elements.add(new ElementSummary(name, currentPath, depth));

        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                summarizeRecursive((Element) node, currentPath, depth + 1, elements);
            }
        }
    }

    @LLMDoc("Returns the XML source code of an element selected by an XPath expression")
    public Optional<String> getElementSource(Path path, String xpathExpr) throws Exception {
        Document doc = loadDocument(path);
        XPath xpath = XPathFactory.newInstance().newXPath();
        Node node = (Node) xpath.evaluate(xpathExpr, doc, XPathConstants.NODE);
        if (node == null) {
            return Optional.empty();
        }
        return Optional.of(nodeToString(node));
    }

    @LLMDoc("Replaces an XML element selected by an XPath expression with new XML content")
    public void replaceElement(Path path, String xpathExpr, String newXml) throws Exception {
        Document doc = loadDocument(path);
        XPath xpath = XPathFactory.newInstance().newXPath();
        Node node = (Node) xpath.evaluate(xpathExpr, doc, XPathConstants.NODE);
        if (node == null) {
            throw new RuntimeException("Element not found: " + xpathExpr);
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document newDoc = builder.parse(new ByteArrayInputStream(newXml.getBytes(StandardCharsets.UTF_8)));

        Node newNode = doc.importNode(newDoc.getDocumentElement(), true);
        node.getParentNode().replaceChild(newNode, node);

        saveDocument(doc, path);
    }

    @LLMDoc("Updates an attribute of an XML element selected by an XPath expression")
    public void updateAttribute(Path path, String xpathExpr, String attrName, String attrValue) throws Exception {
        Document doc = loadDocument(path);
        XPath xpath = XPathFactory.newInstance().newXPath();
        Node node = (Node) xpath.evaluate(xpathExpr, doc, XPathConstants.NODE);
        if (node == null || node.getNodeType() != Node.ELEMENT_NODE) {
            throw new RuntimeException("Element not found or not an element: " + xpathExpr);
        }

        ((Element) node).setAttribute(attrName, attrValue);
        saveDocument(doc, path);
    }

    private Document loadDocument(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // Disable external entities for security
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(path.toFile());
    }

    private void saveDocument(Document doc, Path path) throws Exception {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        DOMSource source = new DOMSource(doc);
        StreamResult result = new StreamResult(path.toFile());
        transformer.transform(source, result);
    }

    private String nodeToString(Node node) throws Exception {
        StringWriter sw = new StringWriter();
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        transformer.transform(new DOMSource(node), new StreamResult(sw));
        return sw.toString().trim();
    }
}
