package org.roxycode.app.ui.syntaxhighlight;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class JexlToHtmlConverterTest {

    @Test
    public void testKeywordHighlighting() {
        String script = "var x = 10;";
        String html = JexlToHtmlConverter.convert(script);
        assertTrue(html.contains("var"));
        assertTrue(html.contains("class=\'tk-rw\'"));
    }

    @Test
    public void testStringHighlighting() {
        String script = "var s = \"hello\";";
        String html = JexlToHtmlConverter.convert(script);
        assertTrue(html.contains("hello"));
        assertTrue(html.contains("class=\'tk-st\'"));
    }

    @Test
    public void testCommentHighlighting() {
        String script = "// This is a comment";
        String html = JexlToHtmlConverter.convert(script);
        assertTrue(html.contains("This is a comment"));
        assertTrue(html.contains("class=\'tk-cm\'"));
    }

    @Test
    public void testNumberHighlighting() {
        String script = "x = 42;";
        String html = JexlToHtmlConverter.convert(script);
        assertTrue(html.contains("42"));
        assertTrue(html.contains("class=\'tk-nm\'"));
    }

    @Test
    public void testWordBoundaries() {
        String script = "variable = 1;";
        String html = JexlToHtmlConverter.convert(script);
        assertFalse(html.contains("<span class=\'tk-rw\'>var</span>iable"));
    }

    @Test
    public void testNoPreWrapping() {
        String script = "var x = 1;";
        String html = JexlToHtmlConverter.convert(script);
        assertFalse(html.startsWith("<pre>"));
        assertFalse(html.endsWith("</pre>"));
    }
}