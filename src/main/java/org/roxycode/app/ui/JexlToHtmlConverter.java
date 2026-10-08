package org.roxycode.app.ui;

import org.apache.commons.text.StringEscapeUtils;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class to convert JEXL and programming script text to syntax-highlighted HTML.
 * Optimized for display in MarkdownPane and MarkdownViewer using CSS classes.
 */
public class JexlToHtmlConverter {

    private static final String[] KEYWORDS = {
        // Control flow & core keywords
        "var", "let", "const", "if", "else", "for", "while", "do", "break", "continue",
        "return", "function", "new", "null", "true", "false", "empty", "size",
        "and", "or", "not", "mod", "div", "instanceof", "switch", "case", "default",
        "try", "catch", "finally", "throw", "NaN", "assert",
        // Services
        "gitService", "genericBuildToolService", "buildToolService", "planService", "exportService",
        "textEditorService", "fileReadService", "fileEditorService", "workflowService", "planManager",
        "exploreManager", "grepService", "geminiCacheService", "repoMapPackerService",
        "projectService", "settingsService", "promptService", "toolService", "envService",
        // Java API Keywords & Types
        "public", "private", "protected", "interface", "class", "record", "void", "enum",
        "package", "import", "static", "final", "extends", "implements", "throws", "super", "this",
        "abstract", "synchronized", "volatile", "transient", "boolean", "int", "long", "double", "float",
        "short", "byte", "char", "String", "List", "Map", "Set", "Optional",
        // Python / JS / Other keywords
        "def", "self", "lambda", "async", "await", "yield", "None", "True", "False", "from", "as", "typeof", "fn"
    };

    private static final String KEYWORDS_PATTERN = "(" + String.join("|", KEYWORDS) + ")";
    private static final String REGEX = "(//.*|##.*)|(&quot;.*?&quot;|&#39;.*?&#39;|&apos;.*?&apos;|'.*?'|`.*?`|\".*?\")|(?<![a-zA-Z0-9_])" + KEYWORDS_PATTERN + "(?![a-zA-Z0-9_])|(?<![a-zA-Z0-9_&#])([0-9]+)(?![a-zA-Z0-9_])";
    private static final Pattern PATTERN = Pattern.compile(REGEX);

    private static final Pattern PRE_PATTERN = Pattern.compile("<pre([^>]*)>(.*?)</pre>", Pattern.DOTALL);
    private static final Pattern CODE_PATTERN = Pattern.compile("^\\s*<code([^>]*)>(.*?)</code>\\s*$", Pattern.DOTALL);

    /**
     * Converts a raw JEXL or code script into highlighted HTML with CSS classes.
     * @param script The raw code to highlight.
     * @return HTML string with span tags for styling.
     */
    public static String convert(String script) {
        if (script == null) {
            return "";
        }
        String escaped = StringEscapeUtils.escapeHtml4(script);
        return highlightHtml(escaped);
    }

    /**
     * Highlights syntax tokens within an already HTML-escaped code snippet.
     * @param escapedCode The HTML-escaped code snippet.
     * @return HTML string with span tags for tokens.
     */
        public static String highlightHtml(String escapedCode) {
        if (escapedCode == null) {
            return "";
        }
        Matcher matcher = PATTERN.matcher(escapedCode);
        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;

                while (matcher.find()) {
            sb.append(escapedCode, lastEnd, matcher.start());
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

        sb.append(escapedCode.substring(lastEnd));

        return sb.toString();
    }

    /**
     * Finds &lt;pre&gt; and &lt;code&gt; blocks within an HTML document and applies syntax highlighting.
     * @param html The HTML document or fragment (e.g. from Markdown rendering).
     * @return The HTML with code blocks syntax highlighted.
     */
    public static String highlightCodeBlocks(String html) {
        if (html == null) {
            return "";
        }
        Matcher preMatcher = PRE_PATTERN.matcher(html);
        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;

        while (preMatcher.find()) {
            sb.append(html, lastEnd, preMatcher.start());
            String preAttrs = preMatcher.group(1);
            String inner = preMatcher.group(2);

            Matcher codeMatcher = CODE_PATTERN.matcher(inner);
            if (codeMatcher.matches()) {
                String codeAttrs = codeMatcher.group(1);
                String codeContent = codeMatcher.group(2);
                String highlighted = highlightHtml(codeContent);
                sb.append("<pre").append(preAttrs).append("><code").append(codeAttrs).append(">")
                  .append(highlighted)
                  .append("</code></pre>");
            } else {
                String highlighted = highlightHtml(inner);
                sb.append("<pre").append(preAttrs).append("><code>")
                  .append(highlighted)
                  .append("</code></pre>");
            }
            lastEnd = preMatcher.end();
        }
        sb.append(html.substring(lastEnd));

        return sb.toString();
    }

}
