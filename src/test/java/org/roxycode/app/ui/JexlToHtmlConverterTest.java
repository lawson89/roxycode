package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;

import org.junit.jupiter.api.Test;

public class JexlToHtmlConverterTest {

    @Test
    public void testKeywordHighlighting() {
        assertContainsAll(JexlToHtmlConverter.convert("var x = 10;"), "var", "class='tk-rw'");
    }

    @Test
    public void testStringHighlighting() {
        assertContainsAll(JexlToHtmlConverter.convert("var s = 'hello';"), "hello", "class='tk-st'");
    }

    @Test
    public void testCommentHighlighting() {
        assertContainsAll(JexlToHtmlConverter.convert("// This is a comment"), "This is a comment", "class='tk-cm'");
    }

    @Test
    public void testNumberHighlighting() {
        assertContainsAll(JexlToHtmlConverter.convert("x = 42;"), "42", "class='tk-nm'");
    }

    @Test
    public void testWordBoundaries() {
        assertFalse(JexlToHtmlConverter.convert("variable = 1;").contains("<span class='tk-rw'>var</span>iable"));
    }

    @Test
    public void testNoPreWrapping() {
        String html = JexlToHtmlConverter.convert("var x = 1;");
        assertFalse(html.startsWith("<pre>"));
        assertFalse(html.endsWith("</pre>"));
    }

    @Test
    public void testJavaKeywordHighlighting() {
        assertContainsAll(JexlToHtmlConverter.convert("public interface GitService { void status(); }"),
                "<span class='tk-rw'>public</span>", "<span class='tk-rw'>interface</span>",
                "<span class='tk-rw'>void</span>");
    }

    @Test
    public void testServiceHighlighting() {
        assertContainsAll(JexlToHtmlConverter.convert("gitService.status(); planService.createPlan();"),
                "<span class='tk-rw'>gitService</span>", "<span class='tk-rw'>planService</span>");
    }

    @Test
    public void testNewServiceHighlighting() {
        String html = JexlToHtmlConverter.convert(
                "fileReadService.readFile(); grepService.grep(); workflowService.status(); otherHelper.run();");
        assertContainsAll(html, "<span class='tk-rw'>fileReadService</span>", "<span class='tk-rw'>grepService</span>",
                "<span class='tk-rw'>workflowService</span>");
        assertFalse(html.contains("<span class='tk-rw'>otherHelper</span>"));
    }

    @Test
    public void testSingleQuoteHighlighting() {
        assertContainsAll(JexlToHtmlConverter.convert("var s = 'hello';"), "hello", "class='tk-st'");
    }

    @Test
    public void testHighlightCodeBlocksWithLanguageClass() {
        String result = JexlToHtmlConverter.highlightCodeBlocks(
                "<p>Intro</p><pre><code class='language-java'>public class App { int count = 42; }</code></pre><p>Outro</p>");
        assertContainsAll(result, "<p>Intro</p>", "<p>Outro</p>", "<pre><code class='language-java'>",
                "<span class='tk-rw'>public</span>", "<span class='tk-rw'>class</span>",
                "<span class='tk-rw'>int</span>", "<span class='tk-nm'>42</span>");
    }

    @Test
    public void testHighlightCodeBlocksPlainPre() {
        assertContainsAll(JexlToHtmlConverter.highlightCodeBlocks("<pre>var y = 'world';</pre>"), "<pre><code>",
                "<span class='tk-rw'>var</span>", "class='tk-st'");
    }

    @Test
    public void testHighlightCodeBlocksMultiple() {
        assertContainsAll(
                JexlToHtmlConverter.highlightCodeBlocks(
                        "<div><pre><code>int a = 1;</code></pre><p>mid</p><pre><code>int b = 2;</code></pre></div>"),
                "<span class='tk-rw'>int</span> a = <span class='tk-nm'>1</span>;", "<p>mid</p>",
                "<span class='tk-rw'>int</span> b = <span class='tk-nm'>2</span>;");
    }

    @Test
    public void testHighlightCodeBlocksSkipsProseFormats() {
        String mdHtml = "<pre><code class='language-markdown'>Use var x = 1; and return true in default text</code></pre>";
        assertEquals(mdHtml, JexlToHtmlConverter.highlightCodeBlocks(mdHtml));

        String txtHtml = "<pre><code class='language-plaintext'>public class PlainText { int n = 42; }</code></pre>";
        assertEquals(txtHtml, JexlToHtmlConverter.highlightCodeBlocks(txtHtml));
    }

    @Test
    public void testHighlightCodeBlocksNullAndEmpty() {
        assertEquals("", JexlToHtmlConverter.highlightCodeBlocks(null));
        assertEquals("", JexlToHtmlConverter.highlightCodeBlocks(""));
        assertEquals("<p>plain text</p>", JexlToHtmlConverter.highlightCodeBlocks("<p>plain text</p>"));
    }

    @Test
    public void testMultilineCommentsBackticksAndHtmlEntities() {
        String script = String.join(System.lineSeparator(), "/* line 1", "line 2 */", "var s = 'a < b && c > d';",
                "## JEXL hash comment");
        String multiLine = JexlToHtmlConverter.convert(script);
        assertContainsAll(multiLine, "<span class='tk-cm'>/* line 1</span>", "<span class='tk-cm'>line 2 */</span>",
                "<span class='tk-rw'>var</span>", "<span class='tk-st'>'a &lt; b &amp;&amp; c &gt; d'</span>",
                "<span class='tk-cm'>## JEXL hash comment</span>");
    }
}
