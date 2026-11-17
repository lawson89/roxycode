package org.roxycode.app.ai.services;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.DescribeStatement;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.ShowColumnsStatement;
import net.sf.jsqlparser.statement.ShowStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.show.ShowTablesStatement;

/**
 * AST-based read-only SQL validator using JSqlParser to enforce single-statement read-only queries.
 */
public final class ReadOnlySqlGuard {

    private static final Set<String> DANGEROUS_FUNCTIONS = Set.of("pg_terminate_backend", "pg_cancel_backend",
            "pg_reload_conf", "pg_rotate_logfile", "lo_import", "lo_export", "lo_unlink", "xp_cmdshell",
            "dbms_lock.sleep", "utl_http", "utl_file");

    private static final String IDENT_CHARS = "-A-Za-z0-9_.'`\\[\\]" + '"';

    private static final Pattern SAFE_SQLITE_PRAGMA = Pattern.compile("^PRAGMA[ \t]+(?:[" + IDENT_CHARS + "]+[.])?"
            + "(?:table_info|table_xinfo|index_list|index_info|foreign_key_list)[ \t]*[(][ \t]*[" + IDENT_CHARS
            + "]+[ \t]*[)]$" + "|^PRAGMA[ \t]+database_list$", Pattern.CASE_INSENSITIVE);

    private static final Pattern SAFE_SHOW_FALLBACK = Pattern
            .compile("^SHOW[ \t]+(?:FULL[ \t]+)?(?:TABLES|DATABASES|SCHEMAS|COLUMNS[ \t]+FROM[ \t]+[" + IDENT_CHARS
                    + "]+" + "|INDEX[ \t]+FROM[ \t]+[" + IDENT_CHARS + "]+" + "|CREATE[ \t]+TABLE[ \t]+[" + IDENT_CHARS
                    + "]+" + "|SEARCH_PATH|SERVER_VERSION)$", Pattern.CASE_INSENSITIVE);
    private ReadOnlySqlGuard() {
    }

    /**
     * Validates that {@code rawSql} is a single read-only SQL statement and returns the normalized SQL without trailing
     * semicolons.
     *
     * @throws IllegalArgumentException
     *             if the SQL is blank, contains multiple statements, or is not strictly read-only
     */
    public static String validateReadOnlySql(String rawSql) {
        String normalized = prepareAndCheckSingleStatement(rawSql);
        if (SAFE_SQLITE_PRAGMA.matcher(normalized).matches() || SAFE_SHOW_FALLBACK.matcher(normalized).matches()) {
            return normalized;
        }
        Statement stmt = parseSingleStatement(normalized, "SQL could not be parsed as a valid read-only statement: ",
                "Only a single read-only SQL statement is allowed.");
        validateStatement(stmt);
        return normalized;
    }

    /**
     * Validates that {@code rawSql} is strictly a single read-only {@code SELECT} statement (suitable for
     * {@code EXPLAIN}).
     *
     * @throws IllegalArgumentException
     *             if the SQL is not a single read-only {@code SELECT}
     */
    public static String validateSelectOnly(String rawSql) {
        String normalized = prepareAndCheckSingleStatement(rawSql);
        Statement stmt = parseSingleStatement(normalized, "SQL could not be parsed as a valid SELECT statement: ",
                "EXPLAIN requires a single SELECT statement.");
        if (!(stmt instanceof Select select)) {
            throw new IllegalArgumentException("EXPLAIN only supports read-only SELECT statements.");
        }
        validateSelect(select);
        return normalized;
    }

    private static Statement parseSingleStatement(String normalizedSql, String parseErrorPrefix,
            String multiStatementMessage) {
        List<Statement> statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(normalizedSql).getStatements();
        } catch (JSQLParserException e) {
            throw new IllegalArgumentException(parseErrorPrefix + e.getMessage(), e);
        }
        if (statements == null || statements.size() != 1) {
            throw new IllegalArgumentException(multiStatementMessage);
        }
        return statements.get(0);
    }

    private static String prepareAndCheckSingleStatement(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            throw new IllegalArgumentException("SQL query must not be blank.");
        }
        String trimmed = stripTrailingSemicolonsAndWhitespace(rawSql.trim());
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("SQL query must not be blank.");
        }
        if (containsUnquotedSemicolon(trimmed)) {
            throw new IllegalArgumentException("Multiple SQL statements are not permitted.");
        }
        return trimmed;
    }

    private static void validateStatement(Statement statement) {
        if (statement instanceof Select select) {
            validateSelect(select);
            return;
        }
        if (statement instanceof ExplainStatement explain) {
            Statement inner = explain.getStatement();
            if (!(inner instanceof Select select)) {
                throw new IllegalArgumentException("EXPLAIN is only allowed for read-only SELECT statements.");
            }
            validateSelect(select);
            return;
        }
        if (statement instanceof ShowStatement || statement instanceof ShowTablesStatement
                || statement instanceof ShowColumnsStatement || statement instanceof DescribeStatement) {
            return;
        }
        throw new IllegalArgumentException(
                "Only read-only SELECT, EXPLAIN, and safe SHOW/DESCRIBE statements are permitted (rejected: "
                        + statement.getClass().getSimpleName() + ").");
    }

    private static void validateSelect(Select select) {
        List<WithItem<?>> withItems = select.getWithItemsList();
        if (withItems != null) {
            for (WithItem<?> withItem : withItems) {
                if (withItem != null) {
                    Select cteSelect;
                    try {
                        cteSelect = withItem.getSelect();
                    } catch (ClassCastException e) {
                        throw new IllegalArgumentException("Mutating CTEs are not permitted in read-only mode.", e);
                    }
                    if (cteSelect != null) {
                        validateSelect(cteSelect);
                    }
                }
            }
        }
        if (select instanceof PlainSelect plainSelect) {
            validatePlainSelect(plainSelect);
            return;
        }
        if (select instanceof SetOperationList setOpList) {
            if (setOpList.getSelects() != null) {
                for (Select sub : setOpList.getSelects()) {
                    validateSelect(sub);
                }
            }
            return;
        }
        if (select instanceof ParenthesedSelect parenthesed) {
            if (parenthesed.getSelect() != null) {
                validateSelect(parenthesed.getSelect());
            }
            return;
        }
        throw new IllegalArgumentException("Unsupported SELECT form: " + select.getClass().getSimpleName());
    }

    private static void validatePlainSelect(PlainSelect plainSelect) {
        if (plainSelect.getIntoTables() != null && !plainSelect.getIntoTables().isEmpty()) {
            throw new IllegalArgumentException("SELECT ... INTO is not permitted in read-only mode.");
        }
        if (plainSelect.getForMode() != null || plainSelect.getWait() != null) {
            throw new IllegalArgumentException("Row-locking clauses (FOR UPDATE / FOR SHARE) are not permitted.");
        }
        if (plainSelect.getSelectItems() != null) {
            for (SelectItem<?> item : plainSelect.getSelectItems()) {
                if (item != null && item.getExpression() != null) {
                    validateExpression(item.getExpression());
                }
            }
        }
        validateFromItem(plainSelect.getFromItem());
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                if (join != null) {
                    validateFromItem(join.getFromItem());
                    if (join.getOnExpressions() != null) {
                        for (Expression onExpr : join.getOnExpressions()) {
                            validateExpression(onExpr);
                        }
                    }
                }
            }
        }
        validateExpression(plainSelect.getWhere());
        validateExpression(plainSelect.getHaving());
        if (plainSelect.getQualify() != null) {
            validateExpression(plainSelect.getQualify());
        }
    }

    private static void validateFromItem(FromItem fromItem) {
        if (fromItem == null) {
            return;
        }
        if (fromItem instanceof ParenthesedSelect subSelect) {
            validateSelect(subSelect);
            return;
        }
        if (fromItem instanceof Select subSelect) {
            validateSelect(subSelect);
            return;
        }
        if (fromItem instanceof ParenthesedFromItem parenthesed) {
            validateFromItem(parenthesed.getFromItem());
            if (parenthesed.getJoins() != null) {
                for (Join join : parenthesed.getJoins()) {
                    if (join != null) {
                        validateFromItem(join.getFromItem());
                    }
                }
            }
        }
    }

    private static void validateExpression(Expression expression) {
        if (expression == null) {
            return;
        }
        ExpressionValidator validator = new ExpressionValidator();
        expression.accept(validator);
    }

    private static final class ExpressionValidator extends ExpressionVisitorAdapter<Void> {
        @Override
        public <S> Void visit(Function function, S context) {
            String name = function.getName();
            if (name != null) {
                String lower = name.trim().toLowerCase(Locale.ROOT);
                if (DANGEROUS_FUNCTIONS.contains(lower)) {
                    throw new IllegalArgumentException("Function '" + name + "' is not permitted in read-only mode.");
                }
            }
            return super.visit(function, context);
        }

        @Override
        public <S> Void visit(ParenthesedSelect selectBody, S context) {
            validateSelect(selectBody);
            return null;
        }
    }

    private static String stripTrailingSemicolonsAndWhitespace(String sql) {
        int end = sql.length();
        while (end > 0) {
            char ch = sql.charAt(end - 1);
            if (Character.isWhitespace(ch) || ch == ';') {
                end--;
            } else {
                break;
            }
        }
        return sql.substring(0, end);
    }

    private static boolean containsUnquotedSemicolon(String sql) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inBacktick = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        int len = sql.length();
        char quoteChar = 39;
        char doubleQuoteChar = '"';
        char backtickChar = '`';
        char semicolonChar = ';';
        char backslashChar = 92;
        char nulChar = 0;
        int i = 0;
        while (i < len) {
            char c = sql.charAt(i);
            char next = nulChar;
            if (i + 1 < len) {
                next = sql.charAt(i + 1);
            }
            if (inLineComment) {
                if (c == '\n' || c == '\r') {
                    inLineComment = false;
                }
                i++;
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                i++;
                continue;
            }
            if (inSingleQuote) {
                if (c == quoteChar && next == quoteChar) {
                    i++;
                } else if (c == backslashChar && next != nulChar) {
                    i++;
                } else if (c == quoteChar) {
                    inSingleQuote = false;
                }
                i++;
                continue;
            }
            if (inDoubleQuote) {
                if (c == doubleQuoteChar && next == doubleQuoteChar) {
                    i++;
                } else if (c == doubleQuoteChar) {
                    inDoubleQuote = false;
                }
                i++;
                continue;
            }
            if (inBacktick) {
                if (c == backtickChar) {
                    inBacktick = false;
                }
                i++;
                continue;
            }
            if (c == '-' && next == '-') {
                inLineComment = true;
                i++;
            } else if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
            } else if (c == quoteChar) {
                inSingleQuote = true;
            } else if (c == doubleQuoteChar) {
                inDoubleQuote = true;
            } else if (c == backtickChar) {
                inBacktick = true;
            } else if (c == semicolonChar) {
                return true;
            }
            i++;
        }
        return false;
    }
}
