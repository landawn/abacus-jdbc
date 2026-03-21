package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.util.NamingPolicy;

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

    static final class TestEntity {
    }
}
