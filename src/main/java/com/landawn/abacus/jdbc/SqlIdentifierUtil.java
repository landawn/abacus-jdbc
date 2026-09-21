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

import com.landawn.abacus.query.SqlDialect.ProductInfo;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Strings;

/**
 * Shared rendering rules for SQL table/column identifiers used by the SQL-generating utilities in
 * this package ({@link DataTransferUtil} and {@link JdbcCodeGenerationUtil}); {@link JdbcUtil} also
 * relies on the delimiter-decoding helper when splitting qualified identifiers.
 *
 * <p>Centralizing the rules in a single implementation guarantees that a table or column name
 * is rendered identically no matter which utility generates the statement.</p>
 *
 * <p>The rules are:</p>
 * <ul>
 *   <li>A <i>simple</i> identifier (ASCII letter or underscore, then ASCII letters/digits/underscores)
 *       supplied without delimiters is emitted unquoted, so case-folding databases resolve it normally.</li>
 *   <li>Anything else &mdash; a non-simple name, or a name the caller explicitly delimited &mdash; is
 *       re-quoted with the dialect's quote character, doubling any embedded occurrence of that character.
 *       Preserving an explicit delimiter matters even when the decoded text is simple: dropping the
 *       delimiters from {@code "MixedCase"} changes its identity on case-folding databases.</li>
 * </ul>
 */
final class SqlIdentifierUtil {

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private SqlIdentifierUtil() {
        // utility class - prevent instantiation.
    }

    /**
     * Returns the identifier quote character for the given database product: a backtick for the
     * MySQL family, a double quote (the SQL standard delimiter) for everything else.
     *
     * @param dbProductInfo the resolved database product, or {@code null} if it is unknown
     * @return the quote string to wrap delimited identifiers with
     */
    static String quoteString(final ProductInfo dbProductInfo) {
        return dbProductInfo != null && Strings.containsAnyIgnoreCase(dbProductInfo.name(), "MySQL", "MariaDB") ? "`" : "\"";
    }

    /**
     * Wraps an identifier in the given quote string, doubling any embedded occurrence of it.
     *
     * @param identifier the decoded (undelimited) identifier text
     * @param quote the quote string to wrap with
     * @return the delimited identifier
     * @throws NullPointerException if {@code identifier} or {@code quote} is {@code null}.
     */
    static String quoteIdentifier(final String identifier, final String quote) {
        // Escape any embedded quote character by doubling it, then wrap, so identifiers containing
        // the active quote char produce valid SQL instead of unbalanced/injectable output.
        return Strings.wrap(identifier.replace(quote, quote + quote), quote);
    }

    /**
     * Tests whether an identifier can be emitted without delimiters: an ASCII letter or underscore
     * followed by ASCII letters, digits or underscores.
     *
     * @param identifier the identifier to test; may be {@code null} or empty
     * @return {@code true} if the identifier needs no quoting
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
    static boolean[] explicitlyDelimitedIdentifierParts(final String qualifiedName, final int partCount) {
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
     * @throws IllegalArgumentException if {@code tableName} is {@code null}, blank or is not a valid
     *         qualified identifier
     */
    static String renderTableName(final String tableName, final ProductInfo dbProductInfo) {
        final String[] parts = JdbcUtil.splitQualifiedSqlIdentifier(tableName, cs.tableName);
        final String quote = quoteString(dbProductInfo);
        final boolean[] explicitlyDelimitedParts = explicitlyDelimitedIdentifierParts(tableName, parts.length);

        if (parts.length == 1) {
            return explicitlyDelimitedParts[0] || !isSimpleSqlIdentifier(parts[0]) ? quoteIdentifier(parts[0], quote) : parts[0];
        }

        final StringBuilder sb = new StringBuilder(tableName.length() + parts.length * 2);

        for (int i = 0, len = parts.length; i < len; i++) {
            if (i > 0) {
                sb.append('.');
            }

            sb.append(explicitlyDelimitedParts[i] || !isSimpleSqlIdentifier(parts[i]) ? quoteIdentifier(parts[i], quote) : parts[i]);
        }

        return sb.toString();
    }

    /**
     * Renders a single column name for the given database product, decoding any delimiters the
     * caller supplied and re-quoting with the active dialect's quote character.
     *
     * @param columnName the raw, caller-supplied column name
     * @param dbProductInfo the resolved database product, or {@code null} if it is unknown
     * @return the rendered column name
     * @throws IllegalArgumentException if {@code columnName} is {@code null} or blank or is not a single identifier
     */
    static String renderColumnName(final String columnName, final ProductInfo dbProductInfo) {
        N.checkArgNotBlank(columnName, cs.columnName);

        final String[] parts = JdbcUtil.splitQualifiedSqlIdentifier(columnName, cs.columnName);

        if (parts.length != 1) {
            throw new IllegalArgumentException("'columnName' must be a single identifier: " + columnName);
        }

        // Parse every input, not just explicitly delimited names: this strips insignificant outer
        // whitespace consistently and prevents an unquoted qualified name from being silently
        // reinterpreted as one literal column containing a dot.
        return checkColumnName(parts[0], dbProductInfo, startsWithIdentifierDelimiter(columnName));
    }

    /**
     * Renders an already-decoded column name for the given database product.
     *
     * @param columnName the decoded (undelimited) column name
     * @param dbProductInfo the resolved database product, or {@code null} if it is unknown
     * @param explicitlyDelimited whether the caller's original text delimited this name, in which
     *        case it is re-quoted even if the decoded text is a simple identifier
     * @return the rendered column name
     * @throws IllegalArgumentException if {@code columnName} is {@code null} or blank
     */
    static String checkColumnName(final String columnName, final ProductInfo dbProductInfo, final boolean explicitlyDelimited) {
        N.checkArgNotBlank(columnName, cs.columnName);

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
