package org.roxycode.core.tools.service;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.core.Sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class XmlServiceTest {

    @Inject
    XmlService xmlService;

    @Inject
    Sandbox sandbox;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        sandbox.setRoot(tempDir);
    }

    @Test
    void testAnalyzeFile() throws Exception {
        String filename = "test.xml";
        Path xmlFile = tempDir.resolve(filename);
        Files.writeString(xmlFile, "<root><child1>text</child1><child2><subchild/></child2></root>");

        XmlService.XmlFileSummary summary = xmlService.analyzeFile(filename);

        assertEquals("root", summary.rootElement());
        assertEquals(4, summary.elements().size());
        assertEquals("/root[1]", summary.elements().get(0).xpath());
        assertEquals("/root[1]/child1[1]", summary.elements().get(1).xpath());
        assertEquals("/root[1]/child2[1]", summary.elements().get(2).xpath());
        assertEquals("/root[1]/child2[1]/subchild[1]", summary.elements().get(3).xpath());
    }

    @Test
    void testGetElementSource() throws Exception {
        String filename = "test.xml";
        Path xmlFile = tempDir.resolve(filename);
        Files.writeString(xmlFile, "<root><child1>text</child1></root>");

        Optional<String> source = xmlService.getElementSource(filename, "/root/child1");

        assertTrue(source.isPresent());
        assertEquals("<child1>text</child1>", source.get());
    }

    @Test
    void testReplaceElement() throws Exception {
        String filename = "test.xml";
        Path xmlFile = tempDir.resolve(filename);
        Files.writeString(xmlFile, "<root><child1>text</child1></root>");

        xmlService.replaceElement(filename, "/root/child1", "<newchild>newtext</newchild>");

        String content = Files.readString(xmlFile);
        assertTrue(content.contains("<newchild>newtext</newchild>"));
        assertFalse(content.contains("<child1>"));
    }

    @Test
    void testUpdateAttribute() throws Exception {
        String filename = "test.xml";
        Path xmlFile = tempDir.resolve(filename);
        Files.writeString(xmlFile, "<root><child1 id=\"1\">text</child1></root>");

        xmlService.updateAttribute(filename, "/root/child1", "id", "2");

        String content = Files.readString(xmlFile);
        assertTrue(content.contains("id=\"2\""));
    }
}
