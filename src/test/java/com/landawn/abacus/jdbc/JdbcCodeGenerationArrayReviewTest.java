package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

@Tag("2025")
public class JdbcCodeGenerationArrayReviewTest extends TestBase {

    @Test
    void convertsNestedArrayExpressionsAcceptedByTheDatabase() throws Exception {
        final JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:codegen_nested_array_review");

        try (Connection conn = source.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE array_values (id INT PRIMARY KEY, payload INT ARRAY ARRAY)");
            final String insert = "INSERT INTO array_values(id, payload) VALUES (1, ARRAY[ARRAY[1, 2], ARRAY[3, 4]])";
            assertEquals(1, stmt.executeUpdate(insert));
            stmt.executeUpdate("UPDATE array_values SET payload = NULL");

            final String update = JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(source, insert, "id = 1");
            assertEquals("UPDATE array_values SET id = 1, payload = ARRAY[ARRAY[1, 2], ARRAY[3, 4]] WHERE id = 1", update);
            assertEquals(1, stmt.executeUpdate(update));
            try (ResultSet rows = stmt.executeQuery("SELECT payload[1][2], payload[2][1] FROM array_values")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
                assertEquals(3, rows.getInt(2));
            }
        }
    }

    @Test
    void convertsArrayElementsContainingDelimitersAndComments() throws Exception {
        final JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:codegen_array_literals_review");

        try (Connection conn = source.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE array_values (payload VARCHAR ARRAY ARRAY)");
            final String expression = "ARRAY /* constructor [)] */ [ARRAY[']', ',('], ARRAY['--', '/*']]";
            final String insert = "INSERT INTO array_values(payload) VALUES (" + expression + ")";
            assertEquals(1, stmt.executeUpdate(insert));
            stmt.executeUpdate("UPDATE array_values SET payload = NULL");

            final String update = JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(source, insert);
            assertEquals("UPDATE array_values SET payload = " + expression, update);
            assertEquals(1, stmt.executeUpdate(update));
            try (ResultSet rows = stmt.executeQuery("SELECT payload[1][1], payload[1][2] FROM array_values")) {
                assertTrue(rows.next());
                assertEquals("]", rows.getString(1));
                assertEquals(",(", rows.getString(2));
            }
        }
    }

    @Test
    void convertsNestedSubscriptsOfAnIdentifier() throws Exception {
        final JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:codegen_array_subscripts_review");

        try (Connection conn = source.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE array_input (payload INT ARRAY, indexes INT ARRAY)");
            stmt.execute("INSERT INTO array_input VALUES (ARRAY[10, 20], ARRAY[2])");
            stmt.execute("CREATE TABLE array_output (chosen INT)");
            final String expression = "(SELECT payload[indexes[1]] FROM array_input)";
            final String insert = "INSERT INTO array_output(chosen) VALUES (" + expression + ")";
            assertEquals(1, stmt.executeUpdate(insert));
            stmt.executeUpdate("UPDATE array_output SET chosen = NULL");

            final String update = JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(source, insert);
            assertEquals("UPDATE array_output SET chosen = " + expression, update);
            assertEquals(1, stmt.executeUpdate(update));
            try (ResultSet rows = stmt.executeQuery("SELECT chosen FROM array_output")) {
                assertTrue(rows.next());
                assertEquals(20, rows.getInt(1));
            }
        }
    }

    @Test
    void convertsShorthandArraysDollarQuotesAndChainedSubscripts() {
        final JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:codegen_array_syntax_review");

        for (final String expression : new String[] { "ARRAY[[1, 2], [3, 4]]", "ARRAY[[$q$],($q$, 'x'], ['y', 'z']]",
                "(ARRAY[10, 20])[ARRAY[1][1]]", "ARRAY -- constructor [)]\n [[1, 2], [3, 4]]" }) {
            assertEquals("UPDATE t SET a = " + expression,
                    JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(source, "INSERT INTO t(a) VALUES (" + expression + ")"));
        }
    }

    @Test
    void preservesBracketDelimitedIdentifiersAndTheirEscapes() {
        final JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:codegen_bracket_identifier_review");

        assertEquals("UPDATE \"array_table\" SET \"a[b\" = [a[b], \"a]b\" = [a]]b]",
                JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(source,
                        "INSERT INTO [array_table]([a[b], [a]]b]) VALUES ([a[b], [a]]b])"));
        assertEquals("UPDATE t SET a = (SELECT [a[b] FROM [c]]d])",
                JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(source, "INSERT INTO t(a) VALUES ((SELECT [a[b] FROM [c]]d]))"));
    }

    @Test
    void preservesBracketNamesFollowingTopAndSequencePrefixes() {
        final JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:codegen_bracket_prefix_review");

        for (final String expression : new String[] { "(SELECT TOP (1) [a[b] FROM [c]]d])", "(SELECT TOP (1) [a]]b(c] FROM t)",
                "(SELECT TOP (1) [a]]b'c] FROM t)", "(SELECT TOP (1) [a/*b] FROM t)", "(SELECT TOP (1) [a--b] FROM t)", "NEXT VALUE FOR [a[b]",
                "(SELECT 1 [a[b])", "(SELECT abs(1) [a[b])", "(SELECT t.a FROM t [a[b])" }) {
            assertEquals("UPDATE t SET a = " + expression,
                    JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(source, "INSERT INTO t(a) VALUES (" + expression + ")"));
        }
    }
}
