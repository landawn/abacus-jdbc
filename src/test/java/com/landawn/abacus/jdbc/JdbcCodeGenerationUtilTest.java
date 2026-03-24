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
}
