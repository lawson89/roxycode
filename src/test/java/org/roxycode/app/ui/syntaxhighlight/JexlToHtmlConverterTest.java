package org.roxycode.app.ui.syntaxhighlight;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class JexlToHtmlConverterTest {

    @Test
    public void testKeywordHighlighting() {
        String script = "var x = 10;";
        String html = JexlToHtmlConverter.convert(script);
        assertTrue(html.contains("var"));
        assertTrue(html.contains("class='tk-rw'"));
    }

    @Test
    public void testStringHighlighting() {
        String script = "var s = \"hello\";";
        String html = JexlToHtmlConverter.convert(script);
        assertTrue(html.contains("hello"));
        assertTrue(html.contains("class='tk-st'"));
    }

    @Test
    public void testCommentHighlighting() {
        String script = "// This is a comment";
        String html = JexlToHtmlConverter.convert(script);
        assertTrue(html.contains("This is a comment"));
        assertTrue(html.contains("class='tk-cm'"));
    }

    @Test
    public void testNumberHighlighting() {
        String script = "x = 42;";
        String html = JexlToHtmlConverter.convert(script);
        assertTrue(html.contains("42"));
        assertTrue(html.contains("class='tk-nm'"));
    }

    @Test
    public void testWordBoundaries() {
        String script = "variable = 1;";
        String html = JexlToHtmlConverter.convert(script);
        assertFalse(html.contains("<span class='tk-rw'>var</span>iable"));
    }

    @Test
    public void testNoPreWrapping() {
        String script = "var x = 1;";
        String html = JexlToHtmlConverter.convert(script);
        assertFalse(html.startsWith("<pre>"));
        assertFalse(html.endsWith("</pre>"));
    }

    @Test
    public void testJavaKeywordHighlighting() {
        String script = "public interface GitService { void status(); }";
        String html = JexlToHtmlConverter.convert(script);
        assertTrue(html.contains("<span class='tk-rw'>public</span>"));
        assertTrue(html.contains("<span class='tk-rw'>interface</span>"));
        assertTrue(html.contains("<span class='tk-rw'>void</span>"));
    }

    @Test
    public void testServiceHighlighting() {
        String script = "gitService.status(); planService.createPlan();";
        String html = JexlToHtmlConverter.convert(script);
        assertTrue(html.contains("<span class='tk-rw'>gitService</span>"));
        assertTrue(html.contains("<span class='tk-rw'>planService</span>"));
    }

    @Test
    public void testNewServiceHighlighting() {
        String script = "fileReadService.readFile(); workflowService.status();";
        String html = JexlToHtmlConverter.convert(script);
        assertTrue(html.contains("<span class='tk-rw'>fileReadService</span>"));
        assertTrue(html.contains("<span class='tk-rw'>workflowService</span>"));
    }

    @Test
    public void testSingleQuoteHighlighting() {
        // Test single quotes in raw JEXL
        String script = "var s = 'hello';";
        String html = JexlToHtmlConverter.convert(script);
        assertTrue(html.contains("hello"));
        assertTrue(html.contains("class='tk-st'"));
    }
}