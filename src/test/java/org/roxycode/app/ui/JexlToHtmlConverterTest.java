package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;

import org.junit.jupiter.api.Test;

public class JexlToHtmlConverterTest {

    @Test
    public void testSyntaxHighlightingTokensKeywordsAndServices() {
        String script = """
                // Single-line comment
                public interface GitService { void status(); }
                var x = 42;
                var s = 'hello';
                gitService.status();
                planService.createPlan();
                fileReadService.readFile();
                grepService.grep();
                workflowService.status();
                otherHelper.run();
                """;
        String html = JexlToHtmlConverter.convert(script);
        assertContainsAll(html, "<span class='mtk-comment'>// Single-line comment</span>",
                "<span class='mtk-keyword'>public</span>", "<span class='mtk-keyword'>interface</span>",
                "<span class='mtk-keyword'>void</span>", "<span class='mtk-keyword'>var</span>",
                "<span class='mtk-number'>42</span>", "<span class='mtk-string'>'hello'</span>",
                "<span class='mtk-keyword'>gitService</span>", "<span class='mtk-keyword'>planService</span>",
                "<span class='mtk-keyword'>fileReadService</span>", "<span class='mtk-keyword'>grepService</span>",
                "<span class='mtk-keyword'>workflowService</span>");
        assertFalse(html.contains("<span class='mtk-keyword'>otherHelper</span>"));
        assertFalse(html.startsWith("<pre>"));
        assertFalse(html.endsWith("</pre>"));
    }

    @Test
    public void testHighlightMethod() {
        String code = "var x = 42;";
        String html = JexlToHtmlConverter.highlight(code, "jexl");
        assertTrue(html.startsWith("<pre class='jexl-highlight'><code>"));
        assertTrue(html.endsWith("</code></pre>"));
        assertContainsAll(html, "<span class='mtk-keyword'>var</span>", "<span class='mtk-number'>42</span>");
    }

    @Test
    public void testHighlightMethodProse() {
        String code = "var x = 42;";
        String html = JexlToHtmlConverter.highlight(code, "markdown");
        assertTrue(html.contains("<pre class='jexl-highlight'><code>var x = 42;</code></pre>"));
        assertFalse(html.contains("mtk-"));
    }

    @Test
    public void testMultilineCommentsAndHashComments() {
        String script = String.join("\n", "/* line 1", "line 2 */", "var s = 'a < b && c > d';", "# JEXL hash comment");
        String html = JexlToHtmlConverter.convert(script);
        assertContainsAll(html, "<span class='mtk-comment'>/* line 1</span>",
                "<span class='mtk-comment'>line 2 */</span>", "<span class='mtk-keyword'>var</span>",
                "<span class='mtk-string'>'a &lt; b &amp;&amp; c &gt; d'</span>",
                "<span class='mtk-comment'># JEXL hash comment</span>");
    }
}