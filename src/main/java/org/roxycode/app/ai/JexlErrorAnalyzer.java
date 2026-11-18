package org.roxycode.app.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.jexl3.JexlException;
import org.apache.commons.jexl3.JexlInfo;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.roxycode.app.util.ExceptionUtils;

/**
 * Classifies JEXL syntax and runtime exceptions into structured error categories with line/column diagnostics and
 * actionable JEXL 3 hints.
 */
public final class JexlErrorAnalyzer {

    private static final String APP_PACKAGE_PREFIX = "org.roxycode.app.";
    private static final int MAX_CAUSE_CHAIN_DEPTH = 8;
    private static final int MAX_CAUSE_MESSAGE_LENGTH = 500;
    private static final int MAX_APP_STACK_FRAMES = 10;
    private static final char BACKTICK = '`';

    private static final Pattern JS_CONST_LET_PATTERN = Pattern.compile("\\b(const|let)\\s+[A-Za-z_$]");
    private static final Pattern BRACKET_MAP_LITERAL_PATTERN = Pattern
            .compile("\\[\\s*(['\"][^'\"]+['\"]|[A-Za-z_]\\w*)\\s*:");
    private static final Pattern JS_FOR_OF_OR_IN_PATTERN = Pattern.compile("\\bfor\\s*\\([^)]*\\b(of|in)\\b");
    private static final Pattern STRICT_EQUALITY_PATTERN = Pattern.compile("(===|!==)");
    private static final Pattern OBJECT_SHORTHAND_PATTERN = Pattern
            .compile("\\{\\s*[A-Za-z_]\\w*\\s*(,|\\})|,\\s*[A-Za-z_]\\w*\\s*\\}");
    private static final Pattern ARRAY_TYPE_PATTERN = Pattern.compile("(\\[\\]|Object\\[|\\bArray\\b)");
    private static final Pattern BROKEN_BACKTICK_CONCAT_PATTERN = Pattern
            .compile("`\\s*\\+\\s*['\"]`|`['\"]\\s*\\+\\s*`");
    private static final String UNESCAPED_BACKTICK_HINT = "Literal backticks inside JEXL 3 backtick strings (`...`) must be escaped as \\` (and literal ${...} as \\${...}). Do not break out of backticks with + \"`...`\" +.";

    /**
     * Structured classification of common JEXL syntax and runtime errors.
     */
    public enum ErrorCategory {
        MULTILINE_QUOTED_STRING, UNESCAPED_BACKTICK, JS_CONST_LET, BRACKET_MAP_LITERAL, JS_FOR_OF_OR_IN,
        STRICT_EQUALITY, OBJECT_SHORTHAND, ARRAY_ADD_MUTATION, UNSOLVABLE_METHOD, UNSOLVABLE_PROPERTY, SYNTAX_ERROR,
        RUNTIME_ERROR
    }

    /**
     * Structured diagnostic result from analyzing a JEXL exception.
     *
     * @param errorCategory
     *            the classified error category name
     * @param line
     *            1-based line number from JexlInfo, or null if unavailable
     * @param column
     *            1-based column number from JexlInfo, or null if unavailable
     * @param offendingLine
     *            trimmed source line at {@code line}, or null if unavailable
     * @param error
     *            human-readable error message
     * @param hint
     *            actionable JEXL 3 syntax or API hint
     * @param syntaxStage
     *            true if thrown during {@code createScript}, false if thrown during execution
     * @param exceptionType
     *            fully qualified class name of the caught throwable
     * @param rootCauseType
     *            fully qualified class name of the root cause throwable
     * @param causeChain
     *            bounded list of exception cause summaries from outermost to root
     * @param appStackFrames
     *            top application stack frames under {@code org.roxycode.app.}
     */
    public record ErrorAnalysis(String errorCategory, @Nullable Integer line, @Nullable Integer column,
            @Nullable String offendingLine, String error, String hint, boolean syntaxStage,
            @Nullable String exceptionType, @Nullable String rootCauseType, List<String> causeChain,
            List<String> appStackFrames) {

        public ErrorAnalysis {
            if (causeChain == null) {
                causeChain = List.of();
            } else {
                causeChain = List.copyOf(causeChain);
            }
            if (appStackFrames == null) {
                appStackFrames = List.of();
            } else {
                appStackFrames = List.copyOf(appStackFrames);
            }
        }

    }

    private JexlErrorAnalyzer() {
    }

    /**
     * Analyzes a thrown JEXL or service exception alongside the executed script.
     *
     * @param throwable
     *            the caught exception
     * @param script
     *            the JEXL script text
     * @param syntaxStage
     *            true if thrown during {@code createScript}, false if thrown during execution
     * @return structured error analysis with category, location, and hint
     */
    public static ErrorAnalysis analyze(Throwable throwable, @Nullable String script, boolean syntaxStage) {
        JexlException jexlException = ExceptionUtils.findCause(throwable, JexlException.class);
        Integer line = null;
        Integer column = null;
        if (jexlException != null) {
            JexlInfo info = jexlException.getInfo();
            if (info != null) {
                if (info.getLine() > 0) {
                    line = info.getLine();
                }
                if (info.getColumn() > 0) {
                    column = info.getColumn();
                }
            }
        }
        String offendingLine = extractOffendingLine(script, line);
        String errorMessage = resolveErrorMessage(throwable, jexlException, syntaxStage);
        String inspectionTarget = resolveInspectionTarget(offendingLine, script);

        String exceptionType = throwable.getClass().getName();
        Throwable rootCause = ExceptionUtils.getRootCause(throwable);
        String rootCauseType = null;
        if (rootCause != null) {
            rootCauseType = rootCause.getClass().getName();
        }
        List<String> causeChain = extractCauseChain(throwable);
        List<String> appStackFrames = extractAppStackFrames(throwable, rootCause);

        if (jexlException instanceof JexlException.Tokenization) {
            if (isUnescapedBacktickInTemplate(inspectionTarget, script, line)) {
                return new ErrorAnalysis(ErrorCategory.UNESCAPED_BACKTICK.name(), line, column, offendingLine,
                        errorMessage, UNESCAPED_BACKTICK_HINT, syntaxStage, exceptionType, rootCauseType, causeChain,
                        appStackFrames);
            }
            if (isMultilineQuotedString(inspectionTarget, errorMessage)) {
                return new ErrorAnalysis(ErrorCategory.MULTILINE_QUOTED_STRING.name(), line, column, offendingLine,
                        errorMessage,
                        "Multi-line strings in JEXL 3 MUST use backticks (`...`), not single (') or double (\") quotes.",
                        syntaxStage, exceptionType, rootCauseType, causeChain, appStackFrames);
            }
        }

        if (syntaxStage || jexlException instanceof JexlException.Parsing
                || jexlException instanceof JexlException.Tokenization) {
            if (isUnescapedBacktickInTemplate(inspectionTarget, script, line)) {
                return new ErrorAnalysis(ErrorCategory.UNESCAPED_BACKTICK.name(), line, column, offendingLine,
                        errorMessage, UNESCAPED_BACKTICK_HINT, syntaxStage, exceptionType, rootCauseType, causeChain,
                        appStackFrames);
            }
            ErrorCategory syntaxCategory = classifySyntaxPattern(inspectionTarget);
            if (syntaxCategory != null) {
                return new ErrorAnalysis(syntaxCategory.name(), line, column, offendingLine, errorMessage,
                        hintForSyntaxCategory(syntaxCategory), syntaxStage, exceptionType, rootCauseType, causeChain,
                        appStackFrames);
            }
            return new ErrorAnalysis(ErrorCategory.SYNTAX_ERROR.name(), line, column, offendingLine, errorMessage,
                    "Check JEXL 3 syntax: declare variables with 'var', use { 'k': v } for maps, backticks for multi-line strings, and ';' between statements.",
                    syntaxStage, exceptionType, rootCauseType, causeChain, appStackFrames);
        }

        if (jexlException instanceof JexlException.Method methodEx) {
            String methodName = StringUtils.defaultIfBlank(methodEx.getMethod(), "unknown");
            if (isArrayAddMutation(methodName, errorMessage, script)) {
                return new ErrorAnalysis(ErrorCategory.ARRAY_ADD_MUTATION.name(), line, column, offendingLine,
                        errorMessage,
                        "Standard '[...]' creates a fixed-size Java array that does not support .add(). Use a mutable list literal '[..., ...]' or pass an array directly to batch service methods.",
                        syntaxStage, exceptionType, rootCauseType, causeChain, appStackFrames);
            }
            return new ErrorAnalysis(ErrorCategory.UNSOLVABLE_METHOD.name(), line, column, offendingLine, errorMessage,
                    "Method '" + methodName
                            + "' is not available on this target or is not permitted by the sandbox. Check the JEXL API Documentation for valid @AgentDoc methods and parameter types.",
                    syntaxStage, exceptionType, rootCauseType, causeChain, appStackFrames);
        }

        if (jexlException instanceof JexlException.Property propEx) {
            String propertyName = StringUtils.defaultIfBlank(propEx.getProperty(), "unknown");
            return new ErrorAnalysis(ErrorCategory.UNSOLVABLE_PROPERTY.name(), line, column, offendingLine,
                    errorMessage,
                    "Property '" + propertyName
                            + "' could not be resolved. Check the exact field/getter name in the JEXL API Documentation or verify the object is not null.",
                    syntaxStage, exceptionType, rootCauseType, causeChain, appStackFrames);
        }

        if (jexlException instanceof JexlException.Variable varEx) {
            String varName = StringUtils.defaultIfBlank(varEx.getVariable(), "unknown");
            return new ErrorAnalysis(ErrorCategory.UNSOLVABLE_PROPERTY.name(), line, column, offendingLine,
                    errorMessage,
                    "Variable or service '" + varName
                            + "' is undefined. Declare local variables with 'var' and check available service names in the JEXL API Documentation.",
                    syntaxStage, exceptionType, rootCauseType, causeChain, appStackFrames);
        }

        return new ErrorAnalysis(ErrorCategory.RUNTIME_ERROR.name(), line, column, offendingLine, errorMessage,
                "Verify arguments, file paths, and null/success checks before accessing properties or calling service methods.",
                syntaxStage, exceptionType, rootCauseType, causeChain, appStackFrames);
    }

    private static List<String> extractCauseChain(Throwable throwable) {
        List<String> chain = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        while (current != null && visited.add(current) && chain.size() < MAX_CAUSE_CHAIN_DEPTH) {
            String msg = StringUtils.defaultIfBlank(current.getMessage(), "<no message>");
            if (msg.length() > MAX_CAUSE_MESSAGE_LENGTH) {
                msg = msg.substring(0, MAX_CAUSE_MESSAGE_LENGTH) + "...";
            }
            chain.add(current.getClass().getSimpleName() + ": " + msg);
            current = current.getCause();
        }
        return chain;
    }

    private static List<String> extractAppStackFrames(Throwable throwable, @Nullable Throwable rootCause) {
        List<String> frames = new ArrayList<>();
        if (rootCause != null) {
            collectAppStackFrames(rootCause, frames);
        }
        if (frames.isEmpty() && rootCause != null && !rootCause.equals(throwable)) {
            collectAppStackFrames(throwable, frames);
        }
        return frames;
    }

    private static void collectAppStackFrames(Throwable source, List<String> frames) {
        for (StackTraceElement element : source.getStackTrace()) {
            if (frames.size() >= MAX_APP_STACK_FRAMES) {
                break;
            }
            String className = element.getClassName();
            if (!className.startsWith(APP_PACKAGE_PREFIX)) {
                continue;
            }
            String simpleName = className.substring(className.lastIndexOf('.') + 1);
            String fileName = StringUtils.defaultIfBlank(element.getFileName(), "Unknown");
            frames.add(
                    simpleName + "." + element.getMethodName() + "(" + fileName + ":" + element.getLineNumber() + ")");
        }
    }

    private static @Nullable String extractOffendingLine(@Nullable String script, @Nullable Integer line) {
        if (StringUtils.isBlank(script) || line == null || line <= 0) {
            return null;
        }
        String[] lines = script.split("\r?\n", -1);
        if (line > lines.length) {
            return null;
        }
        String trimmed = lines[line - 1].trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed;
    }

    private static String resolveErrorMessage(Throwable throwable, @Nullable JexlException jexlException,
            boolean syntaxStage) {
        if (syntaxStage) {
            String detail = ExceptionUtils.getMessageOrSimpleName(throwable);
            return "Syntax Error: " + detail;
        }
        Throwable rootCause = ExceptionUtils.getRootCause(throwable);
        if (rootCause != null && !(rootCause instanceof JexlException)) {
            return ExceptionUtils.getMessageOrSimpleName(rootCause);
        }
        if (jexlException != null) {
            return ExceptionUtils.getMessageOrSimpleName(jexlException);
        }
        return ExceptionUtils.getMessageOrSimpleName(throwable);
    }

    private static String resolveInspectionTarget(@Nullable String offendingLine, @Nullable String script) {
        if (StringUtils.isNotBlank(offendingLine)) {
            return offendingLine;
        }
        return StringUtils.defaultString(script);
    }

    static boolean isUnescapedBacktickInTemplate(String inspectionTarget, @Nullable String script,
            @Nullable Integer line) {
        if (StringUtils.isNotBlank(inspectionTarget)
                && BROKEN_BACKTICK_CONCAT_PATTERN.matcher(inspectionTarget).find()) {
            return true;
        }
        if (StringUtils.isBlank(script)) {
            return StringUtils.isNotBlank(inspectionTarget) && countUnescapedBackticks(inspectionTarget) % 2 != 0;
        }
        if (line != null && line > 1) {
            String[] lines = script.split("\r?\n", -1);
            int precedingLineCount = Math.min(line - 1, lines.length);
            int precedingBackticks = 0;
            for (int i = 0; i < precedingLineCount; i++) {
                precedingBackticks += countUnescapedBackticks(lines[i]);
            }
            if (precedingBackticks % 2 != 0) {
                return true;
            }
        }
        return countUnescapedBackticks(script) % 2 != 0;
    }

    private static int countUnescapedBackticks(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == BACKTICK) {
                int backslashes = 0;
                int j = i - 1;
                while (j >= 0 && text.charAt(j) == '\\') {
                    backslashes++;
                    j--;
                }
                if (backslashes % 2 == 0) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean isMultilineQuotedString(String target, String errorMessage) {
        if (countUnescapedBackticks(target) % 2 != 0) {
            return false;
        }
        if (target.indexOf('"') >= 0 || target.indexOf('\'') >= 0) {
            return true;
        }
        String lower = errorMessage.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("tokenization") && (lower.contains("\\n") || lower.contains("unterminated"));
    }

    private static @Nullable ErrorCategory classifySyntaxPattern(String target) {
        if (StringUtils.isBlank(target)) {
            return null;
        }
        if (JS_CONST_LET_PATTERN.matcher(target).find()) {
            return ErrorCategory.JS_CONST_LET;
        }
        if (BRACKET_MAP_LITERAL_PATTERN.matcher(target).find()) {
            return ErrorCategory.BRACKET_MAP_LITERAL;
        }
        if (JS_FOR_OF_OR_IN_PATTERN.matcher(target).find()) {
            return ErrorCategory.JS_FOR_OF_OR_IN;
        }
        if (STRICT_EQUALITY_PATTERN.matcher(target).find()) {
            return ErrorCategory.STRICT_EQUALITY;
        }
        Matcher shorthandMatcher = OBJECT_SHORTHAND_PATTERN.matcher(target);
        if (shorthandMatcher.find()) {
            return ErrorCategory.OBJECT_SHORTHAND;
        }
        return null;
    }

    private static String hintForSyntaxCategory(ErrorCategory category) {
        return switch (category) {
            case UNESCAPED_BACKTICK -> UNESCAPED_BACKTICK_HINT;
            case JS_CONST_LET ->
                "JEXL 3 does not support 'const' or 'let'. Use 'var' to declare variables (e.g., var x = 1;).";
            case BRACKET_MAP_LITERAL ->
                "Map literals in JEXL 3 MUST use curly braces { 'key': value }, not square brackets [ ... ].";
            case JS_FOR_OF_OR_IN ->
                "JEXL 3 uses colon ':' in for-each loops, not 'of' or 'in' (e.g., for (var item : list) { ... }).";
            case STRICT_EQUALITY -> "JEXL 3 does not support '===' or '!=='. Use '==' or '!=' instead.";
            case OBJECT_SHORTHAND ->
                "JEXL 3 map literals do not support ES6 shorthand properties ({ a, b }). Use explicit key-value pairs: { 'a': a, 'b': b }.";
            default -> "Check JEXL 3 syntax.";
        };
    }

    private static boolean isArrayAddMutation(String methodName, String errorMessage, @Nullable String script) {
        if (!"add".equals(methodName) && !"push".equals(methodName)) {
            return false;
        }
        if (ARRAY_TYPE_PATTERN.matcher(errorMessage).find()) {
            return true;
        }
        if (script != null && script.contains("[") && !script.contains("...")) {
            return true;
        }
        return false;
    }
}
