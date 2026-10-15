package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.sql.Connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.landawn.abacus.query.SqlDialect.ProductInfo;
import com.landawn.abacus.TestBase;

public class SqlIdentifierUtilTest extends TestBase {

    private static final ProductInfo MYSQL = ProductInfo.of("MySQL", "8.0");
    private static final ProductInfo MARIADB = ProductInfo.of("MariaDB", "10.6");
    private static final ProductInfo POSTGRES = ProductInfo.of("PostgreSQL", "16");

    @Test
    public void testQuoteString_StandardAndMySqlFallbacks() {
        assertEquals("\"", SqlIdentifierUtil.quoteString(null));
        assertEquals("\"", SqlIdentifierUtil.quoteString(POSTGRES));
        assertEquals("`", SqlIdentifierUtil.quoteString(MYSQL));
        assertEquals("`", SqlIdentifierUtil.quoteString(MARIADB));
    }

    @Test
    public void testQuoteString_ConnectionSkipsMetadataWhenQuotingIsDisabled() {
        final Connection conn = mock(Connection.class);

        for (final ProductInfo product : new ProductInfo[] { null, MYSQL, MARIADB, POSTGRES, ProductInfo.of("OtherDB", "1"),
                ProductInfo.of("Microsoft SQL Server", "1"), ProductInfo.of("Adaptive Server Enterprise", "1"), ProductInfo.of("SAP ASE", "1"),
                ProductInfo.of("ASE", "1") }) {
            assertNull(SqlIdentifierUtil.quoteString(conn, product));
        }

        verifyNoInteractions(conn);
    }

    @Test
    public void testQuoteString_NonAnsiDialectFallbacksAndBracketEscaping() {
        for (final String product : java.util.List.of("Spark SQL", "Databricks", "Apache Hive", "Google BigQuery")) {
            assertEquals("`", SqlIdentifierUtil.quoteString(ProductInfo.of(product, "1")));
        }
        for (final String product : java.util.List.of("Microsoft SQL Server", "Adaptive Server Enterprise", "SAP ASE", "ASE")) {
            final ProductInfo info = ProductInfo.of(product, "1");
            assertEquals("[", SqlIdentifierUtil.quoteString(info));
            assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderColumnName("\"a]b\"", info));
            assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderColumnName("\"a[b\"", info));
            assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderTableName("\"s\".\"a]b\"", info));
        }
    }

    @Test
    public void testQuoteIdentifier_DoublesEmbeddedQuoteCharacterOnly() {
        assertEquals("\"a\"\"b\"", SqlIdentifierUtil.quoteIdentifier("a\"b", "\""));
        assertEquals("`a``b`", SqlIdentifierUtil.quoteIdentifier("a`b", "`"));
        // The other dialect's quote character is not an escape target.
        assertEquals("`a\"b`", SqlIdentifierUtil.quoteIdentifier("a\"b", "`"));
        assertEquals("\"\"", SqlIdentifierUtil.quoteIdentifier("", "\""));
        assertEquals("[a]]b]", SqlIdentifierUtil.quoteIdentifier("a]b", "["));
        assertEquals("[a[b]", SqlIdentifierUtil.quoteIdentifier("a[b", "["));
    }

    @Test
    public void testIsSimpleSqlIdentifier_Boundaries() {
        assertFalse(SqlIdentifierUtil.isSimpleSqlIdentifier(null));
        assertFalse(SqlIdentifierUtil.isSimpleSqlIdentifier(""));
        assertTrue(SqlIdentifierUtil.isSimpleSqlIdentifier("_"));
        assertTrue(SqlIdentifierUtil.isSimpleSqlIdentifier("_a1"));
        assertTrue(SqlIdentifierUtil.isSimpleSqlIdentifier("Users9"));
        assertFalse(SqlIdentifierUtil.isSimpleSqlIdentifier("1a"));
        assertFalse(SqlIdentifierUtil.isSimpleSqlIdentifier("a-b"));
        assertFalse(SqlIdentifierUtil.isSimpleSqlIdentifier("a b"));
        assertFalse(SqlIdentifierUtil.isSimpleSqlIdentifier(" a"));
        assertFalse(SqlIdentifierUtil.isSimpleSqlIdentifier("a.b"));
        assertFalse(SqlIdentifierUtil.isSimpleSqlIdentifier("café"));
    }

    @Test
    public void testDelimiterPredicates() {
        assertTrue(SqlIdentifierUtil.startsWithIdentifierDelimiter("  \"a\""));
        assertTrue(SqlIdentifierUtil.startsWithIdentifierDelimiter("`a`"));
        assertTrue(SqlIdentifierUtil.startsWithIdentifierDelimiter("[a]"));
        assertFalse(SqlIdentifierUtil.startsWithIdentifierDelimiter("a"));
        assertFalse(SqlIdentifierUtil.startsWithIdentifierDelimiter(null));
        assertFalse(SqlIdentifierUtil.startsWithIdentifierDelimiter("   "));

        assertTrue(SqlIdentifierUtil.isDelimitedIdentifier(" \"a\" "));
        assertTrue(SqlIdentifierUtil.isDelimitedIdentifier("`a`"));
        assertTrue(SqlIdentifierUtil.isDelimitedIdentifier("[a]"));
        assertTrue(SqlIdentifierUtil.isDelimitedIdentifier("\"\""));
        assertFalse(SqlIdentifierUtil.isDelimitedIdentifier("\""));
        assertFalse(SqlIdentifierUtil.isDelimitedIdentifier("\"a"));
        assertFalse(SqlIdentifierUtil.isDelimitedIdentifier("[a\""));
        assertFalse(SqlIdentifierUtil.isDelimitedIdentifier("a"));
        assertFalse(SqlIdentifierUtil.isDelimitedIdentifier(null));
    }

    @Test
    public void testStripIdentifierDelimiters() {
        assertEquals("a", SqlIdentifierUtil.stripIdentifierDelimiters(" \"a\" "));
        assertEquals("a", SqlIdentifierUtil.stripIdentifierDelimiters("`a`"));
        assertEquals("a", SqlIdentifierUtil.stripIdentifierDelimiters("[a]"));
        assertEquals("a\"", SqlIdentifierUtil.stripIdentifierDelimiters("\"a\"\"\""));
        assertEquals("a]b", SqlIdentifierUtil.stripIdentifierDelimiters("[a]]b]"));
        assertEquals("plain", SqlIdentifierUtil.stripIdentifierDelimiters("  plain "));
        assertEquals("", SqlIdentifierUtil.stripIdentifierDelimiters("\"\""));
        assertEquals("", SqlIdentifierUtil.stripIdentifierDelimiters(null));
        // Mismatched delimiters are left untouched (validation happens in splitQualifiedSqlIdentifier).
        assertEquals("\"a]", SqlIdentifierUtil.stripIdentifierDelimiters("\"a]"));
    }

    @Test
    public void testExplicitlyDelimitedIdentifierParts() {
        assertArrayEquals(new boolean[] { false }, SqlIdentifierUtil.explicitlyDelimitedIdentifierParts("users", 1));
        assertArrayEquals(new boolean[] { true }, SqlIdentifierUtil.explicitlyDelimitedIdentifierParts(" \"users\" ", 1));
        assertArrayEquals(new boolean[] { true, false }, SqlIdentifierUtil.explicitlyDelimitedIdentifierParts("\"s.x\".t", 2));
        assertArrayEquals(new boolean[] { false, true }, SqlIdentifierUtil.explicitlyDelimitedIdentifierParts("a . \"b\"\"c\"", 2));
        assertArrayEquals(new boolean[] { true, true, false }, SqlIdentifierUtil.explicitlyDelimitedIdentifierParts("[s]].x].[t].c", 3));
        // A quote that does not open a part is not a delimiter.
        assertArrayEquals(new boolean[] { false, false }, SqlIdentifierUtil.explicitlyDelimitedIdentifierParts("a\"b.c", 2));
        // partCount smaller/larger than the actual number of parts never throws.
        assertArrayEquals(new boolean[] { true }, SqlIdentifierUtil.explicitlyDelimitedIdentifierParts("\"a\".b", 1));
        assertArrayEquals(new boolean[] { false, true, false }, SqlIdentifierUtil.explicitlyDelimitedIdentifierParts("a.\"b\"", 3));
        assertArrayEquals(new boolean[0], SqlIdentifierUtil.explicitlyDelimitedIdentifierParts(null, 0));
        assertThrows(NegativeArraySizeException.class, () -> SqlIdentifierUtil.explicitlyDelimitedIdentifierParts("a", -1));
    }

    @Test
    public void testRenderTableName() {
        assertEquals("users", SqlIdentifierUtil.renderTableName(" users ", null));
        assertEquals("schema.users", SqlIdentifierUtil.renderTableName("schema.users", POSTGRES));
        for (final String table : java.util.List.of("\"Schema\".users", "my table", "[dbo].[my table]", "a.\"b\"\"c\"", "`a\"b`", "\"s.x\".t")) {
            assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderTableName(table, null));
            assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderTableName(table, MYSQL));
        }
        assertEquals("c.s.t", SqlIdentifierUtil.renderTableName("c . s . t", null));

        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderTableName(null, null));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderTableName("  ", null));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderTableName("a.b.c.d", null));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderTableName("a..b", null));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderTableName("\"a", null));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderTableName("\"a\"b", null));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderTableName("\"\"", null));
    }

    @Test
    public void testRenderColumnName() {
        assertEquals("first_name", SqlIdentifierUtil.renderColumnName("first_name", null));
        for (final String column : java.util.List.of("\"first_name\"", "[Name]", "`Name`", "first name", "a\"b", " [a.b] ")) {
            assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderColumnName(column, null));
            assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderColumnName(column, MYSQL));
        }

        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderColumnName(null, null));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderColumnName(" ", null));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderColumnName("a.b", null));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderColumnName("\"a\"b", null));
    }

    @Test
    public void testCheckColumnName() {
        assertEquals("Name", SqlIdentifierUtil.checkColumnName("Name", null, false));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.checkColumnName("Name", null, true));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.checkColumnName("Name", MYSQL, true));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.checkColumnName("my col", null, false));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.checkColumnName("a`b", MYSQL, false));

        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.checkColumnName(null, null, false));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.checkColumnName("  ", null, true));
    }

    @Test
    public void disabledPolicyCannotBeBypassedBySupplyingADelimiter() {
        assertFalse(SqlIdentifierUtil.isIdentifierQuotingEnabled());
        for (final String quote : java.util.List.of("\"", "`", "[")) {
            assertEquals("catalog.schema.table_name", SqlIdentifierUtil.renderTableNameWithQuote("catalog.schema.table_name", quote));
            assertEquals("column_name", SqlIdentifierUtil.renderColumnNameWithQuote("column_name", quote));
            assertEquals("COLUMN_NAME", SqlIdentifierUtil.renderStoredIdentifier("COLUMN_NAME", quote));
            for (final String name : java.util.List.of("user", "ORDER", "Current_User", "current_timestamp", "a.b", "a b", "a]b", "1column")) {
                final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                        () -> SqlIdentifierUtil.renderStoredIdentifier(name, quote));
                assertTrue(failure.getMessage().contains("Identifier quoting is disabled"));
            }
        }
    }

    @Test
    public void bareExpressionNamesRequireQuotingRegardlessOfCase() {
        for (final String name : java.util.List.of("ROWNUM", "rownum", "RowNum", "CURRENT_PATH", "current_path", "Current_Path")) {
            assertFalse(SqlIdentifierUtil.isUnquotedIdentifier(name), name);
            assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderStoredIdentifier(name, null), name);
            assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderColumnName(name, null), name);
            assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderTableName("schema_name." + name, null), name);
        }

        assertTrue(SqlIdentifierUtil.isUnquotedIdentifier("rownum_value"));
        assertTrue(SqlIdentifierUtil.isUnquotedIdentifier("current_path_value"));
    }

    @Test
    public void storedNamesMustRetainTheirIdentityWithoutQuotes() {
        assertEquals("COLUMN_NAME", SqlIdentifierUtil.renderStoredIdentifier("COLUMN_NAME", "\"", 1));
        assertEquals("column_name", SqlIdentifierUtil.renderStoredIdentifier("column_name", "`", -1));
        assertEquals("MixedCase", SqlIdentifierUtil.renderStoredIdentifier("MixedCase", "[", 0));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderStoredIdentifier("MixedCase", "\"", 1));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderStoredIdentifier("MixedCase", "\"", -1));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderStoredIdentifier("a.b", "\"", 0));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderStoredIdentifier("USER", "\"", 1));
    }

    @ParameterizedTest(name = "identifier={0}, quote={1}, invalid parameter={2}")
    @MethodSource("invalidQuoteArguments")
    void quoteIdentifier_invalidArgument_reportsParameter(final String identifier, final String quote, final String parameter) {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.quoteIdentifier(identifier, quote));
        assertTrue(failure.getMessage().contains(parameter), failure.getMessage());
    }

    private static java.util.stream.Stream<Arguments> invalidQuoteArguments() {
        return java.util.stream.Stream.of(Arguments.of(null, "\"", "identifier"), Arguments.of("name", null, "quote"), Arguments.of("name", "", "quote"));
    }
}
