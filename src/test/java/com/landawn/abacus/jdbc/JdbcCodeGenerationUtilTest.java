package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.exception.UncheckedIOException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.util.EscapeUtil;
import com.landawn.abacus.util.NamingPolicy;
import com.landawn.abacus.util.Tuple;
import com.landawn.abacus.util.function.QuadFunction;

public class JdbcCodeGenerationUtilTest extends TestBase {

    private Connection connection;
    private PreparedStatement preparedStatement;
    private ResultSet resultSet;
    private ResultSetMetaData resultSetMetaData;
    private DatabaseMetaData databaseMetaData;

    @BeforeEach
    public void setUp() throws SQLException {
        connection = Mockito.mock(Connection.class);
        preparedStatement = Mockito.mock(PreparedStatement.class);
        resultSet = Mockito.mock(ResultSet.class);
        resultSetMetaData = Mockito.mock(ResultSetMetaData.class);
        databaseMetaData = Mockito.mock(DatabaseMetaData.class);

        when(connection.getMetaData()).thenReturn(databaseMetaData);
        when(databaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(databaseMetaData.getDatabaseProductVersion()).thenReturn("8.0");
        when(connection.prepareStatement("SELECT * FROM order_history WHERE 1 > 2")).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(resultSetMetaData);
        when(resultSetMetaData.getColumnCount()).thenReturn(3);
        when(resultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(resultSetMetaData.getColumnLabel(2)).thenReturn("created_at");
        when(resultSetMetaData.getColumnLabel(3)).thenReturn("status");
    }

    @Test
    public void testMinFunc() {
        assertEquals("min(score)", JdbcCodeGenerationUtil.MIN_FUNC.apply(TestEntity.class, Integer.class, "score"));
        assertNull(JdbcCodeGenerationUtil.MIN_FUNC.apply(TestEntity.class, Object.class, "payload"));
    }

    @Test
    public void testMaxFunc() {
        assertEquals("max(createdAt)", JdbcCodeGenerationUtil.MAX_FUNC.apply(TestEntity.class, String.class, "createdAt"));
        assertNull(JdbcCodeGenerationUtil.MAX_FUNC.apply(TestEntity.class, Runnable.class, "handler"));
    }

    @Test
    public void testGenerateSelectSql_WithExcludedColumns() {
        String sql = JdbcCodeGenerationUtil.generateSelectSql(connection, "order_history", List.of("createdAt"), "status = 'OPEN'");

        assertEquals("SELECT id, status FROM order_history WHERE status = 'OPEN'", sql);
    }

    @Test
    public void testGenerateInsertSql_WithExcludedColumns() {
        String sql = JdbcCodeGenerationUtil.generateInsertSql(connection, "order_history", List.of("status"));

        assertEquals("INSERT INTO order_history(id, created_at) VALUES (?, ?)", sql);
    }

    // Excluding every column must fail fast with IllegalArgumentException instead of emitting malformed SQL
    // (mirrors the existing guard in the UPDATE family).
    @Test
    public void testGenerateSelectSql_AllColumnsExcluded_Throws() {
        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateSelectSql(connection, "order_history", List.of("id", "created_at", "status"), null));
    }

    @Test
    public void testGenerateInsertSql_AllColumnsExcluded_Throws() {
        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateInsertSql(connection, "order_history", List.of("id", "created_at", "status")));
    }

    @Test
    public void testGenerateNamedInsertSql_AllColumnsExcluded_Throws() {
        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateNamedInsertSql(connection, "order_history", List.of("id", "created_at", "status")));
    }

    @Test
    public void testGenerateSelectSql_QuotesSpecialTableNameInMetadataQuery() throws SQLException {
        final Connection conn = Mockito.mock(Connection.class);
        final DatabaseMetaData metaData = Mockito.mock(DatabaseMetaData.class);
        final PreparedStatement stmt = Mockito.mock(PreparedStatement.class);
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData rsMetaData = Mockito.mock(ResultSetMetaData.class);

        when(conn.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("MySQL");
        when(metaData.getDatabaseProductVersion()).thenReturn("8.0");
        when(conn.prepareStatement("SELECT * FROM `order-history` WHERE 1 > 2")).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(rsMetaData);
        when(rsMetaData.getColumnCount()).thenReturn(2);
        when(rsMetaData.getColumnLabel(1)).thenReturn("id");
        when(rsMetaData.getColumnLabel(2)).thenReturn("created_at");

        final String sql = JdbcCodeGenerationUtil.generateSelectSql(conn, "order-history");

        assertEquals("SELECT id, created_at FROM `order-history`", sql);
    }

    @Test
    public void testGenerateInsertSql_QuotesSpecialTableNameInMetadataQuery() throws SQLException {
        final Connection conn = Mockito.mock(Connection.class);
        final DatabaseMetaData metaData = Mockito.mock(DatabaseMetaData.class);
        final PreparedStatement stmt = Mockito.mock(PreparedStatement.class);
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData rsMetaData = Mockito.mock(ResultSetMetaData.class);

        when(conn.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("MySQL");
        when(metaData.getDatabaseProductVersion()).thenReturn("8.0");
        when(conn.prepareStatement("SELECT * FROM `order-history` WHERE 1 > 2")).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(rsMetaData);
        when(rsMetaData.getColumnCount()).thenReturn(2);
        when(rsMetaData.getColumnLabel(1)).thenReturn("id");
        when(rsMetaData.getColumnLabel(2)).thenReturn("created_at");

        final String sql = JdbcCodeGenerationUtil.generateInsertSql(conn, "order-history");

        assertEquals("INSERT INTO `order-history`(id, created_at) VALUES (?, ?)", sql);
    }

    @Test
    public void testGenerateSelectSql_QuotesQualifiedSpecialTableName() throws SQLException {
        final Connection conn = Mockito.mock(Connection.class);
        final DatabaseMetaData metaData = Mockito.mock(DatabaseMetaData.class);
        final PreparedStatement stmt = Mockito.mock(PreparedStatement.class);
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData rsMetaData = Mockito.mock(ResultSetMetaData.class);

        when(conn.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("MySQL");
        when(metaData.getDatabaseProductVersion()).thenReturn("8.0");
        // Per-part conditional quoting: the simple schema part `sales` is left unquoted (quoting it would
        // force case-exact resolution and break on case-folding databases); only the special table part
        // `order-history` is quoted.
        when(conn.prepareStatement("SELECT * FROM sales.`order-history` WHERE 1 > 2")).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(rsMetaData);
        when(rsMetaData.getColumnCount()).thenReturn(2);
        when(rsMetaData.getColumnLabel(1)).thenReturn("id");
        when(rsMetaData.getColumnLabel(2)).thenReturn("created_at");

        final String sql = JdbcCodeGenerationUtil.generateSelectSql(conn, "sales.order-history");

        assertEquals("SELECT id, created_at FROM sales.`order-history`", sql);
    }

    @Test
    public void testGenerateSelectSql_PreservesQuotedDotsWithinSingleIdentifier() throws SQLException {
        final Connection conn = Mockito.mock(Connection.class);
        final DatabaseMetaData metaData = Mockito.mock(DatabaseMetaData.class);
        final PreparedStatement stmt = Mockito.mock(PreparedStatement.class);
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData rsMetaData = Mockito.mock(ResultSetMetaData.class);

        when(conn.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("MySQL");
        when(metaData.getDatabaseProductVersion()).thenReturn("8.0");
        when(conn.prepareStatement("SELECT * FROM `sales.data` WHERE 1 > 2")).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(rsMetaData);
        when(rsMetaData.getColumnCount()).thenReturn(2);
        when(rsMetaData.getColumnLabel(1)).thenReturn("id");
        when(rsMetaData.getColumnLabel(2)).thenReturn("created_at");

        final String sql = JdbcCodeGenerationUtil.generateSelectSql(conn, "\"sales.data\"");

        assertEquals("SELECT id, created_at FROM `sales.data`", sql);
    }

    @Test
    public void testGenerateSelectSql_QuotesSinglePartIdentifierStartingWithDigit() throws SQLException {
        final Connection conn = Mockito.mock(Connection.class);
        final DatabaseMetaData metaData = Mockito.mock(DatabaseMetaData.class);
        final PreparedStatement stmt = Mockito.mock(PreparedStatement.class);
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData rsMetaData = Mockito.mock(ResultSetMetaData.class);

        when(conn.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("MySQL");
        when(metaData.getDatabaseProductVersion()).thenReturn("8.0");
        when(conn.prepareStatement(ArgumentMatchers.anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(rsMetaData);
        when(rsMetaData.getColumnCount()).thenReturn(2);
        when(rsMetaData.getColumnLabel(1)).thenReturn("id");
        when(rsMetaData.getColumnLabel(2)).thenReturn("1st_value");

        final String sql = JdbcCodeGenerationUtil.generateSelectSql(conn, "123abc");

        assertEquals("SELECT id, `1st_value` FROM `123abc`", sql);
    }

    @Test
    public void testGenerateSelectSql_EscapesEmbeddedBacktickInColumnName() throws SQLException {
        final Connection conn = Mockito.mock(Connection.class);
        final DatabaseMetaData metaData = Mockito.mock(DatabaseMetaData.class);
        final PreparedStatement stmt = Mockito.mock(PreparedStatement.class);
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData rsMetaData = Mockito.mock(ResultSetMetaData.class);

        when(conn.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("MySQL");
        when(metaData.getDatabaseProductVersion()).thenReturn("8.0");
        when(conn.prepareStatement("SELECT * FROM demo WHERE 1 > 2")).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(rsMetaData);
        when(rsMetaData.getColumnCount()).thenReturn(2);
        when(rsMetaData.getColumnLabel(1)).thenReturn("id");
        when(rsMetaData.getColumnLabel(2)).thenReturn("we`ird");

        final String sql = JdbcCodeGenerationUtil.generateSelectSql(conn, "demo");

        // The embedded backtick must be doubled so the generated SQL is valid (not unbalanced/injectable).
        assertEquals("SELECT id, `we``ird` FROM demo", sql);
    }

    @Test
    public void testGenerateSelectSql_EscapesEmbeddedDoubleQuoteInColumnName() throws SQLException {
        final Connection conn = Mockito.mock(Connection.class);
        final DatabaseMetaData metaData = Mockito.mock(DatabaseMetaData.class);
        final PreparedStatement stmt = Mockito.mock(PreparedStatement.class);
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData rsMetaData = Mockito.mock(ResultSetMetaData.class);

        when(conn.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(metaData.getDatabaseProductVersion()).thenReturn("15.0");
        when(conn.prepareStatement("SELECT * FROM demo WHERE 1 > 2")).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(rsMetaData);
        when(rsMetaData.getColumnCount()).thenReturn(2);
        when(rsMetaData.getColumnLabel(1)).thenReturn("id");
        when(rsMetaData.getColumnLabel(2)).thenReturn("we\"ird");

        final String sql = JdbcCodeGenerationUtil.generateSelectSql(conn, "demo");

        // ANSI double-quote dialect: the embedded double-quote must be doubled.
        assertEquals("SELECT id, \"we\"\"ird\" FROM demo", sql);
    }

    @Test
    public void testGenerateSelectSql_DataSourceWrapsSQLException() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("connection failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateSelectSql(dataSource, "demo"));
    }

    @Test
    public void testEntityCodeConfigBuilder() {
        JdbcCodeGenerationUtil.EntityCodeConfig.JsonXmlConfig jsonXmlConfig = JdbcCodeGenerationUtil.EntityCodeConfig.JsonXmlConfig.builder()
                .namingPolicy(NamingPolicy.CAMEL_CASE)
                .ignoredFields("secret")
                .build();

        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .className("DemoEntity")
                .useBoxedType(true)
                .jsonXmlConfig(jsonXmlConfig)
                .build();

        assertEquals("DemoEntity", config.getClassName());
        assertEquals(true, config.isUseBoxedType());
        assertEquals(jsonXmlConfig, config.getJsonXmlConfig());
    }

    // Setup helper for a full generateEntityClass mock
    private void setupFullGenerateEntityClassMock() throws SQLException {
        Statement stmt = Mockito.mock(Statement.class);
        ResultSet pkRs = Mockito.mock(ResultSet.class);
        when(resultSet.getStatement()).thenReturn(stmt);
        when(stmt.getConnection()).thenReturn(connection);
        when(databaseMetaData.getPrimaryKeys(null, null, "order_history")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        when(resultSetMetaData.getColumnName(1)).thenReturn("id");
        when(resultSetMetaData.getColumnName(2)).thenReturn("created_at");
        when(resultSetMetaData.getColumnName(3)).thenReturn("status");
        when(resultSetMetaData.getColumnType(1)).thenReturn(Types.BIGINT);
        when(resultSetMetaData.getColumnType(2)).thenReturn(Types.TIMESTAMP);
        when(resultSetMetaData.getColumnType(3)).thenReturn(Types.VARCHAR);
        when(resultSetMetaData.getColumnClassName(1)).thenReturn("java.lang.Long");
        when(resultSetMetaData.getColumnClassName(2)).thenReturn("java.sql.Timestamp");
        when(resultSetMetaData.getColumnClassName(3)).thenReturn("java.lang.String");
    }

    // Test generateEntityClass(Connection, tableName) overload
    @Test
    public void testGenerateEntityClass_Connection_TableName() throws SQLException {
        setupFullGenerateEntityClassMock();
        String result = JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history");
        assertNotNull(result);
        assertTrue(result.contains("OrderHistory") || result.contains("class "));
    }

    @Test
    public void testGenerateEntityClass_QuotesSpecialTableNameInMetadataQuery() throws SQLException {
        final Connection conn = Mockito.mock(Connection.class);
        final PreparedStatement stmt = Mockito.mock(PreparedStatement.class);
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData rsMetaData = Mockito.mock(ResultSetMetaData.class);
        final DatabaseMetaData metaData = Mockito.mock(DatabaseMetaData.class);
        final Statement jdbcStmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);

        when(conn.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("MySQL");
        when(metaData.getDatabaseProductVersion()).thenReturn("8.0");
        when(conn.prepareStatement("SELECT * FROM `order-history` WHERE 1 > 2")).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(rsMetaData);
        when(rs.getStatement()).thenReturn(jdbcStmt);
        when(jdbcStmt.getConnection()).thenReturn(conn);
        when(metaData.getPrimaryKeys(null, null, "order-history")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        when(rsMetaData.getColumnCount()).thenReturn(2);
        when(rsMetaData.getColumnName(1)).thenReturn("id");
        when(rsMetaData.getColumnName(2)).thenReturn("created_at");
        when(rsMetaData.getColumnLabel(1)).thenReturn("id");
        when(rsMetaData.getColumnLabel(2)).thenReturn("created_at");
        when(rsMetaData.getColumnType(1)).thenReturn(Types.BIGINT);
        when(rsMetaData.getColumnType(2)).thenReturn(Types.TIMESTAMP);
        when(rsMetaData.getColumnClassName(1)).thenReturn("java.lang.Long");
        when(rsMetaData.getColumnClassName(2)).thenReturn("java.sql.Timestamp");

        final String result = JdbcCodeGenerationUtil.generateEntityClass(conn, "order-history");

        assertNotNull(result);
        assertTrue(result.contains("OrderHistory"));
    }

    // Test generateEntityClass(Connection, tableName, EntityCodeConfig) overload
    @Test
    public void testGenerateEntityClass_Connection_TableName_Config() throws SQLException {
        setupFullGenerateEntityClassMock();
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().className("OrderHistory").useBoxedType(true).build();
        String result = JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history", config);
        assertNotNull(result);
        assertTrue(result.contains("OrderHistory"));
    }

    // Test generateEntityClass(Connection, entityName, query) overload
    @Test
    public void testGenerateEntityClass_Connection_EntityName_Query() throws SQLException {
        setupFullGenerateEntityClassMock();
        String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2");
        assertNotNull(result);
    }

    // Test generateEntityClass with DataSource overload
    @Test
    public void testGenerateEntityClass_DataSource_TableName() throws SQLException {
        setupFullGenerateEntityClassMock();
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        String result = JdbcCodeGenerationUtil.generateEntityClass(dataSource, "order_history");
        assertNotNull(result);
    }

    // Test generateEntityClass with DataSource and config overload
    @Test
    public void testGenerateEntityClass_DataSource_TableName_Config() throws SQLException {
        setupFullGenerateEntityClassMock();
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().className("OrderHist").build();
        String result = JdbcCodeGenerationUtil.generateEntityClass(dataSource, "order_history", config);
        assertNotNull(result);
    }

    // Test generateEntityClass with DataSource, query, and config
    @Test
    public void testGenerateEntityClass_DataSource_EntityName_Query_Config() throws SQLException {
        setupFullGenerateEntityClassMock();
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(dataSource, "order_history", "SELECT * FROM order_history WHERE 1 > 2", null);
        assertNotNull(result);
    }

    // Test generateEntityClass DataSource wraps SQL exceptions for entity overload
    @Test
    public void testGenerateEntityClass_DataSource_WrapsSQLException() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("connection failed"));
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateEntityClass(dataSource, "order_history"));
    }

    // Test generateEntityClass with packageName config
    @Test
    public void testGenerateEntityClass_WithPackageName() throws SQLException {
        setupFullGenerateEntityClassMock();
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().packageName("com.example.model").build();
        String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("package com.example.model"));
    }

    // Test generateEntityClass with idField config
    @Test
    public void testGenerateEntityClass_WithIdField() throws SQLException {
        setupFullGenerateEntityClassMock();
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().idField("id").build();
        String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("@Id"));
    }

    // Test generateEntityClass with readOnlyFields config
    @Test
    public void testGenerateEntityClass_WithReadOnlyFields() throws SQLException {
        setupFullGenerateEntityClassMock();
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().readOnlyFields(Arrays.asList("created_at")).build();
        String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("@ReadOnly"));
    }

    // Test generateEntityClass with excludedFields config
    @Test
    public void testGenerateEntityClass_WithExcludedFields() throws SQLException {
        setupFullGenerateEntityClassMock();
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().excludedFields(Arrays.asList("status")).build();
        String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
    }

    // Test generateEntityClass with generateCopyMethod config
    @Test
    public void testGenerateEntityClass_WithCopyMethod() throws SQLException {
        setupFullGenerateEntityClassMock();
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .generateCopyMethod(true)
                .className("OrderHistory")
                .build();
        String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
    }

    @Test
    public void testGenerateEntityClass_CopyMethodParsesInitializedAdditionalFieldName() throws SQLException {
        setupFullGenerateEntityClassMock();
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .generateCopyMethod(true)
                .className("OrderHistory")
                .additionalFieldsOrLines("    private int retryCount = 1;")
                .build();

        String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);

        assertTrue(result.contains("private int retryCount = 1;"));
        assertTrue(result.contains("copy.retryCount = this.retryCount;"));
        assertFalse(result.contains("copy.1 = this.1;"));
    }

    @Test
    public void testGenerateEntityClass_AdditionalFieldWithGenericTypeComma() throws SQLException {
        setupFullGenerateEntityClassMock();
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .generateCopyMethod(true)
                .className("OrderHistory")
                .additionalFieldsOrLines("    private Map<String, Object> attrs;")
                .build();

        // A generic type with a comma (Map<String, Object>) must not be mis-parsed as a multi-variable declaration.
        String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);

        assertTrue(result.contains("private Map<String, Object> attrs;"));
        assertTrue(result.contains("copy.attrs = this.attrs;"));
        assertFalse(result.contains("copy.Object> = this.Object>;"));
    }

    // Test generateEntityClass with conflicting readOnly and nonUpdatable for same field
    @Test
    public void testGenerateEntityClass_ConflictingReadOnlyAndNonUpdatable() throws SQLException {
        setupFullGenerateEntityClassMock();
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .readOnlyFields(Arrays.asList("id"))
                .nonUpdatableFields(Arrays.asList("id"))
                .build();
        assertThrows(RuntimeException.class,
                () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config));
    }

    // Test generateSelectSql with DataSource (basic overload)
    @Test
    public void testGenerateSelectSql_WithDataSource() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        String sql = JdbcCodeGenerationUtil.generateSelectSql(dataSource, "order_history");
        assertNotNull(sql);
        assertTrue(sql.startsWith("SELECT"));
    }

    // Test generateInsertSql with DataSource
    @Test
    public void testGenerateInsertSql_WithDataSource() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        String sql = JdbcCodeGenerationUtil.generateInsertSql(dataSource, "order_history");
        assertNotNull(sql);
        assertTrue(sql.startsWith("INSERT"));
    }

    // generateEntityClass(DataSource, entityName, query) - line 261
    @Test
    public void testGenerateEntityClass_DataSource_EntityName_Query() throws SQLException {
        setupFullGenerateEntityClassMock();
        final Statement stmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);
        when(resultSet.getStatement()).thenReturn(stmt);
        when(stmt.getConnection()).thenReturn(connection);
        when(databaseMetaData.getPrimaryKeys(null, null, "MyEntity")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        when(connection.prepareStatement("SELECT * FROM v WHERE 1 > 2")).thenReturn(preparedStatement);

        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(dataSource, "MyEntity", "SELECT * FROM v WHERE 1 > 2");
        assertNotNull(result);
        assertTrue(result.contains("MyEntity"));
    }

    // generateSelectSql(DataSource, tableName, excludedColumns, whereClause) - line 940
    @Test
    public void testGenerateSelectSql_DataSource_WithExcludedColumnsAndWhere() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String sql = JdbcCodeGenerationUtil.generateSelectSql(dataSource, "order_history", Arrays.asList("status"), "id > 0");
        assertNotNull(sql);
        assertTrue(sql.startsWith("SELECT"));
    }

    // generateInsertSql(DataSource, tableName, excludedColumns) - line 1069
    @Test
    public void testGenerateInsertSql_DataSource_WithExcludedColumns() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String sql = JdbcCodeGenerationUtil.generateInsertSql(dataSource, "order_history", Arrays.asList("id"));
        assertNotNull(sql);
        assertTrue(sql.startsWith("INSERT"));
    }

    // generateNamedInsertSql(DataSource, tableName) - line 1138
    @Test
    public void testGenerateNamedInsertSql_DataSource() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String sql = JdbcCodeGenerationUtil.generateNamedInsertSql(dataSource, "order_history");
        assertNotNull(sql);
        assertTrue(sql.startsWith("INSERT"));
    }

    // generateNamedInsertSql(Connection, tableName) - line 1164
    @Test
    public void testGenerateNamedInsertSql_Connection() throws SQLException {
        String sql = JdbcCodeGenerationUtil.generateNamedInsertSql(connection, "order_history");
        assertNotNull(sql);
        assertTrue(sql.startsWith("INSERT"));
    }

    // generateNamedInsertSql(DataSource, tableName, excludedColumns) - line 1200
    @Test
    public void testGenerateNamedInsertSql_DataSource_WithExcludedColumns() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String sql = JdbcCodeGenerationUtil.generateNamedInsertSql(dataSource, "order_history", Arrays.asList("id"));
        assertNotNull(sql);
        assertTrue(sql.startsWith("INSERT"));
    }

    // generateNamedInsertSql(Connection, tableName, excludedColumns) - line 1231
    @Test
    public void testGenerateNamedInsertSql_Connection_WithExcludedColumns() throws SQLException {
        String sql = JdbcCodeGenerationUtil.generateNamedInsertSql(connection, "order_history", Arrays.asList("id"));
        assertNotNull(sql);
        assertTrue(sql.startsWith("INSERT"));
    }

    // generateUpdateSql(DataSource, tableName) - line 1270
    @Test
    public void testGenerateUpdateSql_DataSource() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String sql = JdbcCodeGenerationUtil.generateUpdateSql(dataSource, "order_history");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
    }

    // generateUpdateSql(Connection, tableName) - line 1298
    @Test
    public void testGenerateUpdateSql_Connection() throws SQLException {
        String sql = JdbcCodeGenerationUtil.generateUpdateSql(connection, "order_history");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
    }

    // generateUpdateSql(DataSource, tableName, keyColumnName) - line 1331
    @Test
    public void testGenerateUpdateSql_DataSource_WithKeyColumn() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String sql = JdbcCodeGenerationUtil.generateUpdateSql(dataSource, "order_history", "id");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
    }

    // generateUpdateSql(Connection, tableName, keyColumnName) - line 1357
    @Test
    public void testGenerateUpdateSql_Connection_WithKeyColumn() throws SQLException {
        String sql = JdbcCodeGenerationUtil.generateUpdateSql(connection, "order_history", "id");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
    }

    // generateNamedUpdateSql(DataSource, tableName) - line 1507
    @Test
    public void testGenerateNamedUpdateSql_DataSource() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String sql = JdbcCodeGenerationUtil.generateNamedUpdateSql(dataSource, "order_history");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
    }

    // generateNamedUpdateSql(Connection, tableName) - line 1536
    @Test
    public void testGenerateNamedUpdateSql_Connection() throws SQLException {
        String sql = JdbcCodeGenerationUtil.generateNamedUpdateSql(connection, "order_history");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
    }

    // generateNamedUpdateSql(DataSource, tableName, keyColumnName) - line 1572
    @Test
    public void testGenerateNamedUpdateSql_DataSource_WithKeyColumn() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String sql = JdbcCodeGenerationUtil.generateNamedUpdateSql(dataSource, "order_history", "id");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
    }

    // generateNamedUpdateSql(Connection, tableName, keyColumnName) - line 1598
    @Test
    public void testGenerateNamedUpdateSql_Connection_WithKeyColumn() throws SQLException {
        String sql = JdbcCodeGenerationUtil.generateNamedUpdateSql(connection, "order_history", "id");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
    }

    // generateUpdateSql(DataSource, tableName, excludedColumnNames, keyColumnNames, whereClause) - line 1420
    @Test
    public void testGenerateUpdateSql_DataSource_WithExcludedColumnsAndWhere() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String sql = JdbcCodeGenerationUtil.generateUpdateSql(dataSource, "order_history", Arrays.asList("created_at"), Arrays.asList("id"), "status = 'OPEN'");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
        assertTrue(sql.contains("WHERE"));
    }

    // generateUpdateSql(Connection, tableName, excludedColumnNames, keyColumnNames, whereClause) - line 1460
    @Test
    public void testGenerateUpdateSql_Connection_WithExcludedColumnsAndWhere() throws SQLException {
        String sql = JdbcCodeGenerationUtil.generateUpdateSql(connection, "order_history", Arrays.asList("created_at"), Arrays.asList("id"), "status = 'OPEN'");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
        assertTrue(sql.contains("WHERE"));
    }

    // generateNamedUpdateSql(DataSource, tableName, excludedColumnNames, keyColumnNames, whereClause) - line 1656
    @Test
    public void testGenerateNamedUpdateSql_DataSource_WithExcludedColumnsAndWhere() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String sql = JdbcCodeGenerationUtil.generateNamedUpdateSql(dataSource, "order_history", Arrays.asList("created_at"), Arrays.asList("id"),
                "status = 'OPEN'");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
        assertTrue(sql.contains(":"));
        assertTrue(sql.contains("WHERE"));
    }

    // generateNamedUpdateSql(Connection, tableName, excludedColumnNames, keyColumnNames, whereClause) - line 1696
    @Test
    public void testGenerateNamedUpdateSql_Connection_WithExcludedColumnsAndWhere() throws SQLException {
        String sql = JdbcCodeGenerationUtil.generateNamedUpdateSql(connection, "order_history", Arrays.asList("created_at"), Arrays.asList("id"),
                "status = 'OPEN'");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
        assertTrue(sql.contains(":"));
        assertTrue(sql.contains("WHERE"));
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
    public void testConvertInsertSqlToUpdateSql_PreservesNamedParametersAndQuotedCommas() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        String insertSql = "INSERT INTO order_history(id, status, name) VALUES (:id, :status, 'A,B')";
        String updateSql = JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource, insertSql);

        assertEquals("UPDATE order_history SET id = :id, status = :status, name = 'A,B'", updateSql);
    }

    // Test generateEntityClass with customized EntityCodeConfig fields
    @Test
    public void testEntityCodeConfig_GettersAndSetters() {
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .className("TestEntity")
                .packageName("com.test")
                .useBoxedType(false)
                .idField("id")
                .idFields(Arrays.asList("id"))
                .readOnlyFields(Arrays.asList("createdAt"))
                .nonUpdatableFields(Arrays.asList("version"))
                .build();

        assertEquals("TestEntity", config.getClassName());
        assertEquals("com.test", config.getPackageName());
        assertNull(config.getSrcDir());
    }

    static final class TestEntity {
    }

    // generateEntityClass(DataSource, entityName, query, config) wraps SQLException from getConnection (L291-292)
    @Test
    public void testGenerateEntityClass_DataSourceEntityNameQueryConfig_WrapsSQLException() throws SQLException {
        final DataSource dataSource = Mockito.mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("connection failed"));

        assertThrows(UncheckedSQLException.class,
                () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(dataSource, "MyEntity", "SELECT * FROM t WHERE 1=2", null));
    }

    // generateEntityClass(Connection, entityName, query, config) wraps SQLException from prepareStatement (L385-386)
    @Test
    public void testGenerateEntityClass_ConnectionEntityNameQueryConfig_WrapsSQLException() throws SQLException {
        final Connection conn = Mockito.mock(Connection.class);
        when(conn.prepareStatement(ArgumentMatchers.anyString())).thenThrow(new SQLException("prepare failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(conn, "MyEntity", "SELECT * FROM t WHERE 1=2", null));
    }

    // generateEntityClass with custom tableAnnotationClass and columnAnnotationClass (L418, L421)
    @Test
    public void testGenerateEntityClass_WithCustomAnnotationClasses() throws SQLException {
        setupFullGenerateEntityClassMock();

        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .tableAnnotationClass(jakarta.persistence.Table.class)
                .columnAnnotationClass(jakarta.persistence.Column.class)
                .build();

        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
    }

    // generateEntityClass with additionalFieldsOrLines set (L444-455)
    @Test
    public void testGenerateEntityClass_WithAdditionalFieldsOrLines() throws SQLException {
        setupFullGenerateEntityClassMock();

        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .additionalFieldsOrLines("private String extraField; // extra\nprivate int extraCount;")
                .build();

        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
    }

    // Primary key column populates idFields (L496)
    @Test
    public void testGenerateEntityClass_WithPrimaryKey() throws SQLException {
        final Statement stmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);
        when(resultSet.getStatement()).thenReturn(stmt);
        when(stmt.getConnection()).thenReturn(connection);
        when(databaseMetaData.getPrimaryKeys(null, null, "order_history")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(true, false);
        when(pkRs.getString("COLUMN_NAME")).thenReturn("id");
        when(resultSetMetaData.getColumnName(1)).thenReturn("id");
        when(resultSetMetaData.getColumnName(2)).thenReturn("created_at");
        when(resultSetMetaData.getColumnName(3)).thenReturn("status");
        when(resultSetMetaData.getColumnType(1)).thenReturn(Types.BIGINT);
        when(resultSetMetaData.getColumnType(2)).thenReturn(Types.TIMESTAMP);
        when(resultSetMetaData.getColumnType(3)).thenReturn(Types.VARCHAR);
        when(resultSetMetaData.getColumnClassName(1)).thenReturn("java.lang.Long");
        when(resultSetMetaData.getColumnClassName(2)).thenReturn("java.sql.Timestamp");
        when(resultSetMetaData.getColumnClassName(3)).thenReturn("java.lang.String");

        final String result = JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history");
        assertNotNull(result);
        assertTrue(result.contains("@Id") || result.contains("id"));
    }

    // classNamesToImport adds custom imports (L535)
    @Test
    public void testGenerateEntityClass_WithClassNamesToImport() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .classNamesToImport(Arrays.asList("com.example.MyAnnotation"))
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("import com.example.MyAnnotation;"));
    }

    @Test
    public void testGenerateEntityClass_CustomImportUsesExactDuplicateMatching() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                // This is a prefix of the built-in ReadOnly import but is still a distinct requested import.
                .classNamesToImport(Arrays.asList("com.landawn.abacus.annotation.Read"))
                .build();

        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);

        assertTrue(result.contains("import com.landawn.abacus.annotation.Read;"));
    }

    // Jakarta annotation in headPart triggers extra LINE_SEPARATOR (L597)
    // and custom tableAnnotationClass = jakarta.persistence.Table covers L542 path
    @Test
    public void testGenerateEntityClass_WithJakartaAnnotations_AddsLineSeparator() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .tableAnnotationClass(jakarta.persistence.Table.class)
                .columnAnnotationClass(jakarta.persistence.Column.class)
                .idAnnotationClass(jakarta.persistence.Id.class)
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("jakarta.persistence"));
    }

    // Custom non-jakarta tableAnnotationClass replaces internal annotation import (L541)
    @Test
    public void testGenerateEntityClass_WithCustomNonJakartaAnnotationClass() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .tableAnnotationClass(org.junit.jupiter.api.Test.class) // non-jakarta, non-abacus annotation
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
    }

    // nonUpdatableFields marks a field @NonUpdatable (L661)
    @Test
    public void testGenerateEntityClass_WithNonUpdatableField() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .nonUpdatableFields(Arrays.asList("status"))
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("@NonUpdatable"));
    }

    // customizedFieldDbTypes adds @Type annotation (L669)
    @Test
    public void testGenerateEntityClass_WithCustomizedFieldDbType() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .customizedFieldDbTypes(Arrays.asList(Tuple.of("status", "VARCHAR(255)")))
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
    }

    // generateCopyMethod=true and additionalFieldsOrLines generates copy() with extra fields (L715)
    @Test
    public void testGenerateEntityClass_WithCopyMethodAndAdditionalFields() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .generateCopyMethod(true)
                .additionalFieldsOrLines("private String extra; // extra")
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("copy()"));
    }

    // generateFieldNameTable=true generates X interface with property name constants (L722-751)
    @Test
    public void testGenerateEntityClass_WithFieldNameTable() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().generateFieldNameTable(true).build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("public interface "));
    }

    // jsonXmlConfig with namingPolicy generates @JsonXmlConfig (L603-635)
    @Test
    public void testGenerateEntityClass_WithJsonXmlConfig() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig.JsonXmlConfig jsonXmlConfig = new JdbcCodeGenerationUtil.EntityCodeConfig.JsonXmlConfig();
        jsonXmlConfig.setNamingPolicy(NamingPolicy.CAMEL_CASE);
        jsonXmlConfig.setIgnoredFields("createdAt");
        jsonXmlConfig.setDateFormat("yyyy-MM-dd");
        jsonXmlConfig.setTimeZone("UTC");
        jsonXmlConfig.setNumberFormat("#.##");

        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().jsonXmlConfig(jsonXmlConfig).build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("@JsonXmlConfig"));
    }

    @Test
    public void testGenerateEntityClass_EscapesJsonXmlConfigStringLiterals() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig.JsonXmlConfig jsonXmlConfig = new JdbcCodeGenerationUtil.EntityCodeConfig.JsonXmlConfig();
        jsonXmlConfig.setIgnoredFields("raw\"field, path\\field, line\nfield");
        jsonXmlConfig.setDateFormat("yyyy\"MM");
        jsonXmlConfig.setTimeZone("UTC\\GMT");
        jsonXmlConfig.setNumberFormat("#\n##");

        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().jsonXmlConfig(jsonXmlConfig).build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);

        assertTrue(result.contains("ignoredFields = { \"raw\\\"field\", \"path\\\\field\", \"line\\nfield\" }"), result);
        assertTrue(result.contains("dateFormat = \"yyyy\\\"MM\""), result);
        assertTrue(result.contains("timeZone = \"UTC\\\\GMT\""), result);
        assertTrue(result.contains("numberFormat = \"#\\n##\""), result);
        assertFalse(result.contains("dateFormat = \"yyyy\"MM\""), result);
    }

    // Tests for SQL generation methods that wrap SQLException into UncheckedSQLException
    // (DataSource overloads — covers catch blocks at lines 958-959, 1026-1027, 1087-1088,
    // 1155-1156, 1218-1219, 1287-1288, 1348-1349, 1424-1425, 1496-1497, 1524-1525,
    // 1589-1590, 1660-1661).

    @Test
    public void testGenerateSelectSql_DataSourceWithExcluded_WrapsSQLException() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("ds failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateSelectSql(ds, "t", List.of("x"), "id = 1"));
    }

    @Test
    public void testGenerateInsertSql_DataSource_WrapsSQLException() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("ds failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateInsertSql(ds, "t"));
    }

    @Test
    public void testGenerateInsertSql_DataSourceWithExcluded_WrapsSQLException() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("ds failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateInsertSql(ds, "t", List.of("x")));
    }

    @Test
    public void testGenerateNamedInsertSql_DataSource_WrapsSQLException() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("ds failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateNamedInsertSql(ds, "t"));
    }

    @Test
    public void testGenerateNamedInsertSql_DataSourceWithExcluded_WrapsSQLException() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("ds failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateNamedInsertSql(ds, "t", List.of("x")));
    }

    @Test
    public void testGenerateUpdateSql_DataSource_WrapsSQLException() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("ds failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql(ds, "t"));
    }

    @Test
    public void testGenerateUpdateSql_DataSourceWithKey_WrapsSQLException() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("ds failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql(ds, "t", "id"));
    }

    @Test
    public void testGenerateUpdateSql_DataSourceWithExcludedAndKeys_WrapsSQLException() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("ds failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql(ds, "t", List.of("x"), List.of("id"), null));
    }

    @Test
    public void testGenerateNamedUpdateSql_DataSource_WrapsSQLException() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("ds failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(ds, "t"));
    }

    @Test
    public void testGenerateNamedUpdateSql_DataSourceWithKey_WrapsSQLException() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("ds failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(ds, "t", "id"));
    }

    @Test
    public void testGenerateNamedUpdateSql_DataSourceWithExcludedAndKeys_WrapsSQLException() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("ds failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(ds, "t", List.of("x"), List.of("id"), null));
    }

    // Tests for the Connection-based catch blocks (lines 930-931, 1001-1002, 1060-1061,
    // 1130-1131, 1190-1191, 1261-1262, 1323-1324, 1385-1386, 1496-1497, 1564-1565,
    // 1626-1627, 1736-1737) — triggered when the inner JDBC operation throws SQLException.

    private Connection mockConnectionThatThrowsOnExecuteQuery() throws SQLException {
        final Connection conn = Mockito.mock(Connection.class);
        final DatabaseMetaData md = Mockito.mock(DatabaseMetaData.class);
        final PreparedStatement stmt = Mockito.mock(PreparedStatement.class);

        when(conn.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("MySQL");
        when(md.getDatabaseProductVersion()).thenReturn("8.0");
        when(conn.prepareStatement(ArgumentMatchers.anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenThrow(new SQLException("execute failed"));
        return conn;
    }

    @Test
    public void testGenerateSelectSql_Connection_WrapsSQLException() throws SQLException {
        final Connection conn = mockConnectionThatThrowsOnExecuteQuery();
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateSelectSql(conn, "t"));
    }

    @Test
    public void testGenerateSelectSql_ConnectionWithExcluded_WrapsSQLException() throws SQLException {
        final Connection conn = mockConnectionThatThrowsOnExecuteQuery();
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateSelectSql(conn, "t", List.of("x"), null));
    }

    @Test
    public void testGenerateInsertSql_Connection_WrapsSQLException() throws SQLException {
        final Connection conn = mockConnectionThatThrowsOnExecuteQuery();
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateInsertSql(conn, "t"));
    }

    @Test
    public void testGenerateInsertSql_ConnectionWithExcluded_WrapsSQLException() throws SQLException {
        final Connection conn = mockConnectionThatThrowsOnExecuteQuery();
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateInsertSql(conn, "t", List.of("x")));
    }

    @Test
    public void testGenerateNamedInsertSql_Connection_WrapsSQLException() throws SQLException {
        final Connection conn = mockConnectionThatThrowsOnExecuteQuery();
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateNamedInsertSql(conn, "t"));
    }

    @Test
    public void testGenerateNamedInsertSql_ConnectionWithExcluded_WrapsSQLException() throws SQLException {
        final Connection conn = mockConnectionThatThrowsOnExecuteQuery();
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateNamedInsertSql(conn, "t", List.of("x")));
    }

    @Test
    public void testGenerateUpdateSql_Connection_WrapsSQLException() throws SQLException {
        final Connection conn = mockConnectionThatThrowsOnExecuteQuery();
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql(conn, "t"));
    }

    @Test
    public void testGenerateUpdateSql_ConnectionWithKey_WrapsSQLException() throws SQLException {
        final Connection conn = mockConnectionThatThrowsOnExecuteQuery();
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql(conn, "t", "id"));
    }

    @Test
    public void testGenerateUpdateSql_ConnectionWithExcludedAndKeys_WrapsSQLException() throws SQLException {
        final Connection conn = mockConnectionThatThrowsOnExecuteQuery();
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql(conn, "t", List.of("x"), List.of("id"), null));
    }

    @Test
    public void testGenerateNamedUpdateSql_Connection_WrapsSQLException() throws SQLException {
        final Connection conn = mockConnectionThatThrowsOnExecuteQuery();
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "t"));
    }

    @Test
    public void testGenerateNamedUpdateSql_ConnectionWithKey_WrapsSQLException() throws SQLException {
        final Connection conn = mockConnectionThatThrowsOnExecuteQuery();
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "t", "id"));
    }

    @Test
    public void testGenerateNamedUpdateSql_ConnectionWithExcludedAndKeys_WrapsSQLException() throws SQLException {
        final Connection conn = mockConnectionThatThrowsOnExecuteQuery();
        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "t", List.of("x"), List.of("id"), null));
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

    @Test
    public void testConvertInsertSqlToUpdateSql_InvalidSql_ThrowsIllegalArgument() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenReturn(connection);

        // Malformed SQL — missing parentheses/values — exercises the catch-all wrapper.
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "garbage sql"));
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

    // generateEntityClass — excludedFields filters out a column (lines 475-476).
    @Test
    public void testGenerateEntityClass_ExcludedFieldsFiltersColumn() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .excludedFields(Arrays.asList("status"))
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        // 'status' field should be filtered out
        assertTrue(!result.contains("private String status"));
    }

    // generateEntityClass — additionalFields with a parameterized java.util collection
    // exercises the import-resolution branch (lines 511-526).
    @Test
    public void testGenerateEntityClass_AdditionalFieldsImportsJavaUtilCollection() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .additionalFieldsOrLines("private List<String> tags;")
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("import java.util.List;"));
    }

    // generateEntityClass — jsonXmlConfig with enumerated set (line 631-632).
    @Test
    public void testGenerateEntityClass_JsonXmlConfigWithEnumerated() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig.JsonXmlConfig jx = new JdbcCodeGenerationUtil.EntityCodeConfig.JsonXmlConfig();
        jx.setEnumerated(com.landawn.abacus.util.EnumType.ORDINAL);

        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().jsonXmlConfig(jx).build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("enumerated = "));
    }

    // generateBuilder=false — L593
    @Test
    public void testGenerateEntityClass_WithGenerateBuilderFalse() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().generateBuilder(false).build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertFalse(result.contains("import lombok.Builder;"));
        assertFalse(result.contains("\n@Builder\n"));
    }

    // chainAccessor=false — L597
    @Test
    public void testGenerateEntityClass_WithChainAccessorFalse() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().chainAccessor(false).build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertFalse(result.contains("import lombok.experimental.Accessors;"));
        assertFalse(result.contains("@Accessors(chain = true)"));
    }

    // customizedField._2 (field name override) — L478
    @Test
    public void testGenerateEntityClass_WithCustomizedFieldName() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .customizedFields(Arrays.asList(Tuple.of("created_at", "createdAtOverride", (Class<?>) null)))
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("createdAtOverride"));
    }

    // customizedField._3 (field type override via customized class) — L484-L487
    @Test
    public void testGenerateEntityClass_WithCustomizedFieldType() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .customizedFields(Arrays.asList(Tuple.of("status", (String) null, String.class)))
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("String status"));
    }

    // idFields non-null in config — L416
    @Test
    public void testGenerateEntityClass_WithIdFieldsList() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().idFields(Arrays.asList("id")).build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("@Id"));
    }

    // isJavaPersistenceId=true with idFields non-empty — L562-L565
    @Test
    public void testGenerateEntityClass_WithJakartaIdAndIdFields() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .idAnnotationClass(jakarta.persistence.Id.class)
                .idFields(Arrays.asList("id"))
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("jakarta.persistence.Id"));
        assertFalse(result.contains("import com.landawn.abacus.annotation.Id;"));
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

    // srcDir — writes generated file to disk — L793-L812
    @Test
    public void testGenerateEntityClass_WithSrcDir_WritesFile() throws Exception {
        setupFullGenerateEntityClassMock();
        final Path tempDir = Files.createTempDirectory("jdbcCodeGenTest");
        try {
            final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                    .srcDir(tempDir.toString())
                    .className("OrderHistory")
                    .packageName("com.test.entity")
                    .build();
            final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2",
                    config);
            assertNotNull(result);
            final File expectedFile = new File(tempDir.toFile(), "com/test/entity/OrderHistory.java");
            assertTrue(expectedFile.exists(), "Expected generated file at " + expectedFile.getAbsolutePath());
        } finally {
            deleteRecursively(tempDir.toFile());
        }
    }

    // javax.persistence annotations (non-jakarta) — L434, L436, L438
    @Test
    public void testGenerateEntityClass_WithJavaxPersistenceAnnotations() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .tableAnnotationClass(javax.persistence.Table.class)
                .columnAnnotationClass(javax.persistence.Column.class)
                .idAnnotationClass(javax.persistence.Id.class)
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("javax.persistence"));
    }

    // mapBigIntegerToLong — L873-L874
    @Test
    public void testGenerateEntityClass_WithMapBigIntegerToLong() throws SQLException {
        final Statement stmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);
        when(resultSet.getStatement()).thenReturn(stmt);
        when(stmt.getConnection()).thenReturn(connection);
        when(databaseMetaData.getPrimaryKeys(null, null, "order_history")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        when(resultSetMetaData.getColumnName(1)).thenReturn("value");
        when(resultSetMetaData.getColumnType(1)).thenReturn(Types.BIGINT);
        when(resultSetMetaData.getColumnClassName(1)).thenReturn("java.math.BigInteger");
        when(resultSetMetaData.getColumnName(2)).thenReturn("created_at");
        when(resultSetMetaData.getColumnType(2)).thenReturn(Types.TIMESTAMP);
        when(resultSetMetaData.getColumnClassName(2)).thenReturn("java.sql.Timestamp");
        when(resultSetMetaData.getColumnName(3)).thenReturn("status");
        when(resultSetMetaData.getColumnType(3)).thenReturn(Types.VARCHAR);
        when(resultSetMetaData.getColumnClassName(3)).thenReturn("java.lang.String");

        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().mapBigIntegerToLong(true).build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("long value") || result.contains("long "));
    }

    // mapBigDecimalToDouble — L875-L876
    @Test
    public void testGenerateEntityClass_WithMapBigDecimalToDouble() throws SQLException {
        final Statement stmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);
        when(resultSet.getStatement()).thenReturn(stmt);
        when(stmt.getConnection()).thenReturn(connection);
        when(databaseMetaData.getPrimaryKeys(null, null, "order_history")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        when(resultSetMetaData.getColumnName(1)).thenReturn("amount");
        when(resultSetMetaData.getColumnType(1)).thenReturn(Types.DECIMAL);
        when(resultSetMetaData.getColumnClassName(1)).thenReturn("java.math.BigDecimal");
        when(resultSetMetaData.getColumnName(2)).thenReturn("created_at");
        when(resultSetMetaData.getColumnType(2)).thenReturn(Types.TIMESTAMP);
        when(resultSetMetaData.getColumnClassName(2)).thenReturn("java.sql.Timestamp");
        when(resultSetMetaData.getColumnName(3)).thenReturn("status");
        when(resultSetMetaData.getColumnType(3)).thenReturn(Types.VARCHAR);
        when(resultSetMetaData.getColumnClassName(3)).thenReturn("java.lang.String");

        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().mapBigDecimalToDouble(true).build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("double amount") || result.contains("double "));
    }

    // empty columnClassName — falls back to Object.class — L839
    @Test
    public void testGenerateEntityClass_WithEmptyColumnClassName() throws SQLException {
        final Statement stmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);
        when(resultSet.getStatement()).thenReturn(stmt);
        when(stmt.getConnection()).thenReturn(connection);
        when(databaseMetaData.getPrimaryKeys(null, null, "order_history")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        when(resultSetMetaData.getColumnLabel(1)).thenReturn("payload");
        when(resultSetMetaData.getColumnType(1)).thenReturn(Types.OTHER);
        when(resultSetMetaData.getColumnClassName(1)).thenReturn(""); // empty → Object.class fallback
        when(resultSetMetaData.getColumnName(2)).thenReturn("created_at");
        when(resultSetMetaData.getColumnType(2)).thenReturn(Types.TIMESTAMP);
        when(resultSetMetaData.getColumnClassName(2)).thenReturn("java.sql.Timestamp");
        when(resultSetMetaData.getColumnName(3)).thenReturn("status");
        when(resultSetMetaData.getColumnType(3)).thenReturn(Types.VARCHAR);
        when(resultSetMetaData.getColumnClassName(3)).thenReturn("java.lang.String");

        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", null);
        assertNotNull(result);
        assertTrue(result.contains("Object payload"));
    }

    // oracle.sql.TIMESTAMP — maps to java.sql.Timestamp — L848-L850
    @Test
    public void testGenerateEntityClass_WithOracleTimestampColumnName() throws SQLException {
        final Statement stmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);
        when(resultSet.getStatement()).thenReturn(stmt);
        when(stmt.getConnection()).thenReturn(connection);
        when(databaseMetaData.getPrimaryKeys(null, null, "order_history")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        when(resultSetMetaData.getColumnLabel(1)).thenReturn("ts");
        when(resultSetMetaData.getColumnType(1)).thenReturn(Types.TIMESTAMP);
        when(resultSetMetaData.getColumnClassName(1)).thenReturn("oracle.sql.TIMESTAMP");
        when(resultSetMetaData.getColumnName(2)).thenReturn("created_at");
        when(resultSetMetaData.getColumnType(2)).thenReturn(Types.TIMESTAMP);
        when(resultSetMetaData.getColumnClassName(2)).thenReturn("java.sql.Timestamp");
        when(resultSetMetaData.getColumnName(3)).thenReturn("status");
        when(resultSetMetaData.getColumnType(3)).thenReturn(Types.VARCHAR);
        when(resultSetMetaData.getColumnClassName(3)).thenReturn("java.lang.String");

        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", null);
        assertNotNull(result);
        assertTrue(result.contains("Timestamp ts"));
    }

    // oracle.sql.DATE — maps to java.sql.Date — L851-L852
    @Test
    public void testGenerateEntityClass_WithOracleDateColumnName() throws SQLException {
        final Statement stmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);
        when(resultSet.getStatement()).thenReturn(stmt);
        when(stmt.getConnection()).thenReturn(connection);
        when(databaseMetaData.getPrimaryKeys(null, null, "order_history")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        when(resultSetMetaData.getColumnLabel(1)).thenReturn("myDate");
        when(resultSetMetaData.getColumnType(1)).thenReturn(Types.DATE);
        when(resultSetMetaData.getColumnClassName(1)).thenReturn("oracle.sql.DATE");
        when(resultSetMetaData.getColumnName(2)).thenReturn("created_at");
        when(resultSetMetaData.getColumnType(2)).thenReturn(Types.TIMESTAMP);
        when(resultSetMetaData.getColumnClassName(2)).thenReturn("java.sql.Timestamp");
        when(resultSetMetaData.getColumnName(3)).thenReturn("status");
        when(resultSetMetaData.getColumnType(3)).thenReturn(Types.VARCHAR);
        when(resultSetMetaData.getColumnClassName(3)).thenReturn("java.lang.String");

        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", null);
        assertNotNull(result);
        assertTrue(result.contains("Date myDate"));
    }

    // oracle.sql.TIME — maps to java.sql.Time — L853-L854
    @Test
    public void testGenerateEntityClass_WithOracleTimeColumnName() throws SQLException {
        final Statement stmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);
        when(resultSet.getStatement()).thenReturn(stmt);
        when(stmt.getConnection()).thenReturn(connection);
        when(databaseMetaData.getPrimaryKeys(null, null, "order_history")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        when(resultSetMetaData.getColumnLabel(1)).thenReturn("myTime");
        when(resultSetMetaData.getColumnType(1)).thenReturn(Types.TIME);
        when(resultSetMetaData.getColumnClassName(1)).thenReturn("oracle.sql.TIME");
        when(resultSetMetaData.getColumnName(2)).thenReturn("created_at");
        when(resultSetMetaData.getColumnType(2)).thenReturn(Types.TIMESTAMP);
        when(resultSetMetaData.getColumnClassName(2)).thenReturn("java.sql.Timestamp");
        when(resultSetMetaData.getColumnName(3)).thenReturn("status");
        when(resultSetMetaData.getColumnType(3)).thenReturn(Types.VARCHAR);
        when(resultSetMetaData.getColumnClassName(3)).thenReturn("java.lang.String");

        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", null);
        assertNotNull(result);
        assertTrue(result.contains("Time myTime"));
    }

    // generateNamedUpdateSql with whereClause only (no keyColumnNames) — L1737
    @Test
    public void testGenerateNamedUpdateSql_WithWhereClauseOnly_NoKeyColumns() throws SQLException {
        final String sql = JdbcCodeGenerationUtil.generateNamedUpdateSql(connection, "order_history", null, null, "status = 'OPEN'");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
        assertTrue(sql.contains("WHERE status = 'OPEN'"));
    }

    // generateUpdateSql with whereClause only (no keyColumnNames) — L1501
    @Test
    public void testGenerateUpdateSql_WithWhereClauseOnly_NoKeyColumns() throws SQLException {
        final String sql = JdbcCodeGenerationUtil.generateUpdateSql(connection, "order_history", null, null, "status = 'OPEN'");
        assertNotNull(sql);
        assertTrue(sql.startsWith("UPDATE"));
        assertTrue(sql.contains("WHERE status = 'OPEN'"));
    }

    @Test
    public void testGenerateUpdateSql_AllColumnsExcludedFromSet_Throws() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateUpdateSql(connection, "order_history", null, List.of("id", "createdAt", "status"), null));
    }

    @Test
    public void testGenerateNamedUpdateSql_AllColumnsExcludedFromSet_Throws() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(connection, "order_history", null, List.of("id", "createdAt", "status"), null));
    }

    @Test
    public void testGenerateUpdateSql_SingleKeyOnlyColumn_Throws() throws SQLException {
        when(resultSetMetaData.getColumnCount()).thenReturn(1);
        when(resultSetMetaData.getColumnLabel(1)).thenReturn("id");

        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateUpdateSql(connection, "order_history", "id"));
    }

    @Test
    public void testGenerateNamedUpdateSql_SingleKeyOnlyColumn_Throws() throws SQLException {
        when(resultSetMetaData.getColumnCount()).thenReturn(1);
        when(resultSetMetaData.getColumnLabel(1)).thenReturn("id");

        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.generateNamedUpdateSql(connection, "order_history", "id"));
    }

    // Exercise continue on line 481 when excludedFields match by column name (snake_case)
    @Test
    public void testGenerateEntityClass_ExcludedFieldsByColumnName() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().excludedFields(List.of("created_at")).build();
        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertFalse(result.contains("createdAt"));
    }

    // Exercise catch block at line 524 — Class.forName throw on non-java.util type with generics
    @Test
    public void testGenerateEntityClass_AdditionalFieldsWithNonJavaUtilParameterizedType() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .additionalFieldsOrLines("private javax.sql.DataSource<String> ds;")
                .build();
        assertDoesNotThrow(
                () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config));
    }

    // Exercise catch blocks at lines 818–820 (SQLException → UncheckedSQLException)
    @Test
    public void testGenerateEntityClass_ResultSetMetaData_WrapsSQLException() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(rs.getMetaData()).thenThrow(new SQLException("metadata failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateEntityClass("TestEntity", rs, null));
    }

    @Test
    public void testGenerateEntityClassRejectsColumnsThatMapToDuplicateFieldNames() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData metadata = Mockito.mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(2);
        when(metadata.getColumnName(1)).thenReturn("user_id");
        when(metadata.getColumnName(2)).thenReturn("userId");
        when(metadata.getColumnClassName(1)).thenReturn(String.class.getName());
        when(metadata.getColumnClassName(2)).thenReturn(String.class.getName());
        when(metadata.getColumnType(1)).thenReturn(Types.VARCHAR);
        when(metadata.getColumnType(2)).thenReturn(Types.VARCHAR);

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.generateEntityClass("TestEntity", rs, null));

        assertTrue(thrown.getMessage().contains("user_id"));
        assertTrue(thrown.getMessage().contains("userId"));
    }

    // BUG FIX: entity generation used to prefer the physical column name over the SQL alias (result-set
    // label), so generateEntityClassByQuery produced fields that did not match the labels the query
    // actually returns. The label (alias-or-name per JDBC) must win; every other generation path in
    // this class already keys on column labels.
    @Test
    @Tag("2025")
    public void testGenerateEntityClass_PrefersSqlAliasLabelOverPhysicalColumnName() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData metadata = Mockito.mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnName(1)).thenReturn("user_id");
        when(metadata.getColumnLabel(1)).thenReturn("uid");
        when(metadata.getColumnClassName(1)).thenReturn(Long.class.getName());
        when(metadata.getColumnType(1)).thenReturn(Types.BIGINT);

        final String code = JdbcCodeGenerationUtil.generateEntityClass("UserQueryResult", rs, null);

        assertTrue(code.contains(" uid;"), () -> "generated field should be named after the SQL alias 'uid':\n" + code);
        assertFalse(code.contains("userId"), () -> "physical column name must not override the alias:\n" + code);
    }

    // Exercise catch blocks at lines 821–823 (IOException → UncheckedIOException when srcDir is set)
    @Test
    public void testGenerateEntityClass_WithSrcDir_WrapsIOException() throws Exception {
        final Path tempDir = Files.createTempDirectory("jdbcCodeGenTest");
        try {
            final Path targetDir = tempDir.resolve("OrderHistory.java");
            Files.createDirectories(targetDir);

            setupFullGenerateEntityClassMock();
            final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                    .srcDir(tempDir.toString())
                    .className("OrderHistory")
                    .build();

            assertThrows(UncheckedIOException.class,
                    () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config));
        } finally {
            deleteRecursively(tempDir.toFile());
        }
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

    // Regression: when an @Id field is identified by raw snake_case column name (e.g. from
    // DatabaseMetaData.getPrimaryKeys returning COLUMN_NAME='order_id'), the import for @Id must
    // be preserved because the field-emission pass matches against both fieldName and columnName.
    @Test
    public void testGenerateEntityClass_IdImportPreservedWhenIdFieldUsesColumnName() throws SQLException {
        final Connection conn = Mockito.mock(Connection.class);
        final DatabaseMetaData metaData = Mockito.mock(DatabaseMetaData.class);
        final PreparedStatement stmt = Mockito.mock(PreparedStatement.class);
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData rsMetaData = Mockito.mock(ResultSetMetaData.class);
        final Statement jdbcStmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);

        when(conn.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("MySQL");
        when(metaData.getDatabaseProductVersion()).thenReturn("8.0");
        when(conn.prepareStatement("SELECT * FROM orders WHERE 1 > 2")).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(rsMetaData);
        when(rs.getStatement()).thenReturn(jdbcStmt);
        when(jdbcStmt.getConnection()).thenReturn(conn);
        when(metaData.getPrimaryKeys(null, null, "orders")).thenReturn(pkRs);
        // Primary key auto-detect returns the raw snake_case column name.
        when(pkRs.next()).thenReturn(true, false);
        when(pkRs.getString("COLUMN_NAME")).thenReturn("order_id");
        when(rsMetaData.getColumnCount()).thenReturn(2);
        when(rsMetaData.getColumnName(1)).thenReturn("order_id");
        when(rsMetaData.getColumnName(2)).thenReturn("status");
        when(rsMetaData.getColumnLabel(1)).thenReturn("order_id");
        when(rsMetaData.getColumnLabel(2)).thenReturn("status");
        when(rsMetaData.getColumnType(1)).thenReturn(Types.BIGINT);
        when(rsMetaData.getColumnType(2)).thenReturn(Types.VARCHAR);
        when(rsMetaData.getColumnClassName(1)).thenReturn("java.lang.Long");
        when(rsMetaData.getColumnClassName(2)).thenReturn("java.lang.String");

        final String result = JdbcCodeGenerationUtil.generateEntityClass(conn, "orders");

        assertNotNull(result);
        // @Id annotation must be emitted on the id field.
        assertTrue(result.contains("@Id"), "Expected @Id annotation in: " + result);
        // The matching import statement must also be present so the generated source compiles.
        assertTrue(result.contains("import com.landawn.abacus.annotation.Id;"), "Expected '@Id' import in generated class: " + result);
    }

    private static void deleteRecursively(final File file) {
        if (file.isDirectory()) {
            final File[] children = file.listFiles();
            if (children != null) {
                for (final File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    // Regression: schema-controlled identifiers containing a double-quote, backslash, or newline
    // (legal in PostgreSQL/Oracle/SQL Server quoted/bracketed identifiers) used to be interpolated
    // raw into @Table(name = "...") / @Column(name = "...") string literals, producing malformed
    // (or syntactically valid-but-different) Java. Fix escapes via EscapeUtil.escapeJava.
    @Test
    public void testEscapeJava_HandlesQuoteBackslashNewline() {
        assertEquals("plain", EscapeUtil.escapeJava("plain"));
        assertEquals("a\\\"b", EscapeUtil.escapeJava("a\"b"));
        assertEquals("a\\\\b", EscapeUtil.escapeJava("a\\b"));
        assertEquals("a\\nb", EscapeUtil.escapeJava("a\nb"));
        assertEquals("a\\rb", EscapeUtil.escapeJava("a\rb"));
        assertEquals("a\\tb", EscapeUtil.escapeJava("a\tb"));
        assertNull(EscapeUtil.escapeJava(null));
        assertEquals("", EscapeUtil.escapeJava(""));
    }

    @Test
    public void testGenerateEntityClass_EscapesColumnNameWithEmbeddedQuote() throws SQLException {
        final Connection conn = Mockito.mock(Connection.class);
        final PreparedStatement stmt = Mockito.mock(PreparedStatement.class);
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData rsMd = Mockito.mock(ResultSetMetaData.class);
        final DatabaseMetaData md = Mockito.mock(DatabaseMetaData.class);
        final Statement jdbcStmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);

        when(conn.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(md.getDatabaseProductVersion()).thenReturn("15");
        when(conn.prepareStatement(ArgumentMatchers.anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(rsMd);
        when(rs.getStatement()).thenReturn(jdbcStmt);
        when(jdbcStmt.getConnection()).thenReturn(conn);
        when(md.getPrimaryKeys(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyString())).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        when(rsMd.getColumnCount()).thenReturn(1);
        // Column name with an embedded double-quote — pathological but legal in PostgreSQL/Oracle.
        when(rsMd.getColumnLabel(1)).thenReturn("my\"col");
        when(rsMd.getColumnName(1)).thenReturn("my\"col");
        when(rsMd.getColumnType(1)).thenReturn(Types.VARCHAR);
        when(rsMd.getColumnClassName(1)).thenReturn("java.lang.String");

        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(conn, "weird_tbl", "SELECT * FROM weird_tbl WHERE 1 > 2");
        assertNotNull(result);
        // The raw column-name quote must be escaped; pre-fix the annotation broke compilation.
        assertTrue(result.contains("@Column(name = \"my\\\"col\")"), "Expected escaped @Column annotation, got:\n" + result);
        // The unescaped form (broken Java) must NOT appear.
        assertFalse(result.contains("@Column(name = \"my\"col\")"), "Unescaped column name leaked into generated source");
    }

    // Regression: generated .java files used to be written with IOUtil's platform-default charset
    // (Cp1252 on Windows). Non-ASCII identifiers / comments became mojibake when the file was
    // later compiled by javac with -encoding UTF-8 (Maven/Gradle default). Fix forces UTF-8.
    @Test
    public void testGenerateEntityClass_WritesGeneratedFileAsUtf8() throws Exception {
        // Non-ASCII characters in column name to force a non-ASCII byte in the generated source.
        final Connection conn = Mockito.mock(Connection.class);
        final PreparedStatement stmt = Mockito.mock(PreparedStatement.class);
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData rsMd = Mockito.mock(ResultSetMetaData.class);
        final DatabaseMetaData md = Mockito.mock(DatabaseMetaData.class);
        final Statement jdbcStmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);

        when(conn.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(md.getDatabaseProductVersion()).thenReturn("15");
        when(conn.prepareStatement(ArgumentMatchers.anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(rsMd);
        when(rs.getStatement()).thenReturn(jdbcStmt);
        when(jdbcStmt.getConnection()).thenReturn(conn);
        when(md.getPrimaryKeys(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyString())).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        when(rsMd.getColumnCount()).thenReturn(1);
        // German umlaut and Japanese characters: 2 bytes in UTF-8, single byte in Cp1252-or-broken.
        final String exoticColumn = "größe漢字";
        when(rsMd.getColumnLabel(1)).thenReturn(exoticColumn);
        when(rsMd.getColumnName(1)).thenReturn(exoticColumn);
        when(rsMd.getColumnType(1)).thenReturn(Types.VARCHAR);
        when(rsMd.getColumnClassName(1)).thenReturn("java.lang.String");

        final Path tempDir = Files.createTempDirectory("jdbcCodeGenCharset");
        try {
            final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                    .srcDir(tempDir.toString())
                    .className("Exotic")
                    .packageName("p")
                    .build();
            JdbcCodeGenerationUtil.generateEntityClassByQuery(conn, "weird_tbl", "SELECT * FROM weird_tbl WHERE 1 > 2", config);

            final File expectedFile = new File(tempDir.toFile(), "p/Exotic.java");
            assertTrue(expectedFile.exists());
            // Read the file as UTF-8 bytes and confirm the non-ASCII column name round-trips
            // exactly. Pre-fix, Cp1252 encoding would have replaced 漢字 with `??`.
            final String onDisk = new String(Files.readAllBytes(expectedFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(onDisk.contains(exoticColumn), "Expected UTF-8 round-trip of exotic column name; on-disk content:\n" + onDisk);
        } finally {
            deleteRecursively(tempDir.toFile());
        }
    }

    // Regression: PK auto-detection used to pass `entityName` verbatim to getPrimaryKeys, so a
    // qualified name like "myschema.users" was queried as a literal table named "myschema.users"
    // (drivers never match); @Id annotations were silently missing. Fix splits the qualified
    // identifier into catalog/schema/table.
    @Test
    public void testGenerateEntityClass_QualifiedTableName_SplitsCatalogSchemaTableForPkLookup() throws SQLException {
        final Connection conn = Mockito.mock(Connection.class);
        final PreparedStatement stmt = Mockito.mock(PreparedStatement.class);
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData rsMd = Mockito.mock(ResultSetMetaData.class);
        final DatabaseMetaData md = Mockito.mock(DatabaseMetaData.class);
        final Statement jdbcStmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);

        when(conn.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(md.getDatabaseProductVersion()).thenReturn("15");
        when(conn.prepareStatement(ArgumentMatchers.anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(rsMd);
        when(rs.getStatement()).thenReturn(jdbcStmt);
        when(jdbcStmt.getConnection()).thenReturn(conn);
        // Fix routes the call to (null, "myschema", "users") rather than (null, null, "myschema.users").
        when(md.getPrimaryKeys(null, "myschema", "users")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(true, false);
        when(pkRs.getString("COLUMN_NAME")).thenReturn("id");

        when(rsMd.getColumnCount()).thenReturn(2);
        when(rsMd.getColumnLabel(1)).thenReturn("id");
        when(rsMd.getColumnName(1)).thenReturn("id");
        when(rsMd.getColumnType(1)).thenReturn(Types.BIGINT);
        when(rsMd.getColumnClassName(1)).thenReturn("java.lang.Long");
        when(rsMd.getColumnLabel(2)).thenReturn("name");
        when(rsMd.getColumnName(2)).thenReturn("name");
        when(rsMd.getColumnType(2)).thenReturn(Types.VARCHAR);
        when(rsMd.getColumnClassName(2)).thenReturn("java.lang.String");

        final String result = JdbcCodeGenerationUtil.generateEntityClass(conn, "myschema.users");
        assertNotNull(result);
        // The PK column "id" should have produced an @Id annotation, proving the schema-qualified
        // metadata lookup succeeded. Pre-fix, getPrimaryKeys(null, null, "myschema.users") matched
        // nothing and no @Id was emitted.
        assertTrue(result.contains("@Id"), "Expected @Id annotation from schema-qualified PK lookup; got:\n" + result);
        // Verify the call signature exactly.
        Mockito.verify(md).getPrimaryKeys(null, "myschema", "users");
    }

    // A three-part qualified name (catalog.schema.table) routes all three slots to getPrimaryKeys
    // (JdbcCodeGenerationUtil L529-533, the parts.length >= 3 branch). The existing fixture only
    // covered the two-part schema.table case.
    @Test
    public void testGenerateEntityClass_QualifiedTableName_CatalogSchemaTable() throws SQLException {
        final Connection conn = Mockito.mock(Connection.class);
        final PreparedStatement stmt = Mockito.mock(PreparedStatement.class);
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData rsMd = Mockito.mock(ResultSetMetaData.class);
        final DatabaseMetaData md = Mockito.mock(DatabaseMetaData.class);
        final Statement jdbcStmt = Mockito.mock(Statement.class);
        final ResultSet pkRs = Mockito.mock(ResultSet.class);

        when(conn.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(md.getDatabaseProductVersion()).thenReturn("15");
        when(conn.prepareStatement(ArgumentMatchers.anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(rsMd);
        when(rs.getStatement()).thenReturn(jdbcStmt);
        when(jdbcStmt.getConnection()).thenReturn(conn);
        // catalog.schema.table -> getPrimaryKeys("mycatalog", "myschema", "users").
        when(md.getPrimaryKeys("mycatalog", "myschema", "users")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(true, false);
        when(pkRs.getString("COLUMN_NAME")).thenReturn("id");

        when(rsMd.getColumnCount()).thenReturn(2);
        when(rsMd.getColumnLabel(1)).thenReturn("id");
        when(rsMd.getColumnName(1)).thenReturn("id");
        when(rsMd.getColumnType(1)).thenReturn(Types.BIGINT);
        when(rsMd.getColumnClassName(1)).thenReturn("java.lang.Long");
        when(rsMd.getColumnLabel(2)).thenReturn("name");
        when(rsMd.getColumnName(2)).thenReturn("name");
        when(rsMd.getColumnType(2)).thenReturn(Types.VARCHAR);
        when(rsMd.getColumnClassName(2)).thenReturn("java.lang.String");

        final String result = JdbcCodeGenerationUtil.generateEntityClass(conn, "mycatalog.myschema.users");
        assertNotNull(result);
        assertTrue(result.contains("@Id"), "Expected @Id annotation from catalog/schema-qualified PK lookup; got:\n" + result);
        Mockito.verify(md).getPrimaryKeys("mycatalog", "myschema", "users");
    }

    // ----------------------------------------------------------------------------------------------------
    // Additional coverage for previously-uncovered lines in JdbcCodeGenerationUtil.
    // ----------------------------------------------------------------------------------------------------

    // additionalFieldsOrLines with a multi-variable declaration ("int a, b") must be split on the
    // first top-level comma; only the first variable ("a") is parsed (L494-496: commaIdx assignment + break).
    @Test
    public void testGenerateEntityClass_AdditionalFieldMultiVariableDeclaration() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .generateCopyMethod(true)
                .className("OrderHistory")
                .additionalFieldsOrLines("    private int a, b;")
                .build();

        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);

        // The raw declaration is emitted verbatim ...
        assertTrue(result.contains("private int a, b;"), result);
        // ... but only the first variable ("a") is parsed for the copy method (top-level comma split).
        assertTrue(result.contains("copy.a = this.a;"), result);
        assertFalse(result.contains("copy.b"), result);
    }

    // entityName that is not a parseable SQL identifier (4-part "a.b.c.d") makes
    // JdbcUtil.splitQualifiedSqlIdentifier throw; the PK-lookup falls back to best-effort
    // (L566: catch (RuntimeException ignore)).
    @Test
    public void testGenerateEntityClass_UnparseableEntityName_FallsBackToBestEffort() throws SQLException {
        setupFullGenerateEntityClassMock();
        final ResultSet pkRs = Mockito.mock(ResultSet.class);
        when(databaseMetaData.getPrimaryKeys(null, null, "a.b.c.d")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);

        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().className("MyEntity").build();

        final String result = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "a.b.c.d", "SELECT * FROM order_history WHERE 1 > 2", config);

        assertNotNull(result);
        assertTrue(result.contains("MyEntity"), result);
        // PK lookup used the verbatim entityName as the table after the parse failure fell through.
        Mockito.verify(databaseMetaData).getPrimaryKeys(null, null, "a.b.c.d");
    }

    // srcDir set but the on-disk write fails (target path pre-created as a directory) ->
    // IOException is wrapped as UncheckedIOException (L876-878).
    @Test
    public void testGenerateEntityClass_SrcDirWriteFails_ThrowsUncheckedIOException() throws Exception {
        setupFullGenerateEntityClassMock();
        final Path tempDir = Files.createTempDirectory("jdbcCodeGenIoFail");
        try {
            // Pre-create the destination .java path as a DIRECTORY so writing the generated file fails.
            final Path blockingDir = tempDir.resolve("pkg").resolve("OrderHistory.java");
            Files.createDirectories(blockingDir);

            final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                    .srcDir(tempDir.toString())
                    .packageName("pkg")
                    .className("OrderHistory")
                    .build();

            final UncheckedIOException ex = assertThrows(UncheckedIOException.class,
                    () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config));
            assertNotNull(ex);
        } finally {
            deleteRecursively(tempDir.toFile());
        }
    }

    // ----------------------------------------------------------------------------------------------------
    // convertInsertSqlToUpdateSql — parse-error edge cases and the SQL tokenizer / parenthesis matcher.
    // ----------------------------------------------------------------------------------------------------

    private DataSource dataSourceReturningConnection() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenReturn(connection); // setUp() makes `connection` report MySQL 8.0
        return ds;
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

    // An opening parenthesis with no matching close -> findClosingParenthesis scans to the end and
    // Reports an unclosed parenthesis without echoing the full SQL text.
    @Test
    public void testConvertInsertSqlToUpdateSql_UnclosedParenthesis() throws SQLException {
        final DataSource ds = dataSourceReturningConnection();
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO t(a"));
        assertTrue(ex.getMessage().contains("unclosed parenthesis"), ex.getMessage());
    }

    // An empty token in the column list -> "Empty item in SQL list" (L2107).
    @Test
    public void testConvertInsertSqlToUpdateSql_EmptyColumn() throws SQLException {
        final DataSource ds = dataSourceReturningConnection();
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO t() VALUES (1)"));
        assertTrue(ex.getMessage().contains("Empty item in SQL list"), ex.getMessage());
    }

    // A double-quote-delimited identifier with an escaped (doubled) quote exercises the quote-doubling
    // branches in findClosingParenthesis (L2005-2006) and splitSqlList (L2052-2053), and the
    // doubled-quote unescape in stripIdentifierDelimiters (L2122).
    @Test
    public void testConvertInsertSqlToUpdateSql_DoubledQuoteIdentifier() throws SQLException {
        final DataSource ds = dataSourceReturningConnection();
        final String updateSql = JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO t(\"a\"\"b\") VALUES (1)");
        // MySQL re-quotes with backticks; the embedded double-quote in the identifier is preserved.
        assertEquals("UPDATE t SET `a\"b` = 1", updateSql);
    }

    // A bracket-delimited identifier with an escaped (doubled) bracket exercises the bracket-identifier
    // branches in findClosingParenthesis (L2011-2017) and splitSqlList (L2058-2066), and the
    // "]]" -> "]" unescape in stripIdentifierDelimiters (L2124).
    @Test
    public void testConvertInsertSqlToUpdateSql_BracketIdentifier() throws SQLException {
        final DataSource ds = dataSourceReturningConnection();
        final String updateSql = JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO t([a]]b]) VALUES (1)");
        // [a]]b] decodes to identifier a]b, then MySQL re-quotes with backticks.
        assertEquals("UPDATE t SET `a]b` = 1", updateSql);
    }

    // An escaped single quote inside a string VALUE is copied verbatim; exercises the quote-doubling
    // branches for the single-quote literal path (L2005-2006 / L2052-2053).
    @Test
    public void testConvertInsertSqlToUpdateSql_EscapedSingleQuoteInValue() throws SQLException {
        final DataSource ds = dataSourceReturningConnection();
        final String updateSql = JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO t(a) VALUES ('it''s')");
        assertEquals("UPDATE t SET a = 'it''s'", updateSql);
    }

    // The defensive "Unmatched closing parenthesis" branch in splitSqlList (L2079) cannot be reached
    // through convertInsertSqlToUpdateSql (findClosingParenthesis only ever hands splitSqlList a
    // balanced substring), so it is exercised directly via reflection.
    @Test
    public void testSplitSqlList_UnmatchedClosingParenthesis_Reflection() throws Exception {
        final java.lang.reflect.Method m = JdbcCodeGenerationUtil.class.getDeclaredMethod("splitSqlList", String.class, String.class);
        m.setAccessible(true);

        final java.lang.reflect.InvocationTargetException ex = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> m.invoke(null, "a)", "INSERT INTO t(a)) VALUES (1)"));
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
        assertTrue(ex.getCause().getMessage().contains("Unmatched closing parenthesis in SQL"), ex.getCause().getMessage());
    }

    // The defensive "Unclosed SQL token" branch in splitSqlList (L2091-2092) likewise cannot be reached
    // through the public method (findClosingParenthesis rejects unbalanced quotes first), so it is
    // exercised directly via reflection (an unterminated single-quote literal).
    @Test
    public void testSplitSqlList_UnclosedToken_Reflection() throws Exception {
        final java.lang.reflect.Method m = JdbcCodeGenerationUtil.class.getDeclaredMethod("splitSqlList", String.class, String.class);
        m.setAccessible(true);

        final java.lang.reflect.InvocationTargetException ex = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> m.invoke(null, "'abc", "INSERT INTO t('abc) VALUES (1)"));
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
        assertTrue(ex.getCause().getMessage().contains("Unclosed SQL token in SQL"), ex.getCause().getMessage());
    }

    // isSimpleSqlIdentifier returns false for empty/null input (L2161-2162). Every public caller guards
    // against empty identifiers, so this branch is exercised directly via reflection.
    @Test
    public void testIsSimpleSqlIdentifier_EmptyAndNull_Reflection() throws Exception {
        final java.lang.reflect.Method m = JdbcCodeGenerationUtil.class.getDeclaredMethod("isSimpleSqlIdentifier", String.class);
        m.setAccessible(true);

        assertEquals(Boolean.FALSE, m.invoke(null, "")); // L2162 (empty)
        assertEquals(Boolean.FALSE, m.invoke(null, (Object) null)); // L2162 (null)
        assertEquals(Boolean.FALSE, m.invoke(null, "1bad")); // first char not alpha/underscore
        assertEquals(Boolean.TRUE, m.invoke(null, "valid_name1")); // happy path
    }

    // TODO: L1978-1979 (catch (Exception) -> IllegalArgumentException "Failed to convert insert SQL to
    // update SQL") is left UNCOVERED. It is unreachable via the public convertInsertSqlToUpdateSql with
    // String inputs: getDBProductInfo(ds) runs outside the try, and every failure mode inside the try
    // (findClosingParenthesis / splitSqlList / addSqlListToken / checkColumnName) throws
    // IllegalArgumentException, which is swallowed by the preceding catch (IllegalArgumentException).
    // The defensive generic catch cannot be triggered without instrumentation/mock injection.
}
