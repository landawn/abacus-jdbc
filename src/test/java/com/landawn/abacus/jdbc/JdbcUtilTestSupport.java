package com.landawn.abacus.jdbc;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.util.function.TriConsumer;
import com.landawn.abacus.util.Throwables;

abstract class JdbcUtilTestSupport extends TestBase {

    // TODO: The remaining JdbcUtil overload matrix includes close/skip helpers and large-result query adapters whose
    // behavior is mostly exercised through shared internals. Add focused fixture-based tests for any uncovered branches.

    protected DataSource mockDataSource;

    protected Connection mockConnection;

    protected PreparedStatement mockPreparedStatement;

    protected CallableStatement mockCallableStatement;

    protected ResultSet mockResultSet;

    protected ResultSetMetaData mockResultSetMetaData;

    protected DatabaseMetaData mockDatabaseMetaData;

    protected Statement mockStatement;

    protected Blob mockBlob;

    protected Clob mockClob;

    private boolean previousSqlLogAllowed;
    private boolean previousSqlPerfLogAllowed;
    private boolean previousDaoMethodPerfLogAllowed;
    private SqlLogConfig previousSqlLogConfig;
    private SqlLogConfig previousSqlPerfLogConfig;
    private Throwables.Function<Statement, String, SQLException> previousSqlExtractor;
    private TriConsumer<String, Long, Long> previousSqlLogHandler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        previousSqlLogAllowed = JdbcUtil.isSqlLogAllowed;
        previousSqlPerfLogAllowed = JdbcUtil.isSqlPerfLogAllowed;
        previousDaoMethodPerfLogAllowed = JdbcUtil.isDaoMethodPerfLogAllowed;
        previousSqlLogConfig = JdbcUtil.isSQLLogEnabled_TL.get();
        previousSqlPerfLogConfig = JdbcUtil.sqlPerfLogThresholdMillis_TL.get();
        previousSqlExtractor = JdbcUtil.getSqlExtractor();
        previousSqlLogHandler = JdbcUtil.getSqlLogHandler();
        JdbcUtil.isSqlLogAllowed = true;
        JdbcUtil.isSqlPerfLogAllowed = true;
        JdbcUtil.isDaoMethodPerfLogAllowed = true;
        JdbcUtil.isSQLLogEnabled_TL.set(new SqlLogConfig(false, JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH));
        JdbcUtil.sqlPerfLogThresholdMillis_TL.set(new SqlLogConfig(JdbcUtil.DEFAULT_SQL_PERF_LOG_THRESHOLD_MILLIS, JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH));

        // Reset the static resultSetClassNotSupportAbsolute set so tests don't pollute each other
        final Field rsNoAbsoluteField = JdbcUtil.class.getDeclaredField("resultSetClassNotSupportAbsolute");
        rsNoAbsoluteField.setAccessible(true);
        ((Set<?>) rsNoAbsoluteField.get(null)).clear();
        resetSqlLogHandler();
        JdbcUtil.setSqlExtractor(JdbcUtil.DEFAULT_SQL_EXTRACTOR);

        mockDataSource = mock(DataSource.class);
        mockConnection = mock(Connection.class);
        mockPreparedStatement = mock(PreparedStatement.class);
        mockCallableStatement = mock(CallableStatement.class);
        mockResultSet = mock(ResultSet.class);
        mockResultSetMetaData = mock(ResultSetMetaData.class);
        mockDatabaseMetaData = mock(DatabaseMetaData.class);
        mockStatement = mock(Statement.class);
        mockBlob = mock(Blob.class);
        mockClob = mock(Clob.class);

        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("8");
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement);
        when(mockConnection.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(mockPreparedStatement);
        when(mockConnection.prepareCall(anyString())).thenReturn(mockCallableStatement);
        when(mockConnection.getMetaData()).thenReturn(mockDatabaseMetaData);
        when(mockPreparedStatement.executeQuery()).thenReturn(mockResultSet);
        when(mockResultSet.getMetaData()).thenReturn(mockResultSetMetaData);
    }

    @AfterEach
    public void tearDown() throws Exception {
        try {
            JdbcUtil.closeDaoCacheOnCurrentThread();
        } finally {
            JdbcUtil.isSqlLogAllowed = previousSqlLogAllowed;
            JdbcUtil.isSqlPerfLogAllowed = previousSqlPerfLogAllowed;
            JdbcUtil.isDaoMethodPerfLogAllowed = previousDaoMethodPerfLogAllowed;
            JdbcUtil.isSQLLogEnabled_TL.set(previousSqlLogConfig);
            JdbcUtil.sqlPerfLogThresholdMillis_TL.set(previousSqlPerfLogConfig);
            JdbcUtil.setSqlExtractor(previousSqlExtractor);
            setSqlLogHandler(previousSqlLogHandler);
        }
    }

    protected static void resetSqlLogHandler() throws Exception {
        setSqlLogHandler(null);
    }

    private static void setSqlLogHandler(final TriConsumer<String, Long, Long> handler) throws Exception {
        final Field sqlLogHandlerField = JdbcUtil.class.getDeclaredField("_sqlLogHandler");
        sqlLogHandlerField.setAccessible(true);
        sqlLogHandlerField.set(null, handler);
    }

    // Test entity class for various tests
    public static class TestEntity {
        private Long id;
        private String name;
        private Integer age;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }
    }
}
