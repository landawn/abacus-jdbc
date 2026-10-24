package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

@Tag("integration")
@Tag("2025")
public class JdbcBracketAliasIntegrationTest extends TestBase {

    @Test
    void insertConversionPreservesBracketAliasesAndSeparatesFollowingValues() throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("Microsoft SQL Server");
        when(metadata.getDatabaseProductVersion()).thenReturn("16.0");
        DataSource ds = dataSource(conn);
        for (String alias : new String[] { "[x']", "[x\"]", "[x/*]", "[x--]", "[x$$]", "[x]]']" }) {
            String expression = "(SELECT name " + alias + " FROM t WHERE name = 'a]')";
            assertEquals("UPDATE dest SET a = " + expression + ", b = 2 WHERE id = 1",
                    JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO dest(a, b) VALUES (" + expression + ", 2)", "id = 1"));
        }
    }

    @Test
    void insertConversionKeepsBracketCharactersInsideStringLiteralsOfArraySubscripts() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:bracket_in_array_literal");
        String expression = "(ARRAY[10, 20])[CASE WHEN 'a]' = 'a]' THEN 1 ELSE 2 END]";
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rows = stmt.executeQuery("SELECT " + expression)) {
            assertTrue(rows.next());
            assertEquals(10, rows.getInt(1));
            assertEquals("UPDATE dest SET a = " + expression + ", b = 2",
                    JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO dest(a, b) VALUES (" + expression + ", 2)"));
        }
    }

    private static DataSource dataSource(Connection conn) throws SQLException {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(conn);
        return ds;
    }
}
