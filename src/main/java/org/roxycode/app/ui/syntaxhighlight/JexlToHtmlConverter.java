package org.roxycode.app.ui.syntaxhighlight;

import org.apache.commons.text.StringEscapeUtils;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class to convert JEXL script text to syntax-highlighted HTML.
 * Optimized for display in MarkdownPane using CSS classes.
 */
public class JexlToHtmlConverter {

    private static final String[] KEYWORDS = {
        "var", "let", "const", "if", "else", "for", "while", "do", "break", "continue",
        "return", "function", "new", "null", "true", "false", "empty", "size",
        "and", "or", "not", "mod", "div", "instanceof", "switch", "case", "default",
        "try", "catch", "finally", "throw", "NaN",
        // Services
        "gitService", "genericBuildToolService", "planService", "exportService", "textEditorService",
        "fileReadService", "fileEditorService", "workflowService", "planManager", "exploreManager",
        // Java API Keywords
        "public", "private", "protected", "interface", "class", "record", "void",
        "package", "import", "static", "final", "extends", "implements", "throws"
    };

    private static final String KEYWORDS_PATTERN = "(" + String.join("|", KEYWORDS) + ")";
    private static final String REGEX = "(//.*|##.*)|(&quot;.*?&quot;|&#39;.*?&#39;|&apos;.*?&apos;|'.*?')|(?<![a-zA-Z0-9_])" + KEYWORDS_PATTERN + "(?![a-zA-Z0-9_])|(?<![a-zA-Z0-9_])([0-9]+)(?![a-zA-Z0-9_])";
    private static final Pattern PATTERN = Pattern.compile(REGEX);

    /**
     * Converts a raw JEXL script into highlighted HTML with CSS classes.
     * @param script The JEXL code to highlight.
     * @return HTML string with span tags for styling.
     */
    public static String convert(String script) {
        if (script == null) {
            return "";
        }
        
        String escaped = StringEscapeUtils.escapeHtml4(script);
        Matcher matcher = PATTERN.matcher(escaped);
        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;
        
        while (matcher.find()) {
            sb.append(escaped, lastEnd, matcher.start());
            if (matcher.group(1) != null) {
                sb.append("<span class='tk-cm'>").append(matcher.group(1)).append("</span>");
            } else if (matcher.group(2) != null) {
                sb.append("<span class='tk-st'>").append(matcher.group(2)).append("</span>");
            } else if (matcher.group(3) != null) {
                sb.append("<span class='tk-rw'>").append(matcher.group(3)).append("</span>");
            } else if (matcher.group(4) != null) {
                sb.append("<span class='tk-nm'>").append(matcher.group(4)).append("</span>");
            }
            lastEnd = matcher.end();
        }
        sb.append(escaped.substring(lastEnd));
        
        return sb.toString();
    }
}