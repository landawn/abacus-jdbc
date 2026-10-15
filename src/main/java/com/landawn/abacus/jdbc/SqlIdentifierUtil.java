/*
 * Copyright (c) 2021, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.landawn.abacus.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;

import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.query.SqlDialect.ProductInfo;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Strings;

/**
 * Shared rendering rules for SQL table/column identifiers used by the SQL-generating utilities in
 * this package ({@link DataTransferUtil} and {@link JdbcCodeGenerationUtil}); {@link JdbcUtil} also
 * relies on the delimiter-decoding helper when splitting qualified identifiers.
 *
 * <p>Identifier quoting in generated SQL is disabled by the fixed {@link #IDENTIFIER_QUOTING_ENABLED} policy.
 * Only simple undelimited names are rendered. Explicit delimiters, punctuation, common reserved names and bare
 * expressions such as {@code ROWNUM} and {@code CURRENT_PATH} are rejected; exact metadata names must also retain
 * their identity under the database's case-folding rules.
 * Database-specific identifier restrictions still apply. Caller-provided SQL and expressions are not rewritten.</p>
 *
 * <p>The pure dialect lookup {@link #quoteString(ProductInfo)}, parsing and {@link #quoteIdentifier(String, String)}
 * encoding helpers remain available independently of that policy. The connection-based delimiter lookup returns
 * {@code null} without accessing metadata while quoting is disabled. Callers must still guard work preparing its
 * arguments; validation, metadata name decoding and parsing caller-provided SQL still run while quoting is disabled.
 * If quoting is enabled in a future build, non-simple or explicitly delimited caller names and exact metadata names
 * use the database's delimiter.</p>
 */
final class SqlIdentifierUtil {

    /** Fixed build policy: changing this requires recompilation; an operation cannot observe a mid-statement toggle. */
    private static final boolean IDENTIFIER_QUOTING_ENABLED = false;

    /**
     * Common SQL keywords and bare value expressions that must not be emitted as unquoted identifiers.
     * USER/CURRENT_USER, CURRENT_PATH and ROWNUM can execute successfully while reading an expression instead of
     * the stored column. In particular, a ROWNUM key predicate can update the wrong row, so successful SQL preparation
     * does not establish that an unquoted name still identifies the intended column.
     * This is deliberately not an exhaustive list of every database's reserved words.
     */
    private static final Set<String> UNQUOTED_KEYWORDS = Set.of("ALL", "ALTER", "AND", "ANY", "AS", "ASC", "BETWEEN", "BY", "CASE", "CHECK", "COLUMN",
            "CONSTRAINT", "CREATE", "CROSS", "CURRENT_CATALOG", "CURRENT_DATE", "CURRENT_PATH", "CURRENT_ROLE", "CURRENT_SCHEMA", "CURRENT_TIME",
            "CURRENT_TIMESTAMP", "CURRENT_USER", "DEFAULT", "DELETE", "DESC", "DISTINCT", "DROP", "ELSE", "END", "EXCEPT", "EXISTS", "FALSE", "FETCH", "FOR",
            "FOREIGN", "FROM", "FULL", "GRANT", "GROUP", "HAVING", "IN", "INNER", "INSERT", "INTERSECT", "INTO", "IS", "JOIN", "LEFT", "LIKE", "LIMIT",
            "LOCALTIME", "LOCALTIMESTAMP", "NATURAL", "NOT", "NULL", "OFFSET", "ON", "OR", "ORDER", "OUTER", "PRIMARY", "REFERENCES", "RETURNING", "RIGHT",
            "ROWNUM", "SELECT", "SESSION_USER", "SET", "SOME", "SYSTEM_USER", "TABLE", "THEN", "TRUE", "UNION", "UNIQUE", "UPDATE", "USER", "USING", "VALUE",
            "VALUES", "WHEN", "WHERE", "WITH", "YEAR");

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private SqlIdentifierUtil() {
        // utility class - prevent instantiation.
    }

    /**
     * Returns the fixed policy for identifier quoting in generated SQL. Use this accessor for policy checks
     * so compilers do not flag the retained quoting branches as dead code when the policy is disabled.
     */
    static boolean isIdentifierQuotingEnabled() {
        return IDENTIFIER_QUOTING_ENABLED;
    }

    /** Tests the lexical, common-keyword and bare-expression restrictions for identifiers rendered without delimiters. */
    static boolean isUnquotedIdentifier(final String identifier) {
        return isSimpleSqlIdentifier(identifier) && !UNQUOTED_KEYWORDS.contains(identifier.toUpperCase(Locale.ROOT));
    }

    /** Rejects an explicit delimiter rather than silently changing an exact name to a case-folded name. */
    static void checkExplicitDelimiters(final String identifier, final boolean explicitlyDelimited) {
        if (!isIdentifierQuotingEnabled() && explicitlyDelimited) {
            throw quotingDisabled(identifier);
        }
    }

    private static IllegalArgumentException quotingDisabled(final String identifier) {
        return new IllegalArgumentException("Identifier quoting is disabled; cannot render identifier: " + identifier);
    }

    /**
     * Returns a dialect delimiter when no connection metadata is available. SQL Server/ASE use brackets;
     * MySQL, MariaDB, Spark, Databricks, Hive and BigQuery use backticks. Other products use double quotes.
     *
     * @param dbProductInfo the resolved database product, or {@code null} if it is unknown
     * @return the quote string to wrap delimited identifiers with
     */
    static String quoteString(final ProductInfo dbProductInfo) {
        final String product = dbProductInfo == null ? null : dbProductInfo.name();
        return usesBracketQuotes(product) ? "["
                : Strings.containsAnyIgnoreCase(product, "MySQL", "MariaDB", "Spark", "Databricks", "Hive", "BigQuery") ? "`" : "\"";
    }

    /**
     * Reads the connection's identifier delimiter, returning {@code null} without accessing the connection or
     * inspecting the database product when identifier quoting is disabled. When enabled, SQL Server and ASE use
     * brackets because their reported double quote depends on a session option.
     *
     * @param conn the connection whose metadata supplies the quote string; ignored when quoting is disabled
     * @param dbProductInfo the database product; ignored when quoting is disabled
     * @return the opening delimiter, or {@code null} when quoting is disabled or the driver reports no quoting support
     * @throws UncheckedSQLException if reading identifier metadata fails while quoting is enabled
     */
    static String quoteString(final Connection conn, final ProductInfo dbProductInfo) throws UncheckedSQLException {
        if (!isIdentifierQuotingEnabled()) {
            return null;
        }
        if (usesBracketQuotes(dbProductInfo == null ? null : dbProductInfo.name())) {
            return "[";
        }
        try {
            final String quote = conn.getMetaData().getIdentifierQuoteString();
            return Strings.isBlank(quote) ? null : quote.trim();
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /** Identifies products with session-independent bracket delimiters. */
    static boolean usesBracketQuotes(final String product) {
        return Strings.containsAnyIgnoreCase(product, "SQL Server", "Adaptive Server Enterprise", "SAP ASE") || "ASE".equalsIgnoreCase(product);
    }

    /**
     * Wraps an identifier in the given quote string, doubling embedded closing delimiters.
     * An opening bracket uses a closing bracket; other delimiters are symmetric.
     * This encoding primitive always escapes its input, including when SQL rendering disables quoting.
     *
     * @param identifier the decoded (undelimited) identifier text
     * @param quote the quote string to wrap with
     * @return the delimited identifier
     * @throws IllegalArgumentException if {@code identifier} is {@code null}, or {@code quote} is {@code null} or empty.
     */
    static String quoteIdentifier(final String identifier, final String quote) throws IllegalArgumentException {
        N.checkArgNotNull(identifier, cs.identifier);
        N.checkArgNotEmpty(quote, cs.quote);

        // Escape any embedded quote character by doubling it, then wrap, so identifiers containing
        // the active quote char produce valid SQL instead of unbalanced/injectable output.
        final String closingQuote = "[".equals(quote) ? "]" : quote;
        return quote + identifier.replace(closingQuote, closingQuote + closingQuote) + closingQuote;
    }

    /**
     * Renders a stored name with the driver's delimiter when quoting is enabled. Otherwise only simple names
     * that are not common SQL keywords or bare expressions can be emitted. The caller must separately check stored letter case.
     *
     * @param identifier the exact, decoded identifier
     * @param quote the driver's delimiter, or {@code null} if quoting is unsupported
     * @return the identifier rendered for SQL
     * @throws IllegalArgumentException if the name is blank or cannot be represented without quoting
     */
    static String renderStoredIdentifier(final String identifier, final String quote) {
        N.checkArgNotBlank(identifier, cs.identifier);
        if (!isIdentifierQuotingEnabled()) {
            if (!isUnquotedIdentifier(identifier)) {
                throw quotingDisabled(identifier);
            }
            return identifier;
        }
        if (quote != null) {
            return quoteIdentifier(identifier, quote);
        }
        if (!isSimpleSqlIdentifier(identifier)) {
            throw new IllegalArgumentException("Database does not support quoting identifier: " + identifier);
        }
        return identifier;
    }

    /**
     * Renders an exact metadata name, rejecting a spelling that would resolve differently without delimiters.
     *
     * @param identifier the exact metadata name
     * @param quote the driver's delimiter
     * @param unquotedIdentifierCase positive for upper-case storage, negative for lower-case storage, zero for no folding
     * @return the SQL identifier
     * @throws IllegalArgumentException if the name cannot be rendered under the current quoting policy
     */
    static String renderStoredIdentifier(final String identifier, final String quote, final int unquotedIdentifierCase) {
        N.checkArgNotBlank(identifier, cs.identifier);
        if (!isIdentifierQuotingEnabled() && ((unquotedIdentifierCase > 0 && !identifier.equals(identifier.toUpperCase(Locale.ROOT)))
                || (unquotedIdentifierCase < 0 && !identifier.equals(identifier.toLowerCase(Locale.ROOT))))) {
            throw quotingDisabled(identifier);
        }
        return renderStoredIdentifier(identifier, quote);
    }

    /**
     * Tests whether an identifier can be emitted without delimiters: an ASCII letter or underscore
     * followed by ASCII letters, digits or underscores.
     *
     * @param identifier the identifier to test; may be {@code null} or empty
     * @return {@code true} if the identifier has simple lexical form; keywords and letter case are not checked
     */
    static boolean isSimpleSqlIdentifier(final String identifier) {
        if (Strings.isEmpty(identifier)) {
            return false;
        }

        final char first = identifier.charAt(0);

        if (!(Strings.isAsciiAlpha(first) || first == '_')) {
            return false;
        }

        for (int i = 1, len = identifier.length(); i < len; i++) {
            final char ch = identifier.charAt(i);

            if (!(Strings.isAsciiAlpha(ch) || Strings.isAsciiNumeric(ch) || ch == '_')) {
                return false;
            }
        }

        return true;
    }

    /**
     * Tests whether an identifier's first non-blank character opens a delimited identifier.
     *
     * @param identifier the raw, caller-supplied identifier text
     * @return {@code true} if the text starts with {@code "}, {@code `} or {@code [}
     */
    static boolean startsWithIdentifierDelimiter(final String identifier) {
        final String trimmed = Strings.stripToEmpty(identifier);

        return !trimmed.isEmpty() && isOpeningDelimiter(trimmed.charAt(0));
    }

    /**
     * Tests whether an identifier is wrapped in a matching pair of delimiters.
     *
     * @param identifier the raw, caller-supplied identifier text
     * @return {@code true} if the text both opens and closes with the same delimiter style
     */
    static boolean isDelimitedIdentifier(final String identifier) {
        final String trimmed = Strings.stripToEmpty(identifier);

        if (trimmed.length() < 2) {
            return false;
        }

        final char first = trimmed.charAt(0);
        final char last = trimmed.charAt(trimmed.length() - 1);

        return (first == '"' && last == '"') || (first == '`' && last == '`') || (first == '[' && last == ']');
    }

    /**
     * Removes a matching pair of identifier delimiters and unescapes doubled delimiters inside the
     * body, so the returned text is the literal identifier.
     *
     * <p>Unescaping matters because callers re-quote the result: without it, {@code "a""b"} would
     * decode to the body {@code a""b} and re-quote to {@code "a""""b"}.</p>
     *
     * @param identifier the raw, caller-supplied identifier text
     * @return the decoded identifier, stripped of surrounding blanks
     */
    static String stripIdentifierDelimiters(final String identifier) {
        final String trimmed = Strings.stripToEmpty(identifier);

        if (trimmed.length() >= 2) {
            final char first = trimmed.charAt(0);
            final char last = trimmed.charAt(trimmed.length() - 1);

            if ((first == '"' && last == '"') || (first == '`' && last == '`')) {
                return trimmed.substring(1, trimmed.length() - 1).replace("" + first + first, String.valueOf(first));
            }

            if (first == '[' && last == ']') {
                return trimmed.substring(1, trimmed.length() - 1).replace("]]", "]");
            }
        }

        return trimmed;
    }

    /**
     * Returns which parts of a validated qualified identifier were explicitly delimited in the
     * caller's text. {@link JdbcUtil#splitQualifiedSqlIdentifier(String, String)} deliberately
     * returns decoded names only, so this companion scan retains the case-sensitivity signal needed
     * when the names are re-rendered for another database dialect.
     *
     * @param qualifiedName the raw, caller-supplied qualified identifier
     * @param partCount the number of parts {@code splitQualifiedSqlIdentifier} produced for it
     * @return one flag per part, in order
     * @throws NegativeArraySizeException if {@code partCount} is negative
     */
    static boolean[] explicitlyDelimitedIdentifierParts(final String qualifiedName, final int partCount) throws NegativeArraySizeException {
        final boolean[] result = new boolean[partCount];
        final String trimmed = Strings.stripToEmpty(qualifiedName);
        int partIndex = 0;
        char closingQuote = 0;
        boolean atPartStart = true;
        boolean explicitlyDelimited = false;

        for (int i = 0, len = trimmed.length(); i < len; i++) {
            final char ch = trimmed.charAt(i);

            if (closingQuote == 0) {
                if (ch == '.') {
                    if (partIndex < partCount) {
                        result[partIndex] = explicitlyDelimited;
                    }

                    partIndex++;
                    atPartStart = true;
                    explicitlyDelimited = false;
                } else if (atPartStart && !Character.isWhitespace(ch)) {
                    explicitlyDelimited = isOpeningDelimiter(ch);
                    closingQuote = ch == '[' ? ']' : (explicitlyDelimited ? ch : 0);
                    atPartStart = false;
                }
            } else if (ch == closingQuote) {
                if (i + 1 < len && trimmed.charAt(i + 1) == closingQuote) {
                    i++;
                } else {
                    closingQuote = 0;
                }
            }
        }

        if (partIndex < partCount) {
            result[partIndex] = explicitlyDelimited;
        }

        return result;
    }

    /**
     * Renders a (possibly qualified) table name for the given database product.
     *
     * @param tableName the raw, caller-supplied table name; may be qualified and may use delimiters
     * @param dbProductInfo the resolved database product, or {@code null} if it is unknown
     * @return the rendered table name
     * @throws IllegalArgumentException if {@code tableName} is {@code null}, blank, malformed, or cannot be rendered
     *         under the fixed quoting policy
     */
    static String renderTableName(final String tableName, final ProductInfo dbProductInfo) throws IllegalArgumentException {
        return renderTableNameWithQuote(tableName, isIdentifierQuotingEnabled() ? quoteString(dbProductInfo) : null);
    }

    /** Renders a caller-supplied table name under the fixed policy, using the connection's delimiter only when enabled. */
    static String renderTableNameWithQuote(final String tableName, final String quote) throws IllegalArgumentException {
        final String[] parts = JdbcUtil.splitQualifiedSqlIdentifier(tableName, cs.tableName);
        final boolean[] explicitlyDelimitedParts = explicitlyDelimitedIdentifierParts(tableName, parts.length);

        if (!isIdentifierQuotingEnabled()) {
            for (int i = 0; i < parts.length; i++) {
                checkExplicitDelimiters(parts[i], explicitlyDelimitedParts[i]);
                renderStoredIdentifier(parts[i], null);
            }
            return String.join(".", parts);
        }

        if (parts.length == 1) {
            return explicitlyDelimitedParts[0] || !isSimpleSqlIdentifier(parts[0]) ? renderStoredIdentifier(parts[0], quote) : parts[0];
        }

        final StringBuilder sb = new StringBuilder(tableName.length() + parts.length * 2);

        for (int i = 0, len = parts.length; i < len; i++) {
            if (i > 0) {
                sb.append('.');
            }

            sb.append(explicitlyDelimitedParts[i] || !isSimpleSqlIdentifier(parts[i]) ? renderStoredIdentifier(parts[i], quote) : parts[i]);
        }

        return sb.toString();
    }

    /**
     * Renders a single column name for the given database product. Explicit delimiters are rejected while quoting
     * is disabled; when enabled they are decoded and replaced with the active dialect's delimiter.
     *
     * @param columnName the raw, caller-supplied column name
     * @param dbProductInfo the resolved database product, or {@code null} if it is unknown
     * @return the rendered column name
     * @throws IllegalArgumentException if {@code columnName} is {@code null}, blank, not a single identifier, or needs quoting
     */
    static String renderColumnName(final String columnName, final ProductInfo dbProductInfo) throws IllegalArgumentException {
        return renderColumnNameWithQuote(columnName, isIdentifierQuotingEnabled() ? quoteString(dbProductInfo) : null);
    }

    /** Renders a caller-supplied column name under the fixed quoting policy. */
    static String renderColumnNameWithQuote(final String columnName, final String quote) throws IllegalArgumentException {
        N.checkArgNotBlank(columnName, cs.columnName);

        final String[] parts = JdbcUtil.splitQualifiedSqlIdentifier(columnName, cs.columnName);

        if (parts.length != 1) {
            throw new IllegalArgumentException("'columnName' must be a single identifier: " + columnName);
        }

        if (!isIdentifierQuotingEnabled()) {
            checkExplicitDelimiters(parts[0], startsWithIdentifierDelimiter(columnName));
            return renderStoredIdentifier(parts[0], null);
        }

        // Parse every input, not just explicitly delimited names: this strips insignificant outer
        // whitespace consistently and prevents an unquoted qualified name from being silently
        // reinterpreted as one literal column containing a dot.
        return !startsWithIdentifierDelimiter(columnName) && isSimpleSqlIdentifier(parts[0]) ? parts[0] : renderStoredIdentifier(parts[0], quote);
    }

    /**
     * Renders an already-decoded column name for the given database product.
     *
     * @param columnName the decoded (undelimited) column name
     * @param dbProductInfo the resolved database product, or {@code null} if it is unknown
     * @param explicitlyDelimited whether the name requires exact spelling; rejected when quoting is disabled,
     *        otherwise re-quoted even if the decoded text is a simple identifier
     * @return the rendered column name
     * @throws IllegalArgumentException if {@code columnName} is {@code null}, blank, or cannot be rendered under the quoting policy
     */
    static String checkColumnName(final String columnName, final ProductInfo dbProductInfo, final boolean explicitlyDelimited) throws IllegalArgumentException {
        N.checkArgNotBlank(columnName, cs.columnName);

        if (!isIdentifierQuotingEnabled()) {
            checkExplicitDelimiters(columnName, explicitlyDelimited);
            return renderStoredIdentifier(columnName, null);
        }

        return !explicitlyDelimited && isSimpleSqlIdentifier(columnName) ? columnName : quoteIdentifier(columnName, quoteString(dbProductInfo));
    }

    /**
     * Tests whether a character can open a delimited identifier.
     *
     * @param ch the character to test
     * @return {@code true} if {@code ch} is {@code "}, {@code `} or {@code [}
     */
    private static boolean isOpeningDelimiter(final char ch) {
        return ch == '"' || ch == '`' || ch == '[';
    }
}
