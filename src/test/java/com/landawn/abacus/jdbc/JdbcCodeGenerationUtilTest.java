package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.util.NamingPolicy;
import com.landawn.abacus.util.Tuple;

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
        when(conn.prepareStatement("SELECT * FROM `sales`.`order-history` WHERE 1 > 2")).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(rsMetaData);
        when(rsMetaData.getColumnCount()).thenReturn(2);
        when(rsMetaData.getColumnLabel(1)).thenReturn("id");
        when(rsMetaData.getColumnLabel(2)).thenReturn("created_at");

        final String sql = JdbcCodeGenerationUtil.generateSelectSql(conn, "sales.order-history");

        assertEquals("SELECT id, created_at FROM `sales`.`order-history`", sql);
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
        String result = JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2");
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
        String result = JdbcCodeGenerationUtil.generateEntityClass(dataSource, "order_history", "SELECT * FROM order_history WHERE 1 > 2", null);
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
        String result = JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("package com.example.model"));
    }

    // Test generateEntityClass with idField config
    @Test
    public void testGenerateEntityClass_WithIdField() throws SQLException {
        setupFullGenerateEntityClassMock();
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().idField("id").build();
        String result = JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("@Id"));
    }

    // Test generateEntityClass with readOnlyFields config
    @Test
    public void testGenerateEntityClass_WithReadOnlyFields() throws SQLException {
        setupFullGenerateEntityClassMock();
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().readOnlyFields(Arrays.asList("created_at")).build();
        String result = JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("@ReadOnly"));
    }

    // Test generateEntityClass with excludedFields config
    @Test
    public void testGenerateEntityClass_WithExcludedFields() throws SQLException {
        setupFullGenerateEntityClassMock();
        JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().excludedFields(Arrays.asList("status")).build();
        String result = JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
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
        String result = JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
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
                () -> JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config));
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

        String result = JdbcCodeGenerationUtil.generateEntityClass(dataSource, "MyEntity", "SELECT * FROM v WHERE 1 > 2");
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

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateEntityClass(dataSource, "MyEntity", "SELECT * FROM t WHERE 1=2", null));
    }

    // generateEntityClass(Connection, entityName, query, config) wraps SQLException from prepareStatement (L385-386)
    @Test
    public void testGenerateEntityClass_ConnectionEntityNameQueryConfig_WrapsSQLException() throws SQLException {
        final Connection conn = Mockito.mock(Connection.class);
        when(conn.prepareStatement(ArgumentMatchers.anyString())).thenThrow(new SQLException("prepare failed"));

        assertThrows(UncheckedSQLException.class, () -> JdbcCodeGenerationUtil.generateEntityClass(conn, "MyEntity", "SELECT * FROM t WHERE 1=2", null));
    }

    // generateEntityClass with custom tableAnnotationClass and columnAnnotationClass (L418, L421)
    @Test
    public void testGenerateEntityClass_WithCustomAnnotationClasses() throws SQLException {
        setupFullGenerateEntityClassMock();

        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .tableAnnotationClass(jakarta.persistence.Table.class)
                .columnAnnotationClass(jakarta.persistence.Column.class)
                .build();

        final String result = JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
    }

    // generateEntityClass with additionalFieldsOrLines set (L444-455)
    @Test
    public void testGenerateEntityClass_WithAdditionalFieldsOrLines() throws SQLException {
        setupFullGenerateEntityClassMock();

        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .additionalFieldsOrLines("private String extraField; // extra\nprivate int extraCount;")
                .build();

        final String result = JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
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
        final String result = JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("import com.example.MyAnnotation;"));
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
        final String result = JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
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
        final String result = JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
    }

    // nonUpdatableFields marks a field @NonUpdatable (L661)
    @Test
    public void testGenerateEntityClass_WithNonUpdatableField() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .nonUpdatableFields(Arrays.asList("status"))
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
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
        final String result = JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
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
        final String result = JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("copy()"));
    }

    // generateFieldNameTable=true generates X interface with property name constants (L722-751)
    @Test
    public void testGenerateEntityClass_WithFieldNameTable() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder().generateFieldNameTable(true).build();
        final String result = JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
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
        final String result = JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("@JsonXmlConfig"));
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
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "INSERT INTO t(a,b) VALUES (1)"));
    }

    @Test
    public void testConvertInsertSqlToUpdateSql_InvalidSql_ThrowsIllegalArgument() throws SQLException {
        final DataSource ds = Mockito.mock(DataSource.class);
        when(ds.getConnection()).thenReturn(connection);

        // Malformed SQL — missing parentheses/values — exercises the catch-all wrapper.
        assertThrows(IllegalArgumentException.class, () -> JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(ds, "garbage sql"));
    }

    // generateEntityClass — excludedFields filters out a column (lines 475-476).
    @Test
    public void testGenerateEntityClass_ExcludedFieldsFiltersColumn() throws SQLException {
        setupFullGenerateEntityClassMock();
        final JdbcCodeGenerationUtil.EntityCodeConfig config = JdbcCodeGenerationUtil.EntityCodeConfig.builder()
                .excludedFields(Arrays.asList("status"))
                .build();
        final String result = JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
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
        final String result = JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
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
        final String result = JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history", "SELECT * FROM order_history WHERE 1 > 2", config);
        assertNotNull(result);
        assertTrue(result.contains("enumerated = "));
    }
}
