package org.roxycode.core.tools.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.core.Sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JSoupServiceTest {

    private JSoupService jsoupService;
    private Sandbox sandbox;
    private Path tempDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        this.tempDir = tempDir;
        this.sandbox = new Sandbox();
        this.sandbox.setRoot(tempDir);
        this.jsoupService = new JSoupService(sandbox);
    }

    @Test
    void testParseHtml() throws IOException {
        Path htmlFile = tempDir.resolve("test.html");
        String content = "<html><head><title>Test Title</title><meta name='description' content='Test Meta'></head>" +
                "<body><h1>Header 1</h1><h2>Header 2</h2></body></html>";
        Files.writeString(htmlFile, content);

        String summary = jsoupService.parseHtml("test.html");
        assertTrue(summary.contains("Title: Test Title"));
        assertTrue(summary.contains("meta name=\"description\""));
        assertTrue(summary.contains("h1: Header 1"));
        assertTrue(summary.contains("h2: Header 2"));
    }

    @Test
    void testExtractLinks() throws IOException {
        Path htmlFile = tempDir.resolve("links.html");
        String content = "<html><body><a href='http://example.com'>Example</a><a href='https://google.com'>Google</a></body></html>";
        Files.writeString(htmlFile, content);

        List<String> links = jsoupService.extractLinks("links.html");
        assertEquals(2, links.size());
        assertTrue(links.contains("http://example.com"));
        assertTrue(links.contains("https://google.com"));
    }

    @Test
    void testSelect() throws IOException {
        Path htmlFile = tempDir.resolve("select.html");
        String content = "<html><body><div class='test'>Content 1</div><div class='test'>Content 2</div><span>Other</span></body></html>";
        Files.writeString(htmlFile, content);

        List<String> elements = jsoupService.select("select.html", "div.test");
        assertEquals(2, elements.size());
        assertTrue(elements.get(0).contains("Content 1"));
        assertTrue(elements.get(1).contains("Content 2"));
    }
}
