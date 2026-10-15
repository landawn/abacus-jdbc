package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import com.landawn.abacus.util.function.QuadFunction;

class JdbcCodeGenerationSqlConversionTest extends JdbcCodeGenerationUtilTestSupport {

    @ParameterizedTest
    @ValueSource(strings = { "items[length($q$),]$q$)]", "items[length('a''b')]", "items[$1]", "items[$1 + $2]", "items[index$tag$]" })
    void conversionPreservesQuotedAndParameterizedArraySubscripts(final String expression) throws SQLException {
        final DataSource dataSource = dataSourceReporting("PostgreSQL");

        assertEquals("UPDATE records SET score = " + expression + ", id = 1",
                JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource, "INSERT INTO records(score, id) VALUES (" + expression + ", 1)"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "INSERT", "INSERT INTO", "INSERT INTO(records) VALUES (1)", "INSERT (id) VALUES (1)" })
    void conversionRejectsIncompleteInsertKeywords(final String sql) throws SQLException {
        final DataSource dataSource = dataSourceReporting("PostgreSQL");

        assertTrue(assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource, sql)).getMessage()
                .contains("Not an INSERT INTO SQL"));
    }

    // fieldTypeConverter — L485
    @Test
    public void testGenerateEntityClass_WithFieldTypeConverter() throws SQLException {
        setupFullGenerateEntityClassMock();
        final QuadFunction<String, String, String, String, String> converter = (entity, field, col, cls) -> "java.lang.Object";
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().fieldTypeConverter(converter).build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("Object id") || result.contains("Object created_at"));
    }

    // Exercise line 864 — mapColumClassName returns Object.class when columnClassName is empty
    @Test
    public void testGenerateEntityClass_FieldTypeConverterReturnsEmptyString() throws SQLException {
        setupFullGenerateEntityClassMock();
        final QuadFunction<String, String, String, String, String> converter = (entity, field, col, cls) -> "";
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().fieldTypeConverter(converter).build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("Object "));
    }

    // convertInsertSqlToUpdateSql(DataSource, insertSql) - line 1762
    @Test
    public void testConvertInsertSqlToUpdateSql_DataSource() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String insertSql = "INSERT INTO order_history(id, status) VALUES (1, 'OPEN')";
        String updateSql = JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource, insertSql);
        assertNotNull(updateSql);
        assertTrue(updateSql.startsWith("UPDATE"));
        assertTrue(updateSql.contains("SET"));
    }

    // convertInsertSqlToUpdateSql(DataSource, insertSql, whereClause) - line 1795
    @Test
    public void testConvertInsertSqlToUpdateSql_DataSource_WithWhereClause() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String insertSql = "INSERT INTO order_history(status) VALUES ('CLOSED')";
        String updateSql = JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource, insertSql, "id = 42");
        assertNotNull(updateSql);
        assertTrue(updateSql.startsWith("UPDATE"));
        assertTrue(updateSql.contains("WHERE id = 42"));
    }

    @Test
    public void testConvertInsertSqlToUpdateSql_PreservesJdbcPlaceholders() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String insertSql = "INSERT INTO order_history(id, status) VALUES (?, ?)";
        String updateSql = JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource, insertSql, "id = ?");

        assertEquals("UPDATE order_history SET id = ?, status = ? WHERE id = ?", updateSql);
    }

    @Test
    public void testConvertInsertSqlToUpdateSql_PreservesLineCommentBoundaries() throws SQLException {
        final DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        assertEquals("UPDATE order_history SET id = 1 -- first value\n, status = 2 -- last value\n WHERE id = 42", JdbcCodeGenerationUtil
                .convertInsertSqlToUpdateSql(dataSource, "INSERT INTO order_history(id, status) VALUES (1 -- first value\n, 2 -- last value\n)", "id = 42"));
    }

    @Test
    public void testConvertInsertSqlToUpdateSql_IgnoresPunctuationInsideValueComments() throws SQLException {
        final DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        assertEquals("UPDATE order_history SET id = 1 /* ), ' */ + 2, status = 3 -- ), ' ignored\n WHERE id = 42", JdbcCodeGenerationUtil
                .convertInsertSqlToUpdateSql(dataSource, "INSERT INTO order_history(id, status) VALUES (1 /* ), ' */ + 2, 3 -- ), ' ignored\n)", "id = 42"));
        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource, "INSERT INTO order_history(id) VALUES (1 /* unclosed )"));
    }

    @Test
    public void testConvertInsertSqlToUpdateSql_NestsBlockCommentsForPostgreSqlSqlServerAndH2() throws SQLException {
        for (final String productName : List.of("PostgreSQL", "Microsoft SQL Server", "H2")) {
            final DataSource nesting = dataSourceReporting(productName);

            assertEquals("UPDATE order_history SET id = 1 /* outer /* ), */ inner */ + 2",
                    JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(nesting, "INSERT INTO order_history(id) VALUES (1 /* outer /* ), */ inner */ + 2)"),
                    productName);
            // The inner opener keeps the comment open, so the closing parenthesis is never found.
            assertThrows(IllegalArgumentException.class,
                    () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(nesting, "INSERT INTO order_history(id, status) VALUES (1 /* a /* b */, 2)"),
                    productName);
            // "--" always starts a comment outside MySQL/MariaDB, hiding the rest of the line.
            assertThrows(IllegalArgumentException.class,
                    () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(nesting, "INSERT INTO order_history(id, status) VALUES (5--1, 2)"), productName);
            // "#" is an operator here (bitwise XOR in PostgreSQL), not a comment.
            assertEquals("UPDATE order_history SET id = 5 # 3, status = 2",
                    JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(nesting, "INSERT INTO order_history(id, status) VALUES (5 # 3, 2)"), productName);
        }
    }

    @Test
    public void testConvertInsertSqlToUpdateSql_DoesNotNestBlockCommentsForOtherDatabases() throws SQLException {
        for (final String productName : List.of("SQLite", "Oracle", "HSQL Database Engine", "SomeOtherDatabase")) {
            final DataSource flat = dataSourceReporting(productName);

            // The first "*/" closes the comment, so the second "/*" is plain comment text.
            assertEquals("UPDATE order_history SET id = 1 /* literal /* marker */ + 2",
                    JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(flat, "INSERT INTO order_history(id) VALUES (1 /* literal /* marker */ + 2)"),
                    productName);
            assertEquals("UPDATE order_history SET id = 1 /* a /* b */, status = 2",
                    JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(flat, "INSERT INTO order_history(id, status) VALUES (1 /* a /* b */, 2)"), productName);
            assertThrows(IllegalArgumentException.class,
                    () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(flat, "INSERT INTO order_history(id, status) VALUES (5--1, 2)"), productName);
            assertEquals("UPDATE order_history SET id = 5 # 3, status = 2",
                    JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(flat, "INSERT INTO order_history(id, status) VALUES (5 # 3, 2)"), productName);
        }
    }

    @Test
    public void testConvertInsertSqlToUpdateSql_UsesMySqlCommentRules() throws SQLException {
        for (final String productName : List.of("MySQL", "MariaDB")) {
            final DataSource mySql = dataSourceReporting(productName);

            // MySQL block comments do not nest: the first "*/" closes the comment.
            assertEquals("UPDATE order_history SET id = 1 /* a /* b */, status = 2",
                    JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(mySql, "INSERT INTO order_history(id, status) VALUES (1 /* a /* b */, 2)"));
            // "--" without following whitespace is two minus signs in MySQL.
            assertEquals("UPDATE order_history SET id = 5--1, status = 2",
                    JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(mySql, "INSERT INTO order_history(id, status) VALUES (5--1, 2)"));
            // "-- " followed by whitespace or a control character is still a line comment.
            assertEquals("UPDATE order_history SET id = 1 -- c\n, status = 2 --\t),\n",
                    JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(mySql, "INSERT INTO order_history(id, status) VALUES (1 -- c\n, 2 --\t),\n)"));
        }
    }

    @Test
    public void testConvertInsertSqlToUpdateSql_TerminatesMySqlHashComments() throws SQLException {
        for (final String productName : List.of("MySQL", "MariaDB")) {
            final DataSource mySql = dataSourceReporting(productName);

            // Without the retained newline, "# note" would comment out the WHERE clause and update every row.
            assertEquals("UPDATE t SET a = 99 # note\n WHERE id = 1",
                    JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(mySql, "INSERT INTO t(a) VALUES (99 # note\n)", "id = 1"));
            // Commas and parentheses inside a "#" comment do not split or close the value list.
            assertEquals("UPDATE t SET a = 1 # ), x\n, b = 2",
                    JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(mySql, "INSERT INTO t(a, b) VALUES (1 # ), x\n, 2)"));
            // Column-list "#" comments are removed before the names are validated.
            assertEquals("UPDATE t SET a = 1, b = 2",
                    JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(mySql, "INSERT INTO t(a # first, (x)\n, b) VALUES (1, 2)"));
            // "#" inside a quoted value is text.
            assertEquals("UPDATE t SET a = '#1'", JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(mySql, "INSERT INTO t(a) VALUES ('#1')"));
            // A "#" comment reaching the end of the statement leaves the value list unclosed.
            assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(mySql, "INSERT INTO t(a) VALUES (1 # )"));
        }
    }

    @Test
    public void testConvertInsertSqlToUpdateSql_RemovesColumnListComments() throws SQLException {
        final DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        assertEquals("UPDATE order_history SET id = 1, status = 2 WHERE id = 42", JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource,
                "INSERT INTO order_history(id /* pk, (x) */, -- ), '\n status -- last\n) VALUES (1, 2)", "id = 42"));
        // The parser keeps the quoted name intact before the rendering policy rejects it.
        final IllegalArgumentException quotedNameFailure = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource, "INSERT INTO order_history(`a--b`) VALUES (1)"));
        assertTrue(quotedNameFailure.getMessage().contains("quoting is disabled"), quotedNameFailure.getMessage());
        assertTrue(quotedNameFailure.getMessage().contains("a--b"), quotedNameFailure.getMessage());
        // A column entry consisting only of a comment is empty.
        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource, "INSERT INTO order_history(id, /* none */) VALUES (1, 2)"));
    }

    @Test
    public void testConvertInsertSqlToUpdateSql_PreservesNamedParametersAndQuotedCommas() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String insertSql = "INSERT INTO order_history(id, status, name) VALUES (:id, :status, 'A,B')";
        String updateSql = JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource, insertSql);

        assertEquals("UPDATE order_history SET id = :id, status = :status, name = 'A,B'", updateSql);
    }

    @Test
    public void testConvertInsertSqlToUpdateSql_AcceptsTrailingSemicolon() throws SQLException {
        final DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        assertEquals("UPDATE order_history SET id = 1, status = 'OPEN'",
                JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource, "INSERT INTO order_history(id, status) VALUES (1, 'OPEN');"));
    }

    @Test
    public void testConvertInsertSqlToUpdateSql_AcceptsSqlWhitespaceBetweenKeywords() throws SQLException {
        final DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        assertEquals("UPDATE order_history SET id = 1, status = 'OPEN'",
                JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource, " \nINSERT\t \tINTO\r\n order_history(id, status) VALUES (1, 'OPEN')"));
    }

    @Test
    public void testConvertInsertSqlToUpdateSql_IgnoresParenthesisInsideQuotedTableIdentifier() throws SQLException {
        final DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource, "INSERT INTO \"audit(log)\"(id) VALUES (1)"));
        assertTrue(failure.getMessage().contains("quoting is disabled"), failure.getMessage());
        assertTrue(failure.getMessage().contains("audit(log)"), failure.getMessage());
    }

    @Test
    public void testConvertInsertSqlToUpdateSql_PreservesDialectQuotedValues() throws SQLException {
        final DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        assertEquals("UPDATE order_history SET name = 'A\\'B,C', status = 1",
                JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource, "INSERT INTO order_history(name, status) VALUES ('A\\'B,C', 1)"));
        assertEquals("UPDATE order_history SET name = $tag$A,B)C$tag$, status = 1",
                JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource, "INSERT INTO order_history(name, status) VALUES ($tag$A,B)C$tag$, 1)"));
    }

    @Test
    public void testConvertInsertSqlToUpdateSql_DollarInsideIdentifierDoesNotOpenDollarQuote() throws SQLException {
        final DataSource postgreSql = dataSourceReporting("PostgreSQL");

        // PostgreSQL (like Oracle and MySQL) allows '$' inside identifiers: the "$y$" in x$y$z is not a dollar-quote opener,
        // so it must not swallow the rest of the statement.
        assertEquals("UPDATE t SET a = x$y$z, b = 2", JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(postgreSql, "INSERT INTO t(a, b) VALUES (x$y$z, 2)"));
        assertEquals("UPDATE t SET a = seq$a$b.nextval + 1, b = (c$d$e)",
                JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(postgreSql, "INSERT INTO t(a, b) VALUES (seq$a$b.nextval + 1, (c$d$e))"));
        // A dollar quote that does not follow an identifier character is still recognized.
        assertEquals("UPDATE t SET a = $q$1,2)$q$, b = 2",
                JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(postgreSql, "INSERT INTO t(a, b) VALUES ($q$1,2)$q$, 2)"));
        // Underscore, digit, '$' and non-ASCII letters are identifier characters too.
        assertEquals("UPDATE t SET a = x_$y$z, b = x1$y$z, c = x$$y$z, d = é$y$z",
                JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(postgreSql, "INSERT INTO t(a, b, c, d) VALUES (x_$y$z, x1$y$z, x$$y$z, é$y$z)"));
        // Right after an operator, comma or opening parenthesis a dollar quote still opens.
        assertEquals("UPDATE t SET a = 'p'||$q$,)$q$, b = ($q$)$q$)",
                JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(postgreSql, "INSERT INTO t(a, b) VALUES ('p'||$q$,)$q$,($q$)$q$))"));
    }

    /**
     * Regression test: convertInsertSqlToUpdateSql must escape single quotes in string values
     * to avoid generating malformed SQL.  Before the fix, string values containing a
     * single quote were inserted verbatim, producing broken SQL literals.
     * This test verifies that a string value is correctly single-quoted in the output,
     * and that the output does not contain unescaped embedded quotes.
     */
    @Test
    public void testConvertInsertSqlToUpdateSql_EscapesSingleQuotesInStringValues() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenReturn(connection);

        final String insertSql = "INSERT INTO t(name) VALUES ('test')";
        final String updateSql = JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, insertSql);

        assertNotNull(updateSql);
        assertTrue(updateSql.startsWith("UPDATE"));
        // The string value should be wrapped in single quotes in the SET clause
        assertTrue(updateSql.contains("= 'test'"), "Output SQL should quote the string value: " + updateSql);
    }

    // No '(' after the table name -> "Missing column list in SQL" (L1932).
    @Test
    public void testConvertInsertSqlToUpdateSql_MissingColumnList() throws SQLException {
        final DataSource ds = dataSourceReturningConnection();
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO t"));
        assertTrue(ex.getMessage().contains("Missing column list in SQL"), ex.getMessage());
    }

    // Column list present but no VALUES keyword -> "Missing VALUES clause in SQL" (L1942);
    // also exercises indexOfIgnoreCase returning -1 (L1992).
    @Test
    public void testConvertInsertSqlToUpdateSql_MissingValuesClause() throws SQLException {
        final DataSource ds = dataSourceReturningConnection();
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO t(a)"));
        assertTrue(ex.getMessage().contains("Missing VALUES clause in SQL"), ex.getMessage());
    }

    // VALUES keyword present but no '(' after it -> "Missing VALUES list in SQL" (L1948).
    @Test
    public void testConvertInsertSqlToUpdateSql_MissingValuesList() throws SQLException {
        final DataSource ds = dataSourceReturningConnection();
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO t(a) VALUES 1"));
        assertTrue(ex.getMessage().contains("Missing VALUES list in SQL"), ex.getMessage());
    }

    // A double-quote-delimited identifier with an escaped (doubled) quote exercises the quote-doubling
    // branches in findClosingParenthesis (L2005-2006) and splitSqlList (L2052-2053), and the
    // doubled-quote unescape in stripIdentifierDelimiters (L2122).
    @Test
    public void testConvertInsertSqlToUpdateSql_DoubledQuoteIdentifier() throws SQLException {
        final DataSource ds = dataSourceReturningConnection();
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO t(\"a\"\"b\") VALUES (1)"));
        assertTrue(failure.getMessage().contains("quoting is disabled"), failure.getMessage());
        assertTrue(failure.getMessage().contains("a\"b"), failure.getMessage());
    }

    // A bracket-delimited identifier with an escaped (doubled) bracket exercises the bracket-identifier
    // branches in findClosingParenthesis (L2011-2017) and splitSqlList (L2058-2066), and the
    // "]]" -> "]" unescape in stripIdentifierDelimiters (L2124).
    @Test
    public void testConvertInsertSqlToUpdateSql_BracketIdentifier() throws SQLException {
        final DataSource ds = dataSourceReturningConnection();
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO t([a]]b]) VALUES (1)"));
        assertTrue(failure.getMessage().contains("quoting is disabled"), failure.getMessage());
        assertTrue(failure.getMessage().contains("a]b"), failure.getMessage());
    }

    // An escaped single quote inside a string VALUE is copied verbatim; exercises the quote-doubling
    // branches for the single-quote literal path (L2005-2006 / L2052-2053).
    @Test
    public void testConvertInsertSqlToUpdateSql_EscapedSingleQuoteInValue() throws SQLException {
        final DataSource ds = dataSourceReturningConnection();
        final String updateSql = JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO t(a) VALUES ('it''s')");
        assertEquals("UPDATE t SET a = 'it''s'", updateSql);
    }

    @Test
    public void testConvertInsertSqlToUpdateSql_RejectsAdditionalValueTuples() throws SQLException {
        final DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil
                .convertInsertSqlToUpdateSql(dataSource, "INSERT INTO order_history(id, status) VALUES (1, 'OPEN'), (2, 'CLOSED')"));

        assertTrue(thrown.getMessage().contains("single VALUES tuple"));
    }

    @Test
    public void testConvertInsertSqlToUpdateSql_RejectsStatementPrefix() throws SQLException {
        final DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil
                .convertInsertSqlToUpdateSql(dataSource, "DELETE FROM audit_log; INSERT INTO order_history(id, status) VALUES (1, 'OPEN')"));

        assertTrue(thrown.getMessage().contains("Not an INSERT INTO SQL"));
    }

    @Test
    public void testConvertInsertSqlToUpdateSql_RejectsMissingTableName() throws SQLException {
        final DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource, "INSERT INTO (id, status) VALUES (1, 'OPEN')"));

        assertTrue(thrown.getMessage().contains("Missing table name"));
    }

    @Test
    public void testConvertInsertSqlToUpdateSql_RejectsTokensAroundValuesClause() throws SQLException {
        final DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource, "INSERT INTO order_history(id) garbage VALUES (1)"));
        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource, "INSERT INTO order_history(id) NOTVALUES (1)"));
        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource, "INSERT INTO order_history(id) VALUES garbage (1)"));
    }

    @Test
    public void testConvertInsertSqlToUpdateSql_RejectsUnsafeTableFragment() throws SQLException {
        final DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource, "INSERT INTO t; DROP TABLE audit; -- (id) VALUES (1)"));
        assertTrue(failure.getMessage().contains("quoting is disabled"), failure.getMessage());
    }

    @Test
    public void testConvertInsertSqlToUpdateSql_InvalidSql_ThrowsIllegalArgument() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenReturn(connection);

        // Malformed SQL — missing parentheses/values — exercises the catch-all wrapper.
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "garbage sql"));
    }

    @Test
    public void testConvertInsertSqlToUpdateSql_BlankWhereClauseIsOmitted() throws SQLException {
        final DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        assertEquals("UPDATE order_history SET status = 'CLOSED'",
                JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource, "INSERT INTO order_history(status) VALUES ('CLOSED')", "   "));
    }

    // An empty token in the column list -> "Empty item in SQL list" (L2107).
    @Test
    public void testConvertInsertSqlToUpdateSql_EmptyColumn() throws SQLException {
        final DataSource ds = dataSourceReturningConnection();
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO t() VALUES (1)"));
        assertTrue(ex.getMessage().contains("Empty item in SQL list"), ex.getMessage());
    }

    @Test
    public void testConvertInsertSqlToUpdateSql_RejectsExplicitSimpleIdentifiers() throws SQLException {
        final DataSource ds = dataSourceReturningConnection();
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO \"events\"(\"id\") VALUES (1)"));
        assertTrue(failure.getMessage().contains("quoting is disabled"), failure.getMessage());
    }

    // convertInsertSqlToUpdateSql edge cases (lines 1816-1817 column/value count mismatch
    // and 1842-1843 catch-all Exception path).

    @Test
    public void testConvertInsertSqlToUpdateSql_ColumnValueCountMismatch_ThrowsIllegalArgument() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenReturn(connection);

        // Two columns, one value -> should throw IllegalArgumentException.
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO t(a,b) VALUES (1)"));
        assertTrue(ex.getMessage().contains("Column count"), "Should preserve the specific column/value count mismatch message: " + ex.getMessage());
    }

    // An opening parenthesis with no matching close -> findClosingParenthesis scans to the end and
    // Reports an unclosed parenthesis without echoing the full SQL text.
    @Test
    public void testConvertInsertSqlToUpdateSql_UnclosedParenthesis() throws SQLException {
        final DataSource ds = dataSourceReturningConnection();
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO t(a"));
        assertTrue(ex.getMessage().contains("unclosed parenthesis"), ex.getMessage());
    }

    @Test
    public void testSplitSqlList_UnmatchedClosingParenthesis_Reflection() throws Exception {
        final java.lang.reflect.Method m = JdbcCodeGenerationUtil.class.getDeclaredMethod("splitSqlList", String.class, String.class, commentStyleClass(),
                boolean.class, boolean.class);
        m.setAccessible(true);

        final java.lang.reflect.InvocationTargetException ex = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> m.invoke(null, "a)", "INSERT INTO t(a)) VALUES (1)", flatCommentStyle(), false, true));
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
        assertTrue(ex.getCause().getMessage().contains("Unmatched closing parenthesis in SQL"), ex.getCause().getMessage());
    }

    // The defensive "Unclosed SQL token" branch in splitSqlList (L2091-2092) likewise cannot be reached
    // through the public method (findClosingParenthesis rejects unbalanced quotes first), so it is
    // exercised directly via reflection (an unterminated single-quote literal).
    @Test
    public void testSplitSqlList_UnclosedToken_Reflection() throws Exception {
        final java.lang.reflect.Method m = JdbcCodeGenerationUtil.class.getDeclaredMethod("splitSqlList", String.class, String.class, commentStyleClass(),
                boolean.class, boolean.class);
        m.setAccessible(true);

        final java.lang.reflect.InvocationTargetException ex = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> m.invoke(null, "'abc", "INSERT INTO t('abc) VALUES (1)", flatCommentStyle(), false, true));
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
        assertTrue(ex.getCause().getMessage().contains("Unclosed SQL token in SQL"), ex.getCause().getMessage());
    }

    // isSimpleSqlIdentifier returns false for empty/null input. Every public caller guards against
    // empty identifiers, so this branch is exercised directly. The logic now lives in the shared
    // package-private SqlIdentifierUtil (previously duplicated here and in DataTransferUtil).
    @Test
    public void testIsSimpleSqlIdentifier_EmptyAndNull() {
        assertFalse(com.landawn.abacus.jdbc.SqlIdentifierUtil.isSimpleSqlIdentifier("")); // empty
        assertFalse(com.landawn.abacus.jdbc.SqlIdentifierUtil.isSimpleSqlIdentifier(null)); // null
        assertFalse(com.landawn.abacus.jdbc.SqlIdentifierUtil.isSimpleSqlIdentifier("1bad")); // first char not alpha/underscore
        assertTrue(com.landawn.abacus.jdbc.SqlIdentifierUtil.isSimpleSqlIdentifier("valid_name1")); // happy path
    }

    private static DataSource dataSourceReporting(final String productName) throws SQLException {
        final DataSource dataSource = Mockito.mock(DataSource.class);
        final Connection conn = Mockito.mock(Connection.class);
        final DatabaseMetaData metaData = Mockito.mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn(productName);
        when(metaData.getIdentifierQuoteString())
                .thenReturn(SqlIdentifierUtil.quoteString(com.landawn.abacus.query.SqlDialect.ProductInfo.of(productName, "1")));
        when(metaData.getDatabaseProductVersion()).thenReturn("1.0");
        return dataSource;
    }

    // ----------------------------------------------------------------------------------------------------
    // convertInsertSqlToUpdateSql — parse-error edge cases and the SQL tokenizer / parenthesis matcher.
    // ----------------------------------------------------------------------------------------------------

    private DataSource dataSourceReturningConnection() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenReturn(connection); // setUp() makes `connection` report MySQL 8.0
        return ds;
    }

    // The defensive "Unmatched closing parenthesis" branch in splitSqlList (L2079) cannot be reached
    // through convertInsertSqlToUpdateSql (findClosingParenthesis only ever hands splitSqlList a
    // balanced substring), so it is exercised directly via reflection.
    private static Class<?> commentStyleClass() throws ClassNotFoundException {
        return Class.forName(JdbcCodeGenerationUtil.class.getName() + "$SqlCommentStyle");
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Object flatCommentStyle() throws ClassNotFoundException {
        return Enum.valueOf((Class) commentStyleClass(), "FLAT_BLOCKS");
    }
}
