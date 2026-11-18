package org.roxycode.app.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.apache.commons.jexl3.JexlArithmetic;
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
        String exceptionType = throwable.getClass().getName();
        Throwable rootCause = ExceptionUtils.getRootCause(throwable);
        String rootCauseType = null;
        if (rootCause != null) {
            rootCauseType = rootCause.getClass().getName();
        }
        List<String> causeChain = extractCauseChain(throwable);
        List<String> appStackFrames = extractAppStackFrames(throwable, rootCause);

        String category = "EXECUTION_ERROR";
        String hint = "Verify arguments, file paths, and null/success checks before accessing properties or calling service methods.";
        if (syntaxStage || jexlException instanceof JexlException.Parsing
                || jexlException instanceof JexlException.Tokenization) {
            category = "SYNTAX_ERROR";
            hint = "Check JEXL 3 syntax: declare variables with 'var', use { 'k': v } for maps, backticks for multi-line strings, and ';' between statements.";
        } else if (jexlException instanceof JexlException.Method methodEx) {
            category = "UNKNOWN_METHOD";
            String methodName = StringUtils.defaultIfBlank(methodEx.getMethod(), "unknown");
            hint = "Method '" + methodName
                    + "' is not available on this target or is not permitted by the sandbox. Check the JEXL API Documentation for valid @AgentDoc methods.";
        } else if (jexlException instanceof JexlException.Property propEx) {
            category = "UNKNOWN_PROPERTY";
            String propertyName = StringUtils.defaultIfBlank(propEx.getProperty(), "unknown");
            hint = "Property '" + propertyName
                    + "' could not be resolved. Check the exact field or record component name, or verify the object is not null.";
        } else if (jexlException instanceof JexlException.Variable varEx) {
            category = "UNKNOWN_VARIABLE";
            String varName = StringUtils.defaultIfBlank(varEx.getVariable(), "unknown");
            hint = "Variable or service '" + varName
                    + "' is undefined. Declare local variables with 'var' and check available service names in the JEXL API Documentation.";
        } else if (jexlException instanceof JexlException.Operator
                || ExceptionUtils.findCause(throwable, JexlArithmetic.NullOperand.class) != null) {
            category = "NULL_OPERAND";
            hint = "Operator failed (possibly due to a null operand or incompatible types). Check for null values before operating on variables.";
        }

        return new ErrorAnalysis(category, line, column, offendingLine, errorMessage, hint, syntaxStage, exceptionType,
                rootCauseType, causeChain, appStackFrames);
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
            return "Syntax Error: " + ExceptionUtils.getMessageOrSimpleName(throwable);
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
}