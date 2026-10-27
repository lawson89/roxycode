package org.roxycode.app.ui;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.text.StringEscapeUtils;

@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class JexlToHtmlConverter {

    private static final String KEYWORDS_JOINED =
            "var|let|const|if|else|for|while|do|break|continue|return|function|new|null|true|false|empty|size|and|or|not|mod|div|instanceof|switch|case|default|try|catch|finally|throw|NaN|assert|"
                    + "public|private|protected|interface|class|record|void|enum|package|import|static|final|extends|implements|throws|super|this|abstract|synchronized|volatile|transient|boolean|int|long|double|float|short|byte|char|String|List|Map|Set|Optional|"
                    + "def|self|lambda|async|await|yield|None|True|False|from|as|typeof|fn";

    private static final String SERVICE_IDENTIFIER_PATTERN =
            "[a-zA-Z_][a-zA-Z0-9_]*[sS]ervice[a-zA-Z0-9_]*";
    private static final String KEYWORDS_PATTERN =
            "(" + KEYWORDS_JOINED + "|" + SERVICE_IDENTIFIER_PATTERN + ")";
    private static final String REGEX =
            "(//.*|##.*)|(&quot;.*?&quot;|&#39;.*?&#39;|&apos;.*?&apos;|'.*?'|"
                    + (char) 96
                    + ".*?"
                    + (char) 96
                    + "|"
                    + '"'
                    + ".*?"
                    + '"'
                    + ")|(?<![a-zA-Z0-9_])"
                    + KEYWORDS_PATTERN
                    + "(?![a-zA-Z0-9_])|(?<![a-zA-Z0-9_&#])([0-9]+)(?![a-zA-Z0-9_])";
    private static final Pattern PATTERN = Pattern.compile(REGEX);
    private static final Pattern PRE_PATTERN =
            Pattern.compile("<pre([^>]*)>(.*?)</pre>", Pattern.DOTALL);
    private static final Pattern CODE_PATTERN =
            Pattern.compile("^\\s*<code([^>]*)>(.*?)</code>\\s*$", Pattern.DOTALL);
    private static final Pattern PROSE_LANG_PATTERN =
            Pattern.compile(
                    "\\blanguage-(?:markdown|md|text|plaintext|txt)\\b", Pattern.CASE_INSENSITIVE);

    public static String convert(String script) {
        return script == null ? "" : highlightHtml(StringEscapeUtils.escapeHtml4(script));
    }

    public static String highlightHtml(String escapedCode) {
        if (escapedCode == null) return "";
        Matcher matcher = PATTERN.matcher(escapedCode);
        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;
        String[] classes = {"tk-cm", "tk-st", "tk-rw", "tk-nm"};
        while (matcher.find()) {
            sb.append(escapedCode, lastEnd, matcher.start());
            for (int g = 1; g <= 4; g++) {
                if (matcher.group(g) != null) {
                    sb.append("<span class='")
                            .append(classes[g - 1])
                            .append("'>")
                            .append(matcher.group(g))
                            .append("</span>");
                    break;
                }
            }
            lastEnd = matcher.end();
        }
        return sb.append(escapedCode.substring(lastEnd)).toString();
    }

    public static String highlightCodeBlocks(String html) {
        if (html == null) return "";
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
                String highlighted =
                        isProseLanguage(codeAttrs) ? codeContent : highlightHtml(codeContent);
                sb.append("<pre")
                        .append(preAttrs)
                        .append("><code")
                        .append(codeAttrs)
                        .append(">")
                        .append(highlighted)
                        .append("</code></pre>");
            } else {
                sb.append("<pre")
                        .append(preAttrs)
                        .append("><code>")
                        .append(highlightHtml(inner))
                        .append("</code></pre>");
            }
            lastEnd = preMatcher.end();
        }
        return sb.append(html.substring(lastEnd)).toString();
    }

    private static boolean isProseLanguage(String codeAttrs) {
        return codeAttrs != null && PROSE_LANG_PATTERN.matcher(codeAttrs).find();
    }
}
