package org.roxycode.app.ui;

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
        String script = "var s = 'hello';";
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

    @Test
    public void testHighlightCodeBlocksWithLanguageClass() {
        String inputHtml = "<p>Intro</p><pre><code class='language-java'>public class App { int count = 42; }</code></pre><p>Outro</p>";
        String result = JexlToHtmlConverter.highlightCodeBlocks(inputHtml);
        assertTrue(result.contains("<p>Intro</p>"));
        assertTrue(result.contains("<p>Outro</p>"));
        assertTrue(result.contains("<pre><code class='language-java'>"));
        assertTrue(result.contains("<span class='tk-rw'>public</span>"));
        assertTrue(result.contains("<span class='tk-rw'>class</span>"));
        assertTrue(result.contains("<span class='tk-rw'>int</span>"));
        assertTrue(result.contains("<span class='tk-nm'>42</span>"));
    }

    @Test
    public void testHighlightCodeBlocksPlainPre() {
        String inputHtml = "<pre>var y = 'world';</pre>";
        String result = JexlToHtmlConverter.highlightCodeBlocks(inputHtml);
        assertTrue(result.contains("<pre><code>"));
        assertTrue(result.contains("<span class='tk-rw'>var</span>"));
        assertTrue(result.contains("class='tk-st'"));
    }

    @Test
    public void testHighlightCodeBlocksMultiple() {
        String inputHtml = "<div><pre><code>int a = 1;</code></pre><p>mid</p><pre><code>int b = 2;</code></pre></div>";
        String result = JexlToHtmlConverter.highlightCodeBlocks(inputHtml);
        assertTrue(result.contains("<span class='tk-rw'>int</span> a = <span class='tk-nm'>1</span>;"));
        assertTrue(result.contains("<p>mid</p>"));
        assertTrue(result.contains("<span class='tk-rw'>int</span> b = <span class='tk-nm'>2</span>;"));
    }

    @Test
    public void testHighlightCodeBlocksNullAndEmpty() {
        assertEquals("", JexlToHtmlConverter.highlightCodeBlocks(null));
        assertEquals("", JexlToHtmlConverter.highlightCodeBlocks(""));
        assertEquals("<p>plain text</p>", JexlToHtmlConverter.highlightCodeBlocks("<p>plain text</p>"));
    }
}
