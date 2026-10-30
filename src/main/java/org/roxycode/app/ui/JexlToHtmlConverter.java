package org.roxycode.app.ui;

import java.util.Set;
import java.util.regex.Pattern;
import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.fife.ui.rsyntaxtextarea.modes.JavaScriptTokenMaker;

public class JexlToHtmlConverter {

    private static final Set<String> EXTRA_KEYWORDS = Set.of("and", "or", "not", "mod", "div", "empty", "size",
            "assert", "interface", "record", "String", "List", "Map", "Set", "Optional", "int", "long", "double",
            "float", "short", "byte", "char", "boolean");

    private static final Pattern SERVICE_IDENTIFIER_PATTERN = Pattern
            .compile("[a-zA-Z_][a-zA-Z0-9_]*[sS]ervice[a-zA-Z0-9_]*");

    private static final Pattern PROSE_LANG_PATTERN = Pattern.compile(
            "(?<![a-zA-Z0-9_-])language-(?:markdown|md|text|plaintext|txt)(?![a-zA-Z0-9_-])", Pattern.CASE_INSENSITIVE);

    public static String convert(String script) {
        if (script == null) {
            return "";
        }
        return highlightHtml(script);
    }

    public static String highlight(String code, String language) {
        if (code == null) {
            return "";
        }
        String trimmed = code.stripTrailing();
        String highlighted;
        if (isProseLanguage(language)) {
            highlighted = SwingHtmlUtils.escapeHtml(trimmed);
        } else {
            highlighted = highlightHtml(trimmed);
        }
        return "<pre class='jexl-highlight'><code>" + highlighted + "</code></pre>";
    }

    private static boolean isProseLanguage(String language) {
        if (language == null || language.isEmpty()) {
            return false;
        }
        return PROSE_LANG_PATTERN.matcher("language-" + language).find();
    }

    public static String highlightHtml(String code) {
        if (code == null || code.isEmpty()) {
            return "";
        }
        TokenMaker tokenMaker = new JavaScriptTokenMaker();
        StringBuilder sb = new StringBuilder(code.length() * 2);
        int initialTokenType = TokenTypes.NULL;

        String[] lines = code.splitWithDelimiters("\r?\n", -1);
        for (int i = 0; i < lines.length; i += 2) {
            String line = lines[i];
            if (line.isEmpty()) {
                Token t = tokenMaker.getTokenList(new Segment(new char[0], 0, 0), initialTokenType, 0);
                initialTokenType = getNextInitialTokenType(t);
            } else if (initialTokenType == TokenTypes.NULL && line.trim().startsWith("#")) {
                sb.append("<span class='mtk-comment'>").append(SwingHtmlUtils.escapeHtml(line)).append("</span>");
            } else {
                initialTokenType = tokenizeLine(line, tokenMaker, initialTokenType, sb);
            }

            if (i + 1 < lines.length) {
                sb.append(lines[i + 1]);
            }
        }
        return sb.toString();
    }

    private static int tokenizeLine(String line, TokenMaker tokenMaker, int initialTokenType, StringBuilder sb) {
        char[] chars = line.toCharArray();
        Segment segment = new Segment(chars, 0, chars.length);
        Token token = tokenMaker.getTokenList(segment, initialTokenType, 0);

        Token curr = token;
        while (curr != null && curr.isPaintable()) {
            String lexeme = curr.getLexeme();
            String cssClass = mapTokenToCssClass(curr.getType(), lexeme);
            if (cssClass != null) {
                sb.append("<span class='").append(cssClass).append("'>").append(SwingHtmlUtils.escapeHtml(lexeme))
                        .append("</span>");
            } else {
                sb.append(SwingHtmlUtils.escapeHtml(lexeme));
            }
            curr = curr.getNextToken();
        }
        return getNextInitialTokenType(token);
    }

    private static int getNextInitialTokenType(Token token) {
        Token last = token;
        while (last != null && last.getNextToken() != null) {
            last = last.getNextToken();
        }
        if (last != null) {
            return last.getType();
        }
        return TokenTypes.NULL;
    }

    private static String mapTokenToCssClass(int type, String lexeme) {
        return switch (type) {
            case TokenTypes.RESERVED_WORD, TokenTypes.RESERVED_WORD_2, TokenTypes.DATA_TYPE,
                    TokenTypes.LITERAL_BOOLEAN ->
                "mtk-keyword";
            case TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, TokenTypes.LITERAL_CHAR, TokenTypes.LITERAL_BACKQUOTE ->
                "mtk-string";
            case TokenTypes.LITERAL_NUMBER_DECIMAL_INT, TokenTypes.LITERAL_NUMBER_FLOAT,
                    TokenTypes.LITERAL_NUMBER_HEXADECIMAL ->
                "mtk-number";
            case TokenTypes.COMMENT_EOL, TokenTypes.COMMENT_MULTILINE, TokenTypes.COMMENT_DOCUMENTATION,
                    TokenTypes.COMMENT_KEYWORD, TokenTypes.COMMENT_MARKUP ->
                "mtk-comment";
            case TokenTypes.IDENTIFIER, TokenTypes.FUNCTION, TokenTypes.VARIABLE -> {
                if (EXTRA_KEYWORDS.contains(lexeme) || SERVICE_IDENTIFIER_PATTERN.matcher(lexeme).matches()) {
                    yield "mtk-keyword";
                }
                yield null;
            }
            default -> null;
        };
    }
}