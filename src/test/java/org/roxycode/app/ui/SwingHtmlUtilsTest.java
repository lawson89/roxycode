package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;

import org.junit.jupiter.api.Test;

class SwingHtmlUtilsTest {

    @Test
    void testRenderMarkdownToHtml() {
        assertEquals("", SwingHtmlUtils.renderMarkdownToHtml(null));
        assertEquals("", SwingHtmlUtils.renderMarkdownToHtml(""));

        String html = SwingHtmlUtils.renderMarkdownToHtml("""
                # Heading

                | Name | Value |
                | :--- | ---: |
                | Key | 42 |

                ~~~java
                public class Demo {}
                ~~~
                """);
        assertContainsAll(html, "<h1>Heading</h1>", "md-table", "cellpadding", "md-th", "md-td",
                "<span class='mtk-keyword'>public</span>");
    }

    @Test
    void testStyleMarkdownTablesViaFlexmarkAttributeProviderDoesNotMutateCodeOrInlineHtml() {
        String rendered = SwingHtmlUtils.renderMarkdownToHtml("""
                | Col A | Col B |
                | :--- | ---: |
                | Val 1 | Val 2 |

                <table><tr><td>Inline raw HTML table</td></tr></table>

                ~~~html
                <table>
                  <tr><th>Header</th><td>Cell</td></tr>
                </table>
                ~~~
                """);
        assertContainsAll(rendered, "md-table", "md-th", "md-td", "right",
                "<table><tr><td>Inline raw HTML table</td></tr></table>", "&lt;table&gt;");
    }
}
