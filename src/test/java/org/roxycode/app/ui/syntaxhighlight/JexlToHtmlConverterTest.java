package org.roxycode.app.ui.syntaxhighlight;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class JexlToHtmlConverterTest {

    @Test
    public void testKeywordHighlighting() {
        String script = "var x = 10;";
        String html = JexlToHtmlConverter.convert(script);
        assertTrue(html.contains("var"));
        // Check for font color tag
        assertTrue(html.contains("color='#C678DD'"));
        assertTrue(html.contains("<b>var</b>"));
    }

    @Test
    public void testStringHighlighting() {
        String script = "var s = \"hello\";";
        String html = JexlToHtmlConverter.convert(script);
        assertTrue(html.contains("hello"));
        assertTrue(html.contains("color='#98C379'"));
    }

    @Test
    public void testCommentHighlighting() {
        String script = "// This is a comment";
        String html = JexlToHtmlConverter.convert(script);
        assertTrue(html.contains("This is a comment"));
        assertTrue(html.contains("color='#5C6370'"));
    }

    @Test
    public void testNumberHighlighting() {
        String script = "x = 42;";
        String html = JexlToHtmlConverter.convert(script);
        assertTrue(html.contains("42"));
        assertTrue(html.contains("color='#D19A66'"));
    }
    
    @Test
    public void testWordBoundaries() {
        // "variable" should NOT be highlighted as "var"
        String script = "variable = 1;";
        String html = JexlToHtmlConverter.convert(script);
        assertFalse(html.contains("<b>var</b>iable"), "Keyword 'var' should not match inside 'variable'");
    }
}