package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

/**
 * Verifies the identifier-quoting contract documented on {@link JdbcCodeGenerationUtil}: generated SQL uses the driver's
 * reported quote string (brackets on SQL Server/ASE), and a name that needs delimiters is rejected when the driver reports
 * no quoting support.
 */
@Tag("2025")
public class CodeGenerationQuotingContractTest extends TestBase {
    private static Connection dialect(final Connection real, final String product, final String quote) throws SQLException {
        final Connection conn = mock(Connection.class, delegatesTo(real));
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class, delegatesTo(real.getMetaData()));
        doReturn(metadata).when(conn).getMetaData();
        doReturn(product).when(metadata).getDatabaseProductName();
        doReturn(quote).when(metadata).getIdentifierQuoteString();
        return conn;
    }

    private static DataSource productDataSource(final String product) throws SQLException {
        final DataSource ds = mock(DataSource.class);
        final Connection conn = mock(Connection.class);
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn(product);
        when(metadata.getDatabaseProductVersion()).thenReturn("1.0");
        return ds;
    }

    private static void assertUnquotable(final org.junit.jupiter.api.function.Executable call) {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, call);
        assertTrue(e.getMessage().contains("does not support quoting"), e.getMessage());
    }

    @Test
    void theDriverReportedQuoteWinsOverTheProductFallback() throws Exception {
        // MySQL in ANSI_QUOTES mode reports a double quote: the product-name fallback (a backtick) must not be used.
        try (Connection real = DriverManager.getConnection("jdbc:h2:mem:codegen_contract_ansi"); Statement stmt = real.createStatement()) {
            stmt.execute("create table t(id int, \"order\" int)");
            final Connection conn = dialect(real, "MySQL", "\"");
            assertEquals("SELECT \"ID\", \"order\" FROM t", JdbcCodeGenerationUtil.generateSelectSql(conn, "t"));
            assertEquals("UPDATE t SET \"order\" = ? WHERE \"ID\" = ?", JdbcCodeGenerationUtil.generateUpdateSql(conn, "t", "ID"));
        }
    }

    @Test
    void namesNeedingDelimitersAreRejectedWithoutQuotingSupport() throws Exception {
        try (Connection real = DriverManager.getConnection("jdbc:h2:mem:codegen_contract_noquote"); Statement stmt = real.createStatement()) {
            stmt.execute("create table \"my table\"(id int)");
            stmt.execute("create table plain(id int, \"a-b\" int)");
            final Connection conn = dialect(real, "Informix", " ");

            // A non-simple table-name part cannot be rendered without delimiters, for every generate*Sql family.
            assertUnquotable(() -> JdbcCodeGenerationUtil.generateSelectSql(conn, "\"my table\""));
            assertUnquotable(() -> JdbcCodeGenerationUtil.generateInsertSql(conn, "\"my table\""));
            assertUnquotable(() -> JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "\"my table\""));

            // A non-simple column label is rejected too, unless it is excluded from the generated statement.
            assertUnquotable(() -> JdbcCodeGenerationUtil.generateInsertSql(conn, "plain"));
            assertUnquotable(() -> JdbcCodeGenerationUtil.generateUpdateSql(conn, "plain"));
            assertEquals("SELECT ID FROM plain", JdbcCodeGenerationUtil.generateSelectSql(conn, "plain", List.of("a-b"), null));
        }
    }

    // Non-simple names, and names delimited in the INSERT (a simple "Mixed" keeps its case), are re-quoted per product.
    @Test
    void convertInsertSqlToUpdateSqlQuotesNonSimpleColumnsPerProduct() throws Exception {
        final String insertSql = "INSERT INTO t(\"a b\", c, \"Mixed\") VALUES (1, 2, 3)";

        for (final String product : List.of("Microsoft SQL Server", "Adaptive Server Enterprise")) {
            assertEquals("UPDATE t SET [a b] = 1, c = 2, [Mixed] = 3", JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(productDataSource(product), insertSql, null),
                    product);
        }

        for (final String product : List.of("MySQL", "MariaDB", "Spark SQL", "Databricks", "Apache Hive", "Google BigQuery")) {
            assertEquals("UPDATE t SET `a b` = 1, c = 2, `Mixed` = 3", JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(productDataSource(product), insertSql, null),
                    product);
        }

        assertEquals("UPDATE t SET \"a b\" = 1, c = 2, \"Mixed\" = 3", JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(productDataSource("PostgreSQL"), insertSql, null));
    }
}
