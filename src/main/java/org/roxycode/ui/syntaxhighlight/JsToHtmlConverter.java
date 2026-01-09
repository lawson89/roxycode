package org.roxycode.ui.syntaxhighlight;

import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.modes.JavaScriptTokenMaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.text.Segment;

public class JsToHtmlConverter {

    private static final Logger LOG = LoggerFactory.getLogger(JsToHtmlConverter.class);

    public static String convertToHtml(String jsCode) {
        return convertToHtml(jsCode, false);
    }

    public static String convertToHtml(String jsCode, boolean isDark) {
        try {
            StringBuilder html = new StringBuilder();
            
            String bgColor = isDark ? "#2b2d30" : "#f8f8f8";
            String textColor = isDark ? "#a9b7c6" : "#333333";
            String borderColor = isDark ? "#444444" : "#ddd";
            
            html.append("<pre style='background-color: ")
                .append(bgColor)
                .append("; color: ")
                .append(textColor)
                .append("; padding: 2px; font-family: monospace; border: 0px solid ")
                .append(borderColor)
                .append("; border-radius: 4px;'>");

            // 1. Create the tokenizer specifically for JavaScript
            TokenMaker tokenMaker = new JavaScriptTokenMaker();
            Segment text = new Segment(jsCode.toCharArray(), 0, jsCode.length());

            // 2. Get the list of tokens from the code
            Token token = tokenMaker.getTokenList(text, Token.NULL, 0);

            // 3. Iterate through the linked list of tokens
            while (token != null && token.getType() != Token.NULL) {
                String tokenText = token.getLexeme();
                String escapedText = escapeHtml(tokenText);
                String colorStyle = getColorForTokenType(token.getType(), isDark);

                html.append("<span style='").append(colorStyle).append("'>")
                        .append(escapedText)
                        .append("</span>");

                token = token.getNextToken();
            }

            html.append("</pre>");
            return html.toString();
        } catch (Exception e) {
            LOG.warn("Error while converting js code to html string", e);
            return jsCode;
        }
    }

    // Map token types to standard syntax highlighting colors
    private static String getColorForTokenType(int type, boolean isDark) {
        if (isDark) {
            return switch (type) {
                case Token.RESERVED_WORD -> "color: #cc7832; font-weight: bold;"; // Orange/Brown
                case Token.LITERAL_STRING_DOUBLE_QUOTE, Token.LITERAL_CHAR -> "color: #6a8759;"; // Green
                case Token.COMMENT_EOL, Token.COMMENT_MULTILINE, Token.COMMENT_DOCUMENTATION -> "color: #808080; font-style: italic;"; // Grey
                case Token.FUNCTION -> "color: #ffc66d; font-weight: bold;"; // Yellow
                case Token.LITERAL_NUMBER_DECIMAL_INT, Token.LITERAL_NUMBER_FLOAT -> "color: #6897bb;"; // Blue
                case Token.OPERATOR -> "color: #a9b7c6;"; // Standard Text
                case Token.DATA_TYPE -> "color: #cc7832; font-weight: bold;"; // Orange/Brown
                default -> "color: inherit;";
            };
        } else {
            return switch (type) {
                case Token.RESERVED_WORD -> "color: #000080; font-weight: bold;"; // Dark Blue
                case Token.LITERAL_STRING_DOUBLE_QUOTE, Token.LITERAL_CHAR -> "color: #008000;"; // Green
                case Token.COMMENT_EOL, Token.COMMENT_MULTILINE, Token.COMMENT_DOCUMENTATION -> "color: #808080; font-style: italic;"; // Grey
                case Token.FUNCTION -> "color: #000000; font-weight: bold;"; // Black Bold
                case Token.LITERAL_NUMBER_DECIMAL_INT, Token.LITERAL_NUMBER_FLOAT -> "color: #0000FF;"; // Blue
                case Token.OPERATOR -> "color: #333333;"; // Default Dark
                case Token.DATA_TYPE -> "color: #000080; font-weight: bold;"; // Dark Blue
                default -> "color: inherit;";
            };
        }
    }

    // Basic HTML escaping
    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }
}
