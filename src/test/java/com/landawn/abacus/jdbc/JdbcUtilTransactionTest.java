package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.query.SqlOperation;

class JdbcUtilTransactionTest extends JdbcUtilTestSupport {

    // Regression: getSqlOperation's fallback loop used SqlOperation.name() (which uses Java enum
    // constant naming, e.g., "BEGIN_TRANSACTION" with underscore) instead of sqlToken() ("BEGIN
    // TRANSACTION" with space), so multi-word operations never matched. It also lacked a word
    // boundary so "CREATEX foo" was misclassified as CREATE.
    @Test
    public void testGetSqlOperation_BeginTransactionMatchesSpaceVariant() throws Exception {
        final java.lang.reflect.Method m = JdbcUtil.class.getDeclaredMethod("getSqlOperation", String.class);
        m.setAccessible(true);

        Object op = m.invoke(null, "BEGIN TRANSACTION");
        assertEquals(com.landawn.abacus.query.SqlOperation.BEGIN_TRANSACTION, op);

        op = m.invoke(null, "begin transaction read only");
        assertEquals(com.landawn.abacus.query.SqlOperation.BEGIN_TRANSACTION, op);
    }

    // A for-update-only transaction is skipped for plain SELECTs, but a SELECT embedding a data-modifying
    // statement (PostgreSQL data-modifying CTE, DB2/H2 data-change delta table) writes data and must join it.
    @Test
    public void testGetTransaction_ForUpdateOnlyJoinsSelectWithDataModifyingSection() throws SQLException {
        final SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource, IsolationLevel.READ_COMMITTED, true);

        try {
            final SqlTransaction.CreatedBy by = SqlTransaction.CreatedBy.JDBC_UTIL;

            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT * FROM account", by));
            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT coalesce(update_time, created) FROM account WHERE note <> '(delete x'", by));
            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT id FROM account WHERE id IN (SELECT id FROM t) /* (update */", by));
            // Keywords inside quoted identifiers, bracket identifiers, dollar-quoted text and line comments, and identifiers
            // that merely start with a keyword, are not data-modifying sections.
            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT \"(update\", [(delete], $$(insert$$, $tag$(merge$tag$ FROM account", by));
            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT id -- (update\nFROM account WHERE (update_flag = 1 OR (insert_count > $1))", by));

            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "WITH u AS (UPDATE account SET active = FALSE RETURNING id) SELECT id FROM u", by));
            assertSame(tran, JdbcUtil.getTransaction(mockDataSource,
                    "WITH d AS MATERIALIZED ( /* purge */ delete FROM account RETURNING id) SELECT count(*) FROM d", by));
            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "SELECT id FROM FINAL TABLE (INSERT INTO account (name) VALUES ('x'))", by));
            assertSame(tran, JdbcUtil.getTransaction(mockDataSource,
                    "SELECT id FROM FINAL TABLE (MERGE INTO account a USING (VALUES (1)) s (id) ON a.id = s.id WHEN NOT MATCHED THEN INSERT (id) VALUES (s.id))",
                    by));
            assertSame(tran, JdbcUtil.getTransaction(mockDataSource,
                    "SELECT * FROM ((SELECT id FROM a) UNION ALL (SELECT id FROM OLD TABLE ( -- archive\n  Delete FROM account))) t", by));
            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "UPDATE account SET active = TRUE", by));
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    // A SELECT with an INTO clause writes too: PostgreSQL/SQL Server "SELECT ... INTO new_table" creates and fills a table
    // (a SQL Server #temp table would land on the separate connection), MySQL "SELECT ... INTO @var" sets a session variable.
    @Test
    public void testGetTransaction_ForUpdateOnlyJoinsSelectInto() throws SQLException {
        final SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource, IsolationLevel.READ_COMMITTED, true);

        try {
            final SqlTransaction.CreatedBy by = SqlTransaction.CreatedBy.JDBC_UTIL;

            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "SELECT * INTO account_backup FROM account", by));
            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "select id into #recent from account where created > ?", by));
            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "SELECT max(id) FROM account INTO @max_id", by));

            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT into_date, 'copy into' AS \"into\" FROM account /* into */", by));
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    // BUG FIX: a parameter, variable or qualified name spelled like a keyword is a name, not an INTO clause or a data-modifying
    // section. prepareNamedQuery passes the original named SQL, so ":into" used to make an ordinary SELECT join the
    // for-update-only transaction (and read its uncommitted writes).
    @Test
    public void testGetTransaction_ForUpdateOnlyIgnoresKeywordSpelledParameterAndVariableNames() throws SQLException {
        final SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource, IsolationLevel.READ_COMMITTED, true);

        try {
            final SqlTransaction.CreatedBy by = SqlTransaction.CreatedBy.JDBC_UTIL;

            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT * FROM account WHERE id = :into", by));
            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT * FROM account WHERE id = #{into} OR id = ${into}", by));
            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT @into AS v, a.into_flag, a.\"into\" FROM account a", by));
            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT a.into FROM account a WHERE a.id = ?::into", by));
            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT * FROM account WHERE id IN (:update, :delete, #{insert}, ${merge})", by));

            // Guards (pass before and after the fix): real INTO clauses and data-modifying sections still join.
            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "SELECT id INTO :into_table FROM account WHERE id = :into", by));
            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "SELECT max(id) FROM account WHERE id > :id INTO @max_id", by));
            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "WITH u AS (UPDATE account SET active = :into RETURNING id) SELECT id FROM u", by));
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    // BUG FIX: a qualifier '.' may be separated from the qualified word by whitespace or comments ("a. into" is the column
    // "into" of "a"), so that word is a name too; only the character right before the word used to be checked. A '.' after a
    // number is a decimal point, though: "SELECT 1.INTO @x" (like "SELECT 1. INTO @x") has an INTO clause.
    @Test
    public void testGetTransaction_ForUpdateOnlyRecognizesQualifiedNamesAcrossWhitespaceAndComments() throws SQLException {
        final SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource, IsolationLevel.READ_COMMITTED, true);

        try {
            final SqlTransaction.CreatedBy by = SqlTransaction.CreatedBy.JDBC_UTIL;

            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT a. into FROM account a", by));
            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT a . /* column */ into FROM account a", by));
            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT a.\n  -- the column\n  INTO FROM account a", by));
            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT \"t\". into, [t] .into, `t` . into FROM account t", by));
            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT (r). into FROM account r", by));

            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "SELECT 1.INTO @x", by));
            // Guards (pass before and after the fix).
            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "SELECT 1. INTO @x", by));
            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "SELECT a.id, 2. /* two */ INTO @x, @y FROM account a", by));
            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "SELECT a.id INTO @x FROM account a", by));
            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "SELECT a.id FROM FINAL TABLE (UPDATE account a SET a.active = TRUE)", by));
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    // BUG FIX: MySQL identifiers may start with a digit ("1a"), so whether a '.' is a decimal point must be decided from the
    // whole preceding token (all digits), not its first character: "1a.into" is the column "into" of the alias "1a".
    @Test
    public void testGetTransaction_ForUpdateOnlyDigitPrefixedQualifierIsAName() throws SQLException {
        final SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource, IsolationLevel.READ_COMMITTED, true);

        try {
            final SqlTransaction.CreatedBy by = SqlTransaction.CreatedBy.JDBC_UTIL;

            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT 1a.into FROM account 1a", by));
            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT 1a. into FROM account 1a", by));
            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT 1a /* alias */ . into FROM account 1a", by));
            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT 2b_x.into, 9e.into FROM account 2b_x, account 9e", by));
            // Guards (pass before and after the fix): a quoted digit-prefixed qualifier is a name, and an all-digit token
            // before '.' is a number, so INTO there is a real clause.
            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT `1a`.into, `1a`. into FROM account `1a`", by));
            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "SELECT 1.INTO @x", by));
            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "SELECT 12. INTO @x", by));
            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "SELECT 3. /* three */ INTO @x", by));
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    // BUG FIX: INSERT(str, pos, len, newstr) is a string function (MySQL, H2), not an embedded INSERT statement: a
    // parenthesized call made an ordinary SELECT join the for-update-only transaction.
    @Test
    public void testGetTransaction_ForUpdateOnlyInsertFunctionCallIsNotAWrite() throws SQLException {
        final SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource, IsolationLevel.READ_COMMITTED, true);

        try {
            final SqlTransaction.CreatedBy by = SqlTransaction.CreatedBy.JDBC_UTIL;

            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT (INSERT(name, 1, 1, 'X')) FROM account", by));
            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT ( insert /* fn */ (name, 1, 1, 'X')) FROM account", by));
            assertNull(
                    JdbcUtil.getTransaction(mockDataSource, "SELECT concat((INSERT(name, 1, 1, 'X')), (lower(INSERT\n (name, 2, 0, 'y')))) FROM account", by));
            // Guards (pass before and after the fix): embedded INSERT statements still join, and so does UPDATE followed by
            // '(' (an updatable subquery on Oracle/DB2).
            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "SELECT id FROM FINAL TABLE (INSERT INTO account (name) VALUES ('x'))", by));
            assertSame(tran,
                    JdbcUtil.getTransaction(mockDataSource, "WITH i AS (insert /* c */ INTO account (name) VALUES ('x') RETURNING id) SELECT id FROM i", by));
            assertSame(tran,
                    JdbcUtil.getTransaction(mockDataSource, "SELECT * FROM FINAL TABLE (UPDATE (SELECT * FROM account WHERE id = 1) SET name = 'x')", by));
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    // Cost: the database dialect is only read for SQL whose scan can depend on it, and then once per transaction (the
    // connection is fixed for the transaction's lifetime), also when it can't be read; a new transaction reads it again.
    @Test
    public void testGetTransaction_ForUpdateOnlyReadsTheDialectOncePerTransaction() throws SQLException {
        final SqlTransaction.CreatedBy by = SqlTransaction.CreatedBy.JDBC_UTIL;
        SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource, IsolationLevel.READ_COMMITTED, true);

        try {
            org.mockito.Mockito.clearInvocations(mockDatabaseMetaData);
            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT name, updated_at FROM account WHERE id = ?", by));
            verify(mockDatabaseMetaData, never()).getDatabaseProductName();

            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT name, 1_000 /* note */ FROM account # x -- y", by));

            // setUp reports MySQL (version "8", unparseable, so executable comments count as run).
            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT name FROM account -- copied into archive", by));
            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "SELECT id FROM account WHERE id = 1 /*!50100 INTO @x */", by));
            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT updated_at FROM account # into", by));
            verify(mockDatabaseMetaData, org.mockito.Mockito.times(1)).getDatabaseProductName();
            verify(mockDatabaseMetaData, org.mockito.Mockito.times(1)).getDatabaseProductVersion();
        } finally {
            tran.rollbackIfNotCommitted();
        }

        tran = JdbcUtil.beginTransaction(mockDataSource, IsolationLevel.READ_COMMITTED, true);

        try {
            org.mockito.Mockito.clearInvocations(mockDatabaseMetaData);
            org.mockito.Mockito.doThrow(new SQLException("no metadata")).when(mockDatabaseMetaData).getDatabaseProductName();
            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "SELECT 1 # don't parse this\nINTO @x", by));
            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT name FROM account /* plain */ -- into nothing", by));
            verify(mockDatabaseMetaData, org.mockito.Mockito.times(1)).getDatabaseProductName();
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    // BUG FIX: on MySQL/MariaDB '#' starts a line comment, so words in it ("# copied into archive") are not an INTO clause.
    // Elsewhere '#' is part of a name (SQL Server #temp tables) or an operator (PostgreSQL #, #>), so it is not skipped there:
    // treating "#t.id" as a comment would hide the INTO of "SELECT #t.id INTO #t2 FROM #t" and let that write escape.
    @Test
    public void testGetTransaction_ForUpdateOnlyHashLineCommentsOnlyOnMySqlFamily() throws SQLException {

        // The product name is only read for SQL that contains '#'.
        org.mockito.Mockito.clearInvocations(mockDatabaseMetaData);
        assertFalse(joinsNewForUpdateOnlyTransaction("SELECT name FROM account"));
        verify(mockDatabaseMetaData, never()).getDatabaseProductName();

        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        assertFalse(joinsNewForUpdateOnlyTransaction("SELECT name FROM account # copied into archive"));
        assertFalse(joinsNewForUpdateOnlyTransaction("SELECT name FROM account #into\nWHERE id = 1"));
        assertFalse(joinsNewForUpdateOnlyTransaction("SELECT (name) FROM account WHERE id IN ( # (insert\n 1)"));
        assertFalse(joinsNewForUpdateOnlyTransaction("SELECT (INSERT # function\n (name, 1, 1, 'X')) FROM account"));
        // A real INTO after a '#' comment still joins, and a #{name} placeholder is not a comment.
        assertTrue(joinsNewForUpdateOnlyTransaction("SELECT name FROM account # note\nINTO OUTFILE '/tmp/names.csv'"));
        assertTrue(joinsNewForUpdateOnlyTransaction("SELECT id FROM account WHERE id = #{id} INTO @x"));

        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MariaDB");
        assertFalse(joinsNewForUpdateOnlyTransaction("SELECT name FROM account # copied into archive"));

        // Guards (pass before and after the fix): elsewhere '#' is not a comment.
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("Microsoft SQL Server");
        assertTrue(joinsNewForUpdateOnlyTransaction("SELECT #t.id INTO #t2 FROM #t"));
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        assertTrue(joinsNewForUpdateOnlyTransaction("SELECT a # b INTO t2 FROM t"));
        assertFalse(joinsNewForUpdateOnlyTransaction("SELECT data #> '{a,b}' FROM t"));
        // With an unreadable product name, a write seen under either rule set joins.
        when(mockDatabaseMetaData.getDatabaseProductName()).thenThrow(new SQLException("metadata unavailable"));
        assertTrue(joinsNewForUpdateOnlyTransaction("SELECT #t.id INTO #t2 FROM #t"));
    }

    // BUG FIX: when the product name can't be read, scanning with the standard rules alone could miss a write: on MySQL the
    // apostrophe in "# don't parse this" belongs to a comment, but read as code it opened a string that hid the real INTO
    // on the next line. A write seen under either the MySQL or the standard rules now joins; at worst a plain read joins.
    @Test
    public void testGetTransaction_ForUpdateOnlyUnknownDialectJoinsWhenEitherRuleSetSeesAWrite() throws SQLException {
        final SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource, IsolationLevel.READ_COMMITTED, true);

        try {
            final SqlTransaction.CreatedBy by = SqlTransaction.CreatedBy.JDBC_UTIL;
            when(mockDatabaseMetaData.getDatabaseProductName()).thenThrow(new SQLException("metadata unavailable"));

            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "SELECT 1 # don't parse this\nINTO @x", by));
            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "SELECT 5--3 INTO @x", by));
            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "SELECT 1 /*! INTO @x */", by));
            // Guards (pass before and after the fix): standard-rule writes still join, and a read whose comments hide nothing
            // under either rule set still runs separately.
            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "SELECT #t.id INTO #t2 FROM #t", by));
            assertNull(JdbcUtil.getTransaction(mockDataSource, "SELECT name FROM account /* plain comment */ -- trailing", by));
            // The accepted cost: a plain read whose comment mentions INTO joins, because '#' might not be a comment.
            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "SELECT name FROM account # copied into archive", by));
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    // BUG FIX: PostgreSQL 16 accepts digit groups separated by underscores ("1_000"), so the '.' after such a number is a
    // decimal point and "SELECT 1_000. INTO new_table" creates a table; the digits-only check read "1_000" as a qualifier. On
    // MySQL/MariaDB "1_000" is an identifier, so there "1_000.into" stays a qualified column.
    @Test
    public void testGetTransaction_ForUpdateOnlyUnderscoreSeparatedNumberBeforeDecimalPoint() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL"); // setUp's default is MySQL

        assertTrue(joinsNewForUpdateOnlyTransaction("SELECT 1_000. INTO new_table"));
        assertTrue(joinsNewForUpdateOnlyTransaction("SELECT 1_000_000.INTO new_table"));
        // Guards (pass before and after the fix): not numbers in any dialect, so still qualifiers, and an INTO after a
        // fractional part is a keyword anyway.
        assertFalse(joinsNewForUpdateOnlyTransaction("SELECT 1__0.into, 1_.into, 1a_2.into, _1.into FROM account"));
        assertTrue(joinsNewForUpdateOnlyTransaction("SELECT 1_000_000.5 INTO new_table"));

        final String qualifiedByUnderscoreName = "SELECT 1_000.into FROM account 1_000 -- alias";
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        assertFalse(joinsNewForUpdateOnlyTransaction(qualifiedByUnderscoreName));
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        assertTrue(joinsNewForUpdateOnlyTransaction(qualifiedByUnderscoreName));
    }

    // BUG FIX: MySQL/MariaDB comment rules differ from the standard ones, and a comment hid a real INTO clause, letting a
    // write run outside the for-update-only transaction: "--" starts a comment only before whitespace or a control character
    // ("5--3" is arithmetic), block comments don't nest, and the content of an executable "/*! ... */" comment is run as
    // code. Other databases keep the standard rules.
    @Test
    public void testGetTransaction_ForUpdateOnlyMySqlDashFlatAndExecutableCommentRules() throws SQLException {
        final String dashArithmetic = "SELECT 5--3 INTO @x";
        final String nestedLookingComment = "SELECT id FROM account WHERE id = 1 /* outer /* inner */ INTO @x";
        final String executableInto = "SELECT id FROM account WHERE id = 1 /*!50100 INTO @x */";
        final String mariaDbExecutableInto = "SELECT id FROM account WHERE id = 1 /*M!100100 INTO @x */";

        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        assertTrue(joinsNewForUpdateOnlyTransaction(dashArithmetic));
        assertTrue(joinsNewForUpdateOnlyTransaction(nestedLookingComment));
        assertTrue(joinsNewForUpdateOnlyTransaction(executableInto));
        assertTrue(joinsNewForUpdateOnlyTransaction("SELECT id FROM account WHERE id = 1 /*! INTO @x */"));
        // Ordinary MySQL comments still hide their words; an executable comment's content is code, but its end is not.
        assertFalse(joinsNewForUpdateOnlyTransaction("SELECT name FROM account -- copied into archive"));
        assertFalse(joinsNewForUpdateOnlyTransaction("SELECT name FROM account --\tcopied into archive\nWHERE id = 1"));
        assertFalse(joinsNewForUpdateOnlyTransaction("SELECT name FROM account --"));
        assertFalse(joinsNewForUpdateOnlyTransaction("SELECT name /*!40000 , id */ FROM account /* copied into archive */"));
        assertFalse(joinsNewForUpdateOnlyTransaction("SELECT (INSERT /*! (name, 1, 1, 'X') */) FROM account"));
        assertFalse(joinsNewForUpdateOnlyTransaction("SELECT (/*+ BKA(t) */ INSERT(name, 1, 1, 'X')) FROM account"));

        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MariaDB");
        assertTrue(joinsNewForUpdateOnlyTransaction(mariaDbExecutableInto));
        assertTrue(joinsNewForUpdateOnlyTransaction(dashArithmetic));

        // Guards (pass before and after the fix): the standard rules elsewhere, where "--" always starts a comment,
        // block comments nest and "/*! ... */" is an ordinary comment.
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        assertFalse(joinsNewForUpdateOnlyTransaction(dashArithmetic));
        assertFalse(joinsNewForUpdateOnlyTransaction(nestedLookingComment));
        assertFalse(joinsNewForUpdateOnlyTransaction(executableInto));
        assertFalse(joinsNewForUpdateOnlyTransaction(mariaDbExecutableInto));
    }

    // BUG FIX: an executable comment the server ignores is a plain comment. MySQL ignores MariaDB-only "/*M! ... */", and
    // "/*!NNNNN ... */" runs only on servers of at least version NNNNN; scanning ignored content made plain reads join. Only a
    // certain "ignored" counts: an unknown vendor or an unparseable version keeps the content as code, so no write is missed.
    @Test
    public void testGetTransaction_ForUpdateOnlyExecutableCommentsCountOnlyWhenTheServerRunsThem() throws SQLException {
        final String mariaDbOnly = "SELECT name FROM account /*M! INTO @x */";
        final String futureVersion = "SELECT name FROM account /*!999999 INTO @x */";

        org.mockito.Mockito.doReturn("MySQL").when(mockDatabaseMetaData).getDatabaseProductName();
        org.mockito.Mockito.doReturn("8.0.33").when(mockDatabaseMetaData).getDatabaseProductVersion();
        assertFalse(joinsNewForUpdateOnlyTransaction(mariaDbOnly));
        assertFalse(joinsNewForUpdateOnlyTransaction(futureVersion));
        assertFalse(joinsNewForUpdateOnlyTransaction("SELECT name FROM account /*!80034 INTO @x */ WHERE id = 1"));
        assertTrue(joinsNewForUpdateOnlyTransaction("SELECT name FROM account /*!80033 INTO @x */"));
        assertTrue(joinsNewForUpdateOnlyTransaction("SELECT name FROM account /*!50110 INTO @x */"));
        assertTrue(joinsNewForUpdateOnlyTransaction("SELECT name FROM account /*! INTO @x */"));

        org.mockito.Mockito.doReturn("5.5.5-log").when(mockDatabaseMetaData).getDatabaseProductVersion(); // MySQL 5.5.5
        assertFalse(joinsNewForUpdateOnlyTransaction("SELECT name FROM account /*!50506 INTO @x */"));
        assertTrue(joinsNewForUpdateOnlyTransaction("SELECT name FROM account /*!50505 INTO @x */"));

        // MariaDB 10.6.12, reported as such or (through the MySQL driver) as "MySQL" with the "5.5.5-" compatibility prefix.
        for (final String[] mariaDb : new String[][] { { "MariaDB", "10.6.12-MariaDB-1:10.6.12+maria~ubu2004" }, { "MySQL", "5.5.5-10.6.12-MariaDB" } }) {
            org.mockito.Mockito.doReturn(mariaDb[0]).when(mockDatabaseMetaData).getDatabaseProductName();
            org.mockito.Mockito.doReturn(mariaDb[1]).when(mockDatabaseMetaData).getDatabaseProductVersion();
            assertTrue(joinsNewForUpdateOnlyTransaction(mariaDbOnly), mariaDb[1]);
            assertTrue(joinsNewForUpdateOnlyTransaction("SELECT name FROM account /*M!100100 INTO @x */"), mariaDb[1]);
            assertFalse(joinsNewForUpdateOnlyTransaction("SELECT name FROM account /*M!100700 INTO @x */"), mariaDb[1]);
            assertFalse(joinsNewForUpdateOnlyTransaction(futureVersion), mariaDb[1]);
            // MariaDB ignores MySQL-style comments gated on 5.7+ (50700..99999), but runs its own "/*M!" ones in that range.
            assertTrue(joinsNewForUpdateOnlyTransaction("SELECT name FROM account /*!50699 INTO @x */"), mariaDb[1]);
            assertFalse(joinsNewForUpdateOnlyTransaction("SELECT name FROM account /*!50700 INTO @x */"), mariaDb[1]);
            assertFalse(joinsNewForUpdateOnlyTransaction("SELECT name FROM account /*!80000 INTO @x */"), mariaDb[1]);
            assertFalse(joinsNewForUpdateOnlyTransaction("SELECT name FROM account /*!99999 INTO @x */"), mariaDb[1]);
            assertTrue(joinsNewForUpdateOnlyTransaction("SELECT name FROM account /*!100000 INTO @x */"), mariaDb[1]);
            assertTrue(joinsNewForUpdateOnlyTransaction("SELECT name FROM account /*M!50700 INTO @x */"), mariaDb[1]);
            assertTrue(joinsNewForUpdateOnlyTransaction("SELECT name FROM account /*M!99999 INTO @x */"), mariaDb[1]);
        }

        // MySQL runs its own 5.7+ comments (a guard: passes before and after the MariaDB fix).
        org.mockito.Mockito.doReturn("MySQL").when(mockDatabaseMetaData).getDatabaseProductName();
        org.mockito.Mockito.doReturn("8.0.33").when(mockDatabaseMetaData).getDatabaseProductVersion();
        assertTrue(joinsNewForUpdateOnlyTransaction("SELECT name FROM account /*!50700 INTO @x */"));
        assertTrue(joinsNewForUpdateOnlyTransaction("SELECT name FROM account /*!80000 INTO @x */"));

        // Guards (pass before and after the fix): an unknown vendor or version keeps executable content as code.
        org.mockito.Mockito.doReturn("MySQL").when(mockDatabaseMetaData).getDatabaseProductName();
        org.mockito.Mockito.doReturn("8.0").when(mockDatabaseMetaData).getDatabaseProductVersion(); // a missing patch counts as 99
        assertTrue(joinsNewForUpdateOnlyTransaction("SELECT name FROM account /*!80050 INTO @x */"));
        org.mockito.Mockito.doReturn("unknown").when(mockDatabaseMetaData).getDatabaseProductVersion();
        assertTrue(joinsNewForUpdateOnlyTransaction(futureVersion));
        org.mockito.Mockito.doThrow(new SQLException("no version")).when(mockDatabaseMetaData).getDatabaseProductVersion();
        assertTrue(joinsNewForUpdateOnlyTransaction(mariaDbOnly));
        assertTrue(joinsNewForUpdateOnlyTransaction(futureVersion));
        // "MySQL" without a readable version might be MariaDB behind the MySQL driver: its 5.7+ comments still count as run.
        assertTrue(joinsNewForUpdateOnlyTransaction("SELECT name FROM account /*!80000 INTO @x */"));
        // A server named MariaDB is known to be MariaDB even without a version, so the 50700..99999 range is ignored.
        org.mockito.Mockito.doReturn("MariaDB").when(mockDatabaseMetaData).getDatabaseProductName();
        assertFalse(joinsNewForUpdateOnlyTransaction("SELECT name FROM account /*!80000 INTO @x */"));
        assertTrue(joinsNewForUpdateOnlyTransaction("SELECT name FROM account /*M!80000 INTO @x */"));
        org.mockito.Mockito.doThrow(new SQLException("no metadata")).when(mockDatabaseMetaData).getDatabaseProductName();
        assertTrue(joinsNewForUpdateOnlyTransaction(mariaDbOnly));
        assertTrue(joinsNewForUpdateOnlyTransaction(futureVersion));
        assertTrue(joinsNewForUpdateOnlyTransaction("SELECT name FROM account /*!80000 INTO @x */"));
    }

    // BUG FIX: whether "1_000" is a number depends on the database also without comments. The product used to be looked up
    // only for SQL with comment-like text, so on MySQL the comment-free "SELECT 1_000.into FROM account 1_000" read "1_000"
    // as a PostgreSQL number and joined; a digit followed by '_' now triggers the lookup too.
    @Test
    public void testGetTransaction_ForUpdateOnlyUnderscoreDigitsLookUpTheDialectWithoutComments() throws SQLException {
        final String sql = "SELECT 1_000.into FROM account 1_000";

        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        assertFalse(joinsNewForUpdateOnlyTransaction(sql));
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        assertTrue(joinsNewForUpdateOnlyTransaction(sql));

        // Guard (passes before and after the fix): other SQL still doesn't read the product name.
        org.mockito.Mockito.clearInvocations(mockDatabaseMetaData);
        assertFalse(joinsNewForUpdateOnlyTransaction("SELECT name_1, a_b, 10 FROM account WHERE x = 'y'"));
        verify(mockDatabaseMetaData, never()).getDatabaseProductName();
    }

    // Guard (passes before and after the fix): only a for-update-only transaction is ever skipped, and only for a SELECT.
    @Test
    public void testGetTransaction_NoTransactionOrNotForUpdateOnly() throws SQLException {
        assertNull(JdbcUtil.getTransaction(mockDataSource, "WITH u AS (UPDATE account SET active = FALSE RETURNING id) SELECT id FROM u",
                SqlTransaction.CreatedBy.JDBC_UTIL));

        final SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource, IsolationLevel.READ_COMMITTED, false);

        try {
            assertSame(tran, JdbcUtil.getTransaction(mockDataSource, "SELECT * FROM account", SqlTransaction.CreatedBy.JDBC_UTIL));
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    @Test
    public void testTableExists_FallbackProbeSuccessInTransaction_ReleasesSavepoint() throws SQLException {
        final java.sql.Savepoint savepoint = mock(java.sql.Savepoint.class);
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockConnection.setSavepoint()).thenReturn(savepoint);

        assertTrue(JdbcUtil.tableExists(mockConnection, "present_tbl"));

        verify(mockConnection).prepareStatement("SELECT 1 FROM present_tbl WHERE 1 > 2");
        verify(mockConnection).releaseSavepoint(savepoint);
        verify(mockConnection, never()).rollback(savepoint);
    }

    // A driver without savepoint support: the probe still runs (without a savepoint), as before the fix.
    @Test
    public void testTableExists_FallbackProbeInTransaction_SavepointsUnsupported_ProbesWithoutSavepoint() throws SQLException {
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockConnection.setSavepoint()).thenThrow(new java.sql.SQLFeatureNotSupportedException("savepoints"));
        when(mockConnection.prepareStatement("SELECT 1 FROM missing_tbl WHERE 1 > 2"))
                .thenThrow(new SQLException("relation \"missing_tbl\" does not exist", "42P01"));

        assertFalse(JdbcUtil.tableExists(mockConnection, "missing_tbl"));
        assertTrue(JdbcUtil.tableExists(mockConnection, "present_tbl"));

        verify(mockConnection, never()).rollback(org.mockito.ArgumentMatchers.any(java.sql.Savepoint.class));
        verify(mockConnection, never()).releaseSavepoint(org.mockito.ArgumentMatchers.any(java.sql.Savepoint.class));
    }

    // Databases that roll back only the failed statement (MySQL, Oracle, SQL Server, ...) need no savepoint: the
    // "table not found" probe failure leaves the caller's transaction usable.
    @Test
    public void testTableExists_FallbackProbeInTransaction_NonPostgreSQL_NoSavepoint() throws SQLException {
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockConnection.prepareStatement("SELECT 1 FROM missing_tbl WHERE 1 > 2"))
                .thenThrow(new SQLException("Table 'missing_tbl' doesn't exist", "42S02"));

        for (final String productName : new String[] { "MySQL", "Oracle", "Microsoft SQL Server" }) {
            when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn(productName);

            assertFalse(JdbcUtil.tableExists(mockConnection, "missing_tbl"), productName);
            assertTrue(JdbcUtil.tableExists(mockConnection, "present_tbl"), productName);
        }

        verify(mockConnection, never()).setSavepoint();
        verify(mockConnection, never()).rollback(org.mockito.ArgumentMatchers.any(java.sql.Savepoint.class));
        verify(mockConnection, never()).releaseSavepoint(org.mockito.ArgumentMatchers.any(java.sql.Savepoint.class));
    }

    // BUG FIX: in a transaction (auto-commit off) the tableExists fallback probe must run under a savepoint and roll
    // back to it when the probe fails. On PostgreSQL an expected "relation does not exist" error otherwise aborts the
    // caller's whole transaction ("current transaction is aborted") although tableExists just returns false.
    @Test
    public void testTableExists_FallbackProbeFailureInTransaction_RollsBackToSavepoint() throws SQLException {
        final java.sql.Savepoint savepoint = mock(java.sql.Savepoint.class);
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockConnection.setSavepoint()).thenReturn(savepoint);
        when(mockConnection.prepareStatement("SELECT 1 FROM missing_tbl WHERE 1 > 2"))
                .thenThrow(new SQLException("relation \"missing_tbl\" does not exist", "42P01"));

        assertFalse(JdbcUtil.tableExists(mockConnection, "missing_tbl"));

        verify(mockConnection).rollback(savepoint);
        verify(mockConnection).releaseSavepoint(savepoint);
    }

    // A probe failure that is not "table not found" is still propagated, after the savepoint restored the transaction;
    // a failing rollback is attached to it as suppressed.
    @Test
    public void testTableExists_FallbackProbeOtherErrorInTransaction_RollsBackAndPropagates() throws SQLException {
        final java.sql.Savepoint savepoint = mock(java.sql.Savepoint.class);
        final SQLException probeFailure = new SQLException("could not read block 0 in file", "XX001");
        final SQLException rollbackFailure = new SQLException("rollback failed");
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockConnection.setSavepoint()).thenReturn(savepoint);
        when(mockConnection.prepareStatement("SELECT 1 FROM secret_tbl WHERE 1 > 2")).thenThrow(probeFailure);
        doThrow(rollbackFailure).when(mockConnection).rollback(savepoint);

        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, () -> JdbcUtil.tableExists(mockConnection, "secret_tbl"));

        assertSame(probeFailure, thrown.getCause());
        assertArrayEquals(new Throwable[] { rollbackFailure }, probeFailure.getSuppressed());
        verify(mockConnection).rollback(savepoint);
        verify(mockConnection).releaseSavepoint(savepoint);
    }

    // PostgreSQL-compatible products reporting their own product name are also probed under a savepoint.
    @Test
    public void testTableExists_FallbackProbeFailureInTransaction_Redshift_RollsBackToSavepoint() throws SQLException {
        final java.sql.Savepoint savepoint = mock(java.sql.Savepoint.class);
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("Redshift");
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockConnection.setSavepoint()).thenReturn(savepoint);
        when(mockConnection.prepareStatement("SELECT 1 FROM missing_tbl WHERE 1 > 2"))
                .thenThrow(new SQLException("relation \"missing_tbl\" does not exist", "42P01"));

        assertFalse(JdbcUtil.tableExists(mockConnection, "missing_tbl"));

        verify(mockConnection).rollback(savepoint);
        verify(mockConnection).releaseSavepoint(savepoint);
    }

    @Test
    public void testIsInTransaction() throws SQLException {
        assertFalse(JdbcUtil.isInTransaction(mockDataSource));

        SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource);
        assertTrue(JdbcUtil.isInTransaction(mockDataSource));
        tran.rollbackIfNotCommitted();
    }

    @Test
    @DisplayName("isInTransaction should not propagate LinkageError when Spring is on classpath but mismatched")
    public void testIsInTransactionToleratesLinkageError() throws Exception {
        // The Spring DataSourceUtils.isConnectionTransactional fallback should catch any LinkageError
        // subtype (NoClassDefFoundError, NoSuchMethodError, etc.) — not just NoClassDefFoundError —
        // so that an absent/mismatched Spring on the classpath does not crash callers.
        // We can only test the public-API contract here: with no active transaction the call must
        // simply return without throwing, regardless of whether Spring participation is enabled.
        final Field isInSpringField = JdbcUtil.class.getDeclaredField("isInSpring");
        isInSpringField.setAccessible(true);
        final boolean originalIsInSpring = (boolean) isInSpringField.get(null);
        final Connection springConnection = mock(Connection.class);

        try (org.mockito.MockedStatic<org.springframework.jdbc.datasource.DataSourceUtils> dataSourceUtils = org.mockito.Mockito
                .mockStatic(org.springframework.jdbc.datasource.DataSourceUtils.class)) {
            isInSpringField.set(null, true);
            dataSourceUtils.when(() -> org.springframework.jdbc.datasource.DataSourceUtils.getConnection(mockDataSource)).thenReturn(springConnection);
            dataSourceUtils.when(() -> org.springframework.jdbc.datasource.DataSourceUtils.isConnectionTransactional(springConnection, mockDataSource))
                    .thenThrow(new NoSuchMethodError("incompatible Spring API"));

            assertFalse(JdbcUtil.isInTransaction(mockDataSource));
            dataSourceUtils.verify(() -> org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(springConnection, mockDataSource));
            verify(springConnection, never()).close();
        } finally {
            isInSpringField.set(null, originalIsInSpring);
        }
    }

    @Test
    public void testBeginTransaction() throws SQLException {
        SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource);
        assertNotNull(tran);
        assertTrue(tran.isActive(), "newly begun transaction should be active");
        // The data source should now be marked as in-transaction
        assertTrue(JdbcUtil.isInTransaction(mockDataSource));
        tran.rollbackIfNotCommitted();
    }

    @Test
    public void testBeginTransactionWithIsolation() throws SQLException {
        SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource, IsolationLevel.READ_COMMITTED);
        assertNotNull(tran);
        assertTrue(tran.isActive(), "newly begun transaction should be active");
        assertEquals(IsolationLevel.READ_COMMITTED, tran.isolationLevel(), "isolation level should match the requested level");
        tran.rollbackIfNotCommitted();
    }

    @Test
    public void testBeginTransactionWithIsolationAndUpdateOnly() throws SQLException {
        SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource, IsolationLevel.READ_COMMITTED, true);
        assertNotNull(tran);
        assertTrue(tran.isActive(), "newly begun transaction should be active");
        assertEquals(IsolationLevel.READ_COMMITTED, tran.isolationLevel(), "isolation level should match the requested level");
        // The transaction's connection should be the one from the mock data source
        assertEquals(mockConnection, tran.connection(), "transaction should use the connection from the data source");
        tran.rollbackIfNotCommitted();
    }

    @Test
    public void testBeginTransactionRejectsUnsupportedIsolationBeforeAcquiringConnection() {
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.beginTransaction(mockDataSource, IsolationLevel.NONE));
        verifyNoInteractions(mockDataSource);
    }

    @Test
    public void testCallInTransaction() throws SQLException {
        String result = JdbcUtil.callInTransaction(mockDataSource, () -> "test");
        assertEquals("test", result);
    }

    @Test
    public void testCallInTransactionWithConnection() throws SQLException {
        String result = JdbcUtil.callInTransaction(mockDataSource, conn -> {
            assertNotNull(conn);
            return "test";
        });
        assertEquals("test", result);
    }

    @Test
    public void testCallInTransactionPreservesCallbackFailureWhenRollbackFails() throws SQLException {
        final IllegalStateException callbackFailure = new IllegalStateException("business failure");
        doThrow(new SQLException("rollback failure")).when(mockConnection).rollback();

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> JdbcUtil.callInTransaction(mockDataSource, () -> {
            throw callbackFailure;
        }));

        assertTrue(thrown == callbackFailure);
        assertEquals(1, thrown.getSuppressed().length);
        assertTrue(thrown.getSuppressed()[0] instanceof UncheckedSQLException);
        assertEquals("rollback failure", thrown.getSuppressed()[0].getCause().getMessage());
    }

    @Test
    @DisplayName("transaction callback preserves a failure object reused by rollback")
    public void testTransactionCommandDoesNotSelfSuppressReusedRollbackFailure() throws SQLException {
        final IllegalStateException sharedFailure = new IllegalStateException("shared command/rollback failure");
        doThrow(sharedFailure).when(mockConnection).rollback();

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> JdbcUtil.callInTransaction(mockDataSource, () -> {
            throw sharedFailure;
        }));

        assertSame(sharedFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
    }

    @Test
    public void testRunInTransaction() throws SQLException {
        final boolean[] executed = { false };
        JdbcUtil.runInTransaction(mockDataSource, () -> {
            executed[0] = true;
        });
        assertTrue(executed[0]);
    }

    @Test
    public void testRunInTransactionWithConnection() throws SQLException {
        final boolean[] executed = { false };
        JdbcUtil.runInTransaction(mockDataSource, conn -> {
            assertNotNull(conn);
            executed[0] = true;
        });
        assertTrue(executed[0]);
    }

    @Test
    public void testCallOutsideTransaction() throws SQLException {
        String result = JdbcUtil.callOutsideTransaction(mockDataSource, () -> "test");
        assertEquals("test", result);
    }

    @Test
    public void testCallOutsideTransactionWithDataSource() throws SQLException {
        String result = JdbcUtil.callOutsideTransaction(mockDataSource, ds -> {
            assertNotNull(ds);
            return "test";
        });
        assertEquals("test", result);
    }

    // Test callOutsideTransaction when inside a transaction - runs outside the transaction
    @Test
    public void testCallOutsideTransaction_WhileInTransaction() throws SQLException {
        SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource);
        try {
            // The call should execute outside the active transaction
            String result = JdbcUtil.callOutsideTransaction(mockDataSource, () -> "outsideResult");
            assertEquals("outsideResult", result);
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    @Test
    public void testCallOutsideTransactionWithDS_WhileInTransaction() throws SQLException {
        SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource);
        try {
            String result = JdbcUtil.callOutsideTransaction(mockDataSource, ds -> {
                assertNotNull(ds);
                return "dsResult";
            });
            assertEquals("dsResult", result);
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    @Test
    public void testRunOutsideTransaction() throws SQLException {
        final boolean[] executed = { false };
        JdbcUtil.runOutsideTransaction(mockDataSource, () -> {
            executed[0] = true;
        });
        assertTrue(executed[0]);
    }

    @Test
    public void testRunOutsideTransactionWithDataSource() throws SQLException {
        final boolean[] executed = { false };
        JdbcUtil.runOutsideTransaction(mockDataSource, ds -> {
            assertNotNull(ds);
            executed[0] = true;
        });
        assertTrue(executed[0]);
    }

    @Test
    public void testRunOutsideTransaction_WhileInTransaction() throws SQLException {
        SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource);
        try {
            boolean[] ran = { false };
            JdbcUtil.runOutsideTransaction(mockDataSource, () -> ran[0] = true);
            assertTrue(ran[0]);
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    @Test
    public void testRunOutsideTransactionWithDS_WhileInTransaction() throws SQLException {
        SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource);
        try {
            boolean[] ran = { false };
            JdbcUtil.runOutsideTransaction(mockDataSource, ds -> {
                assertNotNull(ds);
                ran[0] = true;
            });
            assertTrue(ran[0]);
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    @Test
    public void testRunWithoutUsingSpringTransaction() {
        final boolean[] executed = { false };
        JdbcUtil.runIgnoringSpringTransaction(() -> {
            executed[0] = true;
        });
        assertTrue(executed[0]);
    }

    @Test
    public void testCallWithoutUsingSpringTransaction() {
        String result = JdbcUtil.callIgnoringSpringTransaction(() -> "test");
        assertEquals("test", result);
    }

    /**
     * Routes {@code sql} through a new for-update-only transaction and returns whether it joined it. The database dialect is
     * read once per transaction, so a test that changes the mocked product between checks uses a new transaction for each.
     */
    private boolean joinsNewForUpdateOnlyTransaction(final String sql) throws SQLException {
        final SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource, IsolationLevel.READ_COMMITTED, true);

        try {
            final SqlTransaction joined = JdbcUtil.getTransaction(mockDataSource, sql, SqlTransaction.CreatedBy.JDBC_UTIL);
            assertTrue(joined == null || joined == tran);
            return joined != null;
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }
}
