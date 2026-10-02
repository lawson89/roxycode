package org.roxycode.app.ui.syntaxhighlight;

import org.apache.commons.text.StringEscapeUtils;

/**
 * Utility class to convert JEXL script text to syntax-highlighted HTML.
 * Optimized for display in MarkdownPane using CSS classes.
 */
public class JexlToHtmlConverter {

    private static final String[] KEYWORDS = {
        "var", "let", "const", "if", "else", "for", "while", "do", "break", "continue", 
        "return", "function", "new", "null", "true", "false", "empty", "size", 
        "and", "or", "not", "mod", "div", "instanceof"
    };

    /**
     * Converts a raw JEXL script into highlighted HTML with CSS classes.
     * @param script The JEXL code to highlight.
     * @return HTML string with span tags for styling.
     */
    public static String convert(String script) {
        if (script == null) return "";
        
        // Escape HTML entities first
        String escaped = StringEscapeUtils.escapeHtml4(script);
        
        String processed = escaped;
        
        // Strings
        processed = processed.replaceAll("(&quot;.*?&quot;)", "<span class='tk-st'>$1</span>");
        processed = processed.replaceAll("(&apos;.*?&apos;)", "<span class='tk-st'>$1</span>");
        
        // Comments
        processed = processed.replaceAll("(//.*|##.*)", "<span class='tk-cm'>$1</span>");

        // Keywords
        for (String kw : KEYWORDS) {
            processed = processed.replaceAll("(?<![a-zA-Z0-9_])" + kw + "(?![a-zA-Z0-9_])", 
                "<span class='tk-rw'>" + kw + "</span>");
        }
        
        // Numbers
        processed = processed.replaceAll("(?<![a-zA-Z0-9_])([0-9]+)(?![a-zA-Z0-9_])", 
            "<span class='tk-nm'>$1</span>");

        return processed;
    }
}