package org.roxycode.app.ui.syntaxhighlight;

import org.apache.commons.text.StringEscapeUtils;

/**
 * Utility class to convert JEXL script text to syntax-highlighted HTML.
 * Optimized for display in Swing JTextPane.
 */
public class JexlToHtmlConverter {

    private static final String KEYWORD_COLOR = "#C678DD"; // Purple
    private static final String STRING_COLOR = "#98C379";  // Green
    private static final String COMMENT_COLOR = "#5C6370"; // Grey
    private static final String NUMBER_COLOR = "#D19A66";  // Orange

    private static final String[] KEYWORDS = {
        "var", "let", "const", "if", "else", "for", "while", "do", "break", "continue", 
        "return", "function", "new", "null", "true", "false", "empty", "size", 
        "and", "or", "not", "mod", "div", "instanceof"
    };

    /**
     * Converts a raw JEXL script into highlighted HTML.
     * @param script The JEXL code to highlight.
     * @return HTML string with font tags for styling.
     */
    public static String convert(String script) {
        if (script == null) return "";
        
        // Escape HTML entities first to avoid injecting tags from the script itself
        String escaped = StringEscapeUtils.escapeHtml4(script);
        
        String processed = escaped;
        
        // Strings: &quot;...&quot; or &apos;...&apos;
        processed = processed.replaceAll("(&quot;.*?&quot;)", "<font color='" + STRING_COLOR + "'>$1</font>");
        processed = processed.replaceAll("(&apos;.*?&apos;)", "<font color='" + STRING_COLOR + "'>$1</font>");
        
        // Comments: // or ##
        processed = processed.replaceAll("(//.*|##.*)", "<font color='" + COMMENT_COLOR + "'>$1</font>");

        // Keywords - Use lookarounds to define word boundaries that work after HTML escaping
        for (String kw : KEYWORDS) {
            processed = processed.replaceAll("(?<![a-zA-Z0-9_])" + kw + "(?![a-zA-Z0-9_])", 
                "<font color='" + KEYWORD_COLOR + "'><b>" + kw + "</b></font>");
        }
        
        // Numbers
        processed = processed.replaceAll("(?<![a-zA-Z0-9_])([0-9]+)(?![a-zA-Z0-9_])", 
            "<font color='" + NUMBER_COLOR + "'>$1</font>");

        // Wrap in a pre tag for monospaced formatting
        return "<pre style='font-family: monospaced; font-size: 11pt; color: #ABB2BF; background-color: #282C34; padding: 10px; margin: 0;'>" 
             + processed + "</pre>";
    }
}