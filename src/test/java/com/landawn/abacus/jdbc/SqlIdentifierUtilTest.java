package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.query.SqlDialect.ProductInfo;

public class SqlIdentifierUtilTest extends TestBase {

    private static final ProductInfo MYSQL = ProductInfo.of("MySQL", "8.0");
    private static final ProductInfo MARIADB = ProductInfo.of("MariaDB", "10.6");
    private static final ProductInfo POSTGRES = ProductInfo.of("PostgreSQL", "16");

    @Test
    public void testQuoteString_BacktickOnlyForMySqlFamily() {
        assertEquals("\"", SqlIdentifierUtil.quoteString(null));
        assertEquals("\"", SqlIdentifierUtil.quoteString(POSTGRES));
        assertEquals("`", SqlIdentifierUtil.quoteString(MYSQL));
        assertEquals("`", SqlIdentifierUtil.quoteString(MARIADB));
    }

    @Test
    public void testQuoteIdentifier_DoublesEmbeddedQuoteCharacterOnly() {
        assertEquals("\"a\"\"b\"", SqlIdentifierUtil.quoteIdentifier("a\"b", "\""));
        assertEquals("`a``b`", SqlIdentifierUtil.quoteIdentifier("a`b", "`"));
        // The other dialect's quote character is not an escape target.
        assertEquals("`a\"b`", SqlIdentifierUtil.quoteIdentifier("a\"b", "`"));
        assertEquals("\"\"", SqlIdentifierUtil.quoteIdentifier("", "\""));
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
        assertEquals("\"Schema\".users", SqlIdentifierUtil.renderTableName("\"Schema\".users", null));
        assertEquals("`Schema`.users", SqlIdentifierUtil.renderTableName("\"Schema\".users", MYSQL));
        assertEquals("\"my table\"", SqlIdentifierUtil.renderTableName("my table", null));
        assertEquals("`dbo`.`my table`", SqlIdentifierUtil.renderTableName("[dbo].[my table]", MARIADB));
        assertEquals("a.\"b\"\"c\"", SqlIdentifierUtil.renderTableName("a.\"b\"\"c\"", null));
        assertEquals("`a\"b`", SqlIdentifierUtil.renderTableName("`a\"b`", MYSQL));
        assertEquals("\"s.x\".t", SqlIdentifierUtil.renderTableName("\"s.x\".t", null));
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
        assertEquals("\"first_name\"", SqlIdentifierUtil.renderColumnName("\"first_name\"", null));
        assertEquals("`Name`", SqlIdentifierUtil.renderColumnName("[Name]", MYSQL));
        assertEquals("\"Name\"", SqlIdentifierUtil.renderColumnName("`Name`", POSTGRES));
        assertEquals("\"first name\"", SqlIdentifierUtil.renderColumnName("first name", null));
        assertEquals("\"a\"\"b\"", SqlIdentifierUtil.renderColumnName("a\"b", null));
        assertEquals("\"a.b\"", SqlIdentifierUtil.renderColumnName(" [a.b] ", null));

        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderColumnName(null, null));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderColumnName(" ", null));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderColumnName("a.b", null));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.renderColumnName("\"a\"b", null));
    }

    @Test
    public void testCheckColumnName() {
        assertEquals("Name", SqlIdentifierUtil.checkColumnName("Name", null, false));
        assertEquals("\"Name\"", SqlIdentifierUtil.checkColumnName("Name", null, true));
        assertEquals("`Name`", SqlIdentifierUtil.checkColumnName("Name", MYSQL, true));
        assertEquals("\"my col\"", SqlIdentifierUtil.checkColumnName("my col", null, false));
        assertEquals("`a``b`", SqlIdentifierUtil.checkColumnName("a`b", MYSQL, false));

        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.checkColumnName(null, null, false));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifierUtil.checkColumnName("  ", null, true));
    }
}
