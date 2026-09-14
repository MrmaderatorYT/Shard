package com.ccs.shard.editor.codeHighliter;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Sql extends Highlighter {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE",
        "SET", "DELETE", "CREATE", "ALTER", "DROP", "TABLE", "INDEX",
        "VIEW", "PROCEDURE", "FUNCTION", "TRIGGER", "DATABASE", "SCHEMA",
        "GRANT", "REVOKE", "COMMIT", "ROLLBACK", "BEGIN", "END", "DECLARE",
        "OPEN", "CLOSE", "FETCH", "EXEC", "EXECUTE", "RETURN", "RETURNS",
        "AS", "IS", "BEGIN", "END", "IF", "ELSE", "ELSEIF", "CASE",
        "WHEN", "THEN", "ELSE", "END", "WHILE", "FOR", "LOOP", "EXIT",
        "CONTINUE", "LEAVE", "REPEAT", "UNTIL", "DO", "CALL", "SET",
        "NULL", "NOT", "AND", "OR", "IN", "EXISTS", "BETWEEN", "LIKE",
        "IS", "TRUE", "FALSE", "HAVING", "GROUP", "BY", "ORDER", "ASC",
        "DESC", "LIMIT", "OFFSET", "UNION", "ALL", "INTERSECT", "EXCEPT",
        "DISTINCT", "TOP", "PERCENT", "WITH", "TIES", "AS", "JOIN",
        "LEFT", "RIGHT", "FULL", "INNER", "OUTER", "CROSS", "ON",
        "NATURAL", "USING", "MERGE", "INTO", "USING", "MATCHED",
        "WHEN", "THEN", "ELSE", "OUTPUT", "DELETED", "INSERTED",
        "IDENTITY", "SEQUENCE", "CONSTRAINT", "PRIMARY", "KEY",
        "FOREIGN", "REFERENCES", "UNIQUE", "CHECK", "DEFAULT", "AUTO_INCREMENT",
        "SERIAL", "BIGSERIAL", "SMALLSERIAL", "GENERATED", "ALWAYS", "BY",
        "STORED", "VOLATILE", "TEMPORARY", "TEMP", "UNLOGGED", "MATERIALIZED",
        "RECURSIVE", "LATERAL", "PIVOT", "UNPIVOT", "PIVOTXML",
        "APPLY", "CROSS", "OUTER", "APPLY", "TABLESAMPLE", "FOR",
        "SYSTEM_TIME", "FOR", "PORTION", "OF", "ROW", "ROWS",
        "RANGE", "GROUPS", "WINDOW", "OVER", "PARTITION", "BY",
        "ORDER", "ASC", "DESC", "NULLS", "FIRST", "LAST",
        "RANGE", "ROW", "GROUPS", "UNBOUNDED", "PRECEDING", "FOLLOWING",
        "CURRENT", "ROW", "EXCLUDE", "NO", "OTHERS", "TIES",
        "GROUPING", "SETS", "CUBE", "ROLLUP", "FILTER", "WHERE",
        "REPLACE", "FORCE", "IGNORE", "DELAYED", "LOW_PRIORITY",
        "HIGH_PRIORITY", "CONCURRENT", "LOCK", "SHARE", "EXCLUSIVE",
        "NOWAIT", "SKIP", "LOCKED", "NOLOCK", "READCOMMITTED",
        "READCOMMITTEDLOCK", "READUNCOMMITTED", "REPEATABLEREAD",
        "SERIALIZABLE", "SNAPSHOT", "NOEXPAND", "INDEX", "FORCE",
        "KEEP", "PLAN", "IGNORE", "PLAN", "OPTIMIZE", "FOR",
        "UNKNOWN", "OPTION", "RECOMPILE", "KEEPFIXED", "PLAN",
        "MAXDOP", "MIN_GRANT_PERCENT", "MAX_GRANT_PERCENT",
        "MIN_CPU_COST_PER_QUERY", "MAX_CPU_COST_PER_QUERY",
        "MIN_IO_COST_PER_QUERY", "MAX_IO_COST_PER_QUERY",
        "MIN_MEMORY_PER_QUERY", "MAX_MEMORY_PER_QUERY",
        "MAX_WORKER_THREADS", "MAX_GRANT_PERCENT", "MIN_GRANT_PERCENT",
        "FORCESEEK", "FORCESCAN", "HINT", "USE", "HINT",
        "HASH", "GROUP", "ORDER", "FORCE", "MERGE", "JOIN",
        "LOOP", "JOIN", "HASH", "JOIN", "MERGE", "JOIN",
        "REMOTE", "DISTRIBUTION", "ROUNDROBIN", "HASH", "MATCHED",
        "WHEN", "MATCHED", "SOURCE", "THEN", "UPDATE", "DELETE",
        "WHEN", "NOT", "MATCHED", "BY", "TARGET", "THEN", "INSERT",
        "WHEN", "NOT", "MATCHED", "BY", "SOURCE", "THEN", "DELETE",
        "OUTPUT", "$action", "INSERTED", "DELETED", "MERGE",
        "MERGE", "INTO", "USING", "ON", "WHEN", "MATCHED",
        "WHEN", "NOT", "MATCHED", "OUTPUT", "VALUES", "DEFAULT",
        "IDENTITY", "KEYSET", "FAST", "FAST", "FIRST", "LAST",
        "ABSOLUTE", "RELATIVE", "NEXT", "PRIOR", "SKIP", "FIRST",
        "LAST", "PERCENT", "TOP", "PERCENT", "WITH", "TIES",
        "SCROLL", "NO", "SCROLL", "HOLDLOCK", "UPDLOCK", "XLOCK",
        "PAGLOCK", "TABLOCK", "TABLOCKX", "ROWLOCK", "NOLOCK",
        "NOWAIT", "READPAST", "UPDLOCK", "XLOCK", "HOLDLOCK",
        "SERIALIZABLE", "REPEATABLEREAD", "READCOMMITTED",
        "READUNCOMMITTED", "SNAPSHOT", "READCOMMITTEDLOCK"
    ));

    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
        "INT", "INTEGER", "SMALLINT", "TINYINT", "BIGINT", "BIT",
        "DECIMAL", "NUMERIC", "MONEY", "SMALLMONEY", "FLOAT", "REAL",
        "DATE", "TIME", "DATETIME", "DATETIME2", "SMALLDATETIME",
        "DATETIMEOFFSET", "TIMESTAMP", "CHAR", "VARCHAR", "TEXT",
        "NCHAR", "NVARCHAR", "NTEXT", "BINARY", "VARBINARY", "IMAGE",
        "CURSOR", "TABLE", "XML", "SQL_VARIANT", "UNIQUEIDENTIFIER",
        "HierarchyID", "Geometry", "Geography", "RowVersion",
        "AUTO_INCREMENT", "SERIAL", "BIGSERIAL", "SMALLSERIAL",
        "UUID", "JSON", "JSONB", "ARRAY", "HSTORE", "INET",
        "CIDR", "MACADDR", "TSVECTOR", "TSQUERY", "BYTEA"
    ));

    private static final Set<String> CONSTANTS = new HashSet<>(Arrays.asList(
        "NULL", "TRUE", "FALSE", "DEFAULT", "CURRENT_DATE",
        "CURRENT_TIME", "CURRENT_TIMESTAMP", "LOCALTIME",
        "LOCALTIMESTAMP", "NOW", "GETDATE", "SYSDATETIME",
        "SYSUTCDATETIME", "SYSDATETIMEOFFSET"
    ));

    @Override
    protected Set<String> getKeywords() { return KEYWORDS; }

    @Override
    protected Set<String> getTypes() { return TYPES; }

    @Override
    protected Set<String> getConstants() { return CONSTANTS; }

    @Override
    protected String getLineCommentPattern() { return "--.*"; }

    @Override
    protected String getBlockCommentStart() { return "/*"; }

    @Override
    protected String getBlockCommentEnd() { return "*/"; }

    @Override
    protected String getStringPattern() { return "'[^']*'"; }

    @Override
    protected String getNumberPattern() { return "\\b\\d+\\.?\\d*\\b"; }

    @Override
    protected String getAnnotationPattern() { return "@[a-zA-Z_][a-zA-Z0-9_]*"; }

    @Override
    protected void applyHighlights(SpannableStringBuilder sb) {
        String code = sb.toString().toUpperCase(Locale.ROOT);
        applyPattern(sb, code, "--.*", COLOR_COMMENT, false);
        applyPattern(sb, code, "/\\*[\\s\\S]*?\\*/", COLOR_COMMENT, false);
        applyPattern(sb, code, "'[^']*'", COLOR_STRING, false);
        applyPattern(sb, code, getNumberPattern(), COLOR_NUMBER, false);

        StringBuilder kwRegex = new StringBuilder("\\b(");
        boolean first = true;
        for (String kw : getKeywords()) {
            if (!first) kwRegex.append("|");
            kwRegex.append(Pattern.quote(kw));
            first = false;
        }
        kwRegex.append(")\\b");
        Pattern kwPattern = Pattern.compile(kwRegex.toString());
        Matcher kwMatcher = kwPattern.matcher(code);
        while (kwMatcher.find()) {
            sb.setSpan(new ForegroundColorSpan(COLOR_KEYWORD), kwMatcher.start(), kwMatcher.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), kwMatcher.start(), kwMatcher.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}
