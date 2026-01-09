package org.roxycode.ui.syntaxhighlight;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class JsToHtmlConverterTest {

    @Test
    void testConvertToHtml_LightMode() {
        String js = "const x = 10;";
        String html = JsToHtmlConverter.convertToHtml(js, false);
        assertNotNull(html);
        assertTrue(html.contains("const"));
        assertTrue(html.contains("x"));
        assertTrue(html.contains("10"));
        assertTrue(html.contains("color: #000080"));
    }

    @Test
    void testConvertToHtml_DarkMode() {
        String js = "const x = 10;";
        String html = JsToHtmlConverter.convertToHtml(js, true);
        assertNotNull(html);
        assertTrue(html.contains("color: #cc7832"));
    }

    @Test
    void testConvertToHtml_Multiline() {
        String js = "const x = 10;\nconst varY = 20;";
        String html = JsToHtmlConverter.convertToHtml(js, false);
        assertTrue(html.contains("x"));
        assertTrue(html.contains("varY"));
    }

    @Test
    void testConvertToHtml_MultilineComment() {
        String js = "/* multiline\n comment */";
        String html = JsToHtmlConverter.convertToHtml(js, false);
        assertTrue(html.contains("multiline"));
        assertTrue(html.contains("comment"));
    }
}
