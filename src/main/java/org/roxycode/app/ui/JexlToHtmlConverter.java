package org.roxycode.app.ui;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.text.Segment;
import org.apache.commons.text.StringEscapeUtils;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.fife.ui.rsyntaxtextarea.modes.JavaScriptTokenMaker;

@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class JexlToHtmlConverter {

    private static final char LF = 10;
    private static final char CR = 13;
    private static final char SQUOTE = 39;
    private static final char DQUOTE = 34;
    private static final char BACKSLASH = 92;
    private static final char BACKTICK = 96;
    private static final Pattern SERVICE_IDENTIFIER_PATTERN = Pattern
            .compile("[a-zA-Z_][a-zA-Z0-9_]*[sS]ervice[a-zA-Z0-9_]*");
    private static final Set<String> EXTRA_KEYWORDS = Set.of("and", "or", "not", "mod", "div", "empty", "size",
            "assert", "interface", "record", "String", "List", "Map", "Set", "Optional", "int", "long", "double",
            "float", "short", "byte", "char", "boolean", "def", "self", "lambda", "None", "True", "False", "from", "as",
            "fn");
    private static final Pattern PRE_PATTERN = Pattern.compile("<pre([^>]*)>(.*?)</pre>", Pattern.DOTALL);
    private static final Pattern CODE_PATTERN = Pattern.compile("^[ \t\r\n]*<code([^>]*)>(.*?)</code>[ \t\r\n]*$",
            Pattern.DOTALL);
    private static final Pattern PROSE_LANG_PATTERN = Pattern.compile(
            "(?<![a-zA-Z0-9_-])language-(?:markdown|md|text|plaintext|txt)(?![a-zA-Z0-9_-])", Pattern.CASE_INSENSITIVE);
    private static final Pattern LANG_CLASS_PATTERN = Pattern.compile("(?<![a-zA-Z0-9_-])language-([a-zA-Z0-9_+-]+)",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> LANGUAGE_SYNTAX_MAP = Map.ofEntries(
            Map.entry("java", SyntaxConstants.SYNTAX_STYLE_JAVA),
            Map.entry("js", SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT),
            Map.entry("javascript", SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT),
            Map.entry("jexl", SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT),
            Map.entry("ts", SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT),
            Map.entry("typescript", SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT),
            Map.entry("py", SyntaxConstants.SYNTAX_STYLE_PYTHON),
            Map.entry("python", SyntaxConstants.SYNTAX_STYLE_PYTHON), Map.entry("go", SyntaxConstants.SYNTAX_STYLE_GO),
            Map.entry("golang", SyntaxConstants.SYNTAX_STYLE_GO), Map.entry("rs", SyntaxConstants.SYNTAX_STYLE_RUST),
            Map.entry("rust", SyntaxConstants.SYNTAX_STYLE_RUST), Map.entry("c", SyntaxConstants.SYNTAX_STYLE_C),
            Map.entry("cpp", SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS),
            Map.entry("c++", SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS),
            Map.entry("cs", SyntaxConstants.SYNTAX_STYLE_CSHARP),
            Map.entry("csharp", SyntaxConstants.SYNTAX_STYLE_CSHARP),
            Map.entry("kt", SyntaxConstants.SYNTAX_STYLE_KOTLIN),
            Map.entry("kotlin", SyntaxConstants.SYNTAX_STYLE_KOTLIN),
            Map.entry("groovy", SyntaxConstants.SYNTAX_STYLE_GROOVY),
            Map.entry("scala", SyntaxConstants.SYNTAX_STYLE_SCALA), Map.entry("rb", SyntaxConstants.SYNTAX_STYLE_RUBY),
            Map.entry("ruby", SyntaxConstants.SYNTAX_STYLE_RUBY), Map.entry("php", SyntaxConstants.SYNTAX_STYLE_PHP),
            Map.entry("sql", SyntaxConstants.SYNTAX_STYLE_SQL),
            Map.entry("sh", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL),
            Map.entry("bash", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL),
            Map.entry("zsh", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL),
            Map.entry("shell", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL),
            Map.entry("xml", SyntaxConstants.SYNTAX_STYLE_XML), Map.entry("html", SyntaxConstants.SYNTAX_STYLE_HTML),
            Map.entry("css", SyntaxConstants.SYNTAX_STYLE_CSS), Map.entry("json", SyntaxConstants.SYNTAX_STYLE_JSON),
            Map.entry("yaml", SyntaxConstants.SYNTAX_STYLE_YAML), Map.entry("yml", SyntaxConstants.SYNTAX_STYLE_YAML),
            Map.entry("properties", SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE),
            Map.entry("toml", SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE));

    public static String convert(String script) {
        return script == null ? "" : highlightHtml(StringEscapeUtils.escapeHtml4(script));
    }

    public static String highlightHtml(String escapedCode) {
        return highlightHtml(escapedCode, null);
    }

    static String highlightHtml(String escapedCode, String languageHint) {
        if (escapedCode == null || escapedCode.isEmpty()) {
            return "";
        }
        String rawCode = StringEscapeUtils.unescapeHtml4(escapedCode);
        TokenMaker tokenMaker = resolveTokenMaker(languageHint);
        StringBuilder sb = new StringBuilder(escapedCode.length() + 64);
        int initialTokenType = TokenTypes.NULL;

        int lineStart = 0;
        int len = rawCode.length();
        while (lineStart <= len) {
            int lineEnd = rawCode.indexOf(LF, lineStart);
            boolean hasNewline = lineEnd >= 0;
            if (!hasNewline) {
                lineEnd = len;
            }
            int contentEnd = lineEnd;
            boolean hasCarriageReturn = contentEnd > lineStart && rawCode.charAt(contentEnd - 1) == CR;
            if (hasCarriageReturn) {
                contentEnd--;
            }

            String lineText = rawCode.substring(lineStart, contentEnd);
            initialTokenType = highlightLine(lineText, tokenMaker, initialTokenType, sb);

            if (hasCarriageReturn) {
                sb.append(CR);
            }
            if (hasNewline) {
                sb.append(LF);
                lineStart = lineEnd + 1;
                if (lineStart == len) {
                    break;
                }
            } else {
                break;
            }
        }
        return sb.toString();
    }

    private static int highlightLine(String lineText, TokenMaker tokenMaker, int initialTokenType, StringBuilder sb) {
        if (lineText.isEmpty()) {
            char[] emptyChars = new char[0];
            Token token = tokenMaker.getTokenList(new Segment(emptyChars, 0, 0), initialTokenType, 0);
            return extractEndTokenType(token);
        }

        if (initialTokenType == TokenTypes.NULL) {
            int hashCommentIdx = findJexlHashCommentIndex(lineText);
            if (hashCommentIdx >= 0) {
                String beforeComment = lineText.substring(0, hashCommentIdx);
                String commentText = lineText.substring(hashCommentIdx);
                if (!beforeComment.isEmpty()) {
                    tokenizeSegment(beforeComment, tokenMaker, TokenTypes.NULL, sb);
                }
                sb.append("<span class='tk-cm'>").append(StringEscapeUtils.escapeHtml4(commentText)).append("</span>");
                return TokenTypes.NULL;
            }
        }

        return tokenizeSegment(lineText, tokenMaker, initialTokenType, sb);
    }

    private static int tokenizeSegment(String text, TokenMaker tokenMaker, int initialTokenType, StringBuilder sb) {
        char[] chars = text.toCharArray();
        Segment segment = new Segment(chars, 0, chars.length);
        Token token = tokenMaker.getTokenList(segment, initialTokenType, 0);
        Token curr = token;
        while (curr != null && curr.isPaintable()) {
            int tokenType = curr.getType();
            String lexeme = curr.getLexeme();
            if (lexeme != null && !lexeme.isEmpty()) {
                String escapedLexeme = StringEscapeUtils.escapeHtml4(lexeme);
                String cssClass = mapTokenToCssClass(tokenType, lexeme);
                if (cssClass != null) {
                    sb.append("<span class='").append(cssClass).append("'>").append(escapedLexeme).append("</span>");
                } else {
                    sb.append(escapedLexeme);
                }
            }
            curr = curr.getNextToken();
        }
        return extractEndTokenType(curr != null ? curr : token);
    }

    private static int extractEndTokenType(Token token) {
        Token last = token;
        while (last != null && last.getNextToken() != null) {
            last = last.getNextToken();
        }
        return last != null ? last.getType() : TokenTypes.NULL;
    }

    private static String mapTokenToCssClass(int tokenType, String lexeme) {
        return switch (tokenType) {
            case TokenTypes.RESERVED_WORD, TokenTypes.RESERVED_WORD_2, TokenTypes.DATA_TYPE -> "tk-rw";
            case TokenTypes.LITERAL_BOOLEAN -> "tk-rw";
            case TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, TokenTypes.LITERAL_CHAR, TokenTypes.LITERAL_BACKQUOTE ->
                "tk-st";
            case TokenTypes.LITERAL_NUMBER_DECIMAL_INT, TokenTypes.LITERAL_NUMBER_FLOAT,
                    TokenTypes.LITERAL_NUMBER_HEXADECIMAL ->
                "tk-nm";
            case TokenTypes.COMMENT_EOL, TokenTypes.COMMENT_MULTILINE, TokenTypes.COMMENT_DOCUMENTATION,
                    TokenTypes.COMMENT_KEYWORD, TokenTypes.COMMENT_MARKUP ->
                "tk-cm";
            case TokenTypes.IDENTIFIER, TokenTypes.FUNCTION, TokenTypes.VARIABLE ->
                (SERVICE_IDENTIFIER_PATTERN.matcher(lexeme).matches() || EXTRA_KEYWORDS.contains(lexeme))
                        ? "tk-rw"
                        : null;
            default -> SERVICE_IDENTIFIER_PATTERN.matcher(lexeme).matches() ? "tk-rw" : null;
        };
    }

    private static int findJexlHashCommentIndex(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;
        int i = 0;
        while (i < line.length() - 1) {
            char c = line.charAt(i);
            if (c == BACKSLASH) {
                i += 2;
                continue;
            }
            if (!inDouble && !inBacktick && c == SQUOTE) {
                inSingle = !inSingle;
            } else if (!inSingle && !inBacktick && c == DQUOTE) {
                inDouble = !inDouble;
            } else if (!inSingle && !inDouble && c == BACKTICK) {
                inBacktick = !inBacktick;
            } else if (!inSingle && !inDouble && !inBacktick) {
                if (c == '/' && line.charAt(i + 1) == '/') {
                    return -1;
                }
                if (c == '#' && line.charAt(i + 1) == '#') {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    private static TokenMaker resolveTokenMaker(String languageHint) {
        if (languageHint != null && !languageHint.isBlank()) {
            String style = LANGUAGE_SYNTAX_MAP.get(languageHint.trim().toLowerCase(Locale.ROOT));
            if (style != null && !SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT.equals(style)) {
                TokenMaker tm = TokenMakerFactory.getDefaultInstance().getTokenMaker(style);
                if (tm != null) {
                    return tm;
                }
            }
        }
        return new JavaScriptTokenMaker();
    }

    private static String extractLanguageHint(String codeAttrs) {
        if (codeAttrs == null || codeAttrs.isBlank()) {
            return null;
        }
        Matcher m = LANG_CLASS_PATTERN.matcher(codeAttrs);
        return m.find() ? m.group(1) : null;
    }

    public static String highlightCodeBlocks(String html) {
        if (html == null)
            return "";
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
                String highlighted = isProseLanguage(codeAttrs)
                        ? codeContent
                        : highlightHtml(codeContent, extractLanguageHint(codeAttrs));
                sb.append("<pre").append(preAttrs).append("><code").append(codeAttrs).append(">").append(highlighted)
                        .append("</code></pre>");
            } else {
                sb.append("<pre").append(preAttrs).append("><code>").append(highlightHtml(inner))
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
