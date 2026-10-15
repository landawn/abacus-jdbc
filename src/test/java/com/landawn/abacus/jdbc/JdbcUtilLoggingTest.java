package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.function.TriConsumer;
import com.zaxxer.hikari.pool.HikariProxyPreparedStatement;

class JdbcUtilLoggingTest extends JdbcUtilTestSupport {

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void oracleSqlExtractionUsesOriginalSqlAndFallsBackAfterDriverFailure(final boolean failed) throws SQLException {
        final oracle.jdbc.internal.OraclePreparedStatement statement = mock(oracle.jdbc.internal.OraclePreparedStatement.class);
        when(statement.toString()).thenReturn("driver fallback");
        if (failed) {
            when(statement.getOriginalSql()).thenThrow(new SQLException("original SQL unavailable"));
        } else {
            when(statement.getOriginalSql()).thenReturn("SELECT 42 FROM DUAL");
        }

        assertEquals(failed ? "driver fallback" : "SELECT 42 FROM DUAL", JdbcUtil.DEFAULT_SQL_EXTRACTOR.apply(statement));
        verify(statement).getOriginalSql();
    }

    @ParameterizedTest(name = "{0} clears parameters, logging={1}")
    @MethodSource("executionModes")
    void execute_loggingMode_preservesResultsAndClearsParameters(final ExecutionOperation operation, final boolean logging) throws SQLException {
        final List<String> loggedSql = new ArrayList<>();
        final List<Statement> extractedStatements = new ArrayList<>();
        JdbcUtil.setSqlExtractor(stmt -> {
            extractedStatements.add(stmt);
            return "SELECT 42";
        });
        JdbcUtil.setSqlPerfLogThresholdMillis(0);
        if (logging) {
            JdbcUtil.setSqlLogHandler((sql, start, end) -> loggedSql.add(sql));
        } else {
            JdbcUtil.disableSqlPerfLogGlobally();
        }
        when(mockPreparedStatement.executeUpdate()).thenReturn(3);
        when(mockPreparedStatement.executeLargeUpdate()).thenReturn((long) Integer.MAX_VALUE + 1);
        when(mockPreparedStatement.execute()).thenReturn(true);

        switch (operation) {
            case QUERY -> {
                try (ResultSet result = JdbcUtil.executeQuery(mockPreparedStatement)) {
                    assertNotNull(result);
                }
                verify(mockResultSet).close();
            }
            case UPDATE -> assertEquals(3, JdbcUtil.executeUpdate(mockPreparedStatement));
            case LARGE_UPDATE -> assertEquals((long) Integer.MAX_VALUE + 1, JdbcUtil.executeLargeUpdate(mockPreparedStatement));
            case EXECUTE -> assertTrue(JdbcUtil.execute(mockPreparedStatement));
        }

        assertEquals(logging ? List.of("SELECT 42") : List.of(), loggedSql);
        assertEquals(logging ? List.of(mockPreparedStatement) : List.of(), extractedStatements);
        verify(mockPreparedStatement).clearParameters();
    }

    @ParameterizedTest(name = "global SQL logging enabled={0}")
    @ValueSource(booleans = { false, true })
    void logSql_globalSwitch_controlsEmittedMessages(final boolean enabled) {
        final List<String> messages = new ArrayList<>();
        final AbstractAppender appender = new AbstractAppender("jdbc-unit-log-capture", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(final LogEvent event) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        };
        final Logger logger = (Logger) LogManager.getLogger("com.landawn.abacus.SQL");
        final Level previousLevel = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            JdbcUtil.enableSqlLog();
            if (!enabled) {
                JdbcUtil.disableSqlLogGlobally();
            }

            JdbcUtil.logSql("SELECT 42");

            assertEquals(enabled ? List.of("[SQL]: SELECT 42") : List.of(), messages);
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test
    public void testSqlExtractorFailureDoesNotMaskDatabaseFailure() throws SQLException {
        JdbcUtil.setSqlLogHandler((sql, start, end) -> {
            // Enable the SQL logging path.
        });
        JdbcUtil.setSqlExtractor(stmt -> {
            throw new SQLException("extractor failure");
        });
        when(mockPreparedStatement.executeUpdate()).thenThrow(new SQLException("database failure"));

        final SQLException thrown = assertThrows(SQLException.class, () -> JdbcUtil.executeUpdate(mockPreparedStatement));

        assertEquals("database failure", thrown.getMessage());
    }

    @Test
    public void testSqlLogHandlerFailureDoesNotFailSuccessfulExecution() throws SQLException {
        JdbcUtil.setSqlLogHandler((sql, start, end) -> {
            throw new IllegalStateException("handler failure");
        });
        when(mockPreparedStatement.executeUpdate()).thenReturn(3);

        assertEquals(3, JdbcUtil.executeUpdate(mockPreparedStatement));
    }

    @Test
    public void testTurnOffSqlLogGlobally() {
        JdbcUtil.disableSqlLogGlobally();
        assertFalse(JdbcUtil.isSqlLogAllowed);
    }

    @Test
    public void testTurnOffSqlPerfLogGlobally() {
        JdbcUtil.disableSqlPerfLogGlobally();
        assertFalse(JdbcUtil.isSqlPerfLogAllowed);
    }

    @Test
    public void testTurnOffDaoMethodPerfLogGlobally() {
        JdbcUtil.disableDaoMethodPerfLogGlobally();
        assertFalse(JdbcUtil.isDaoMethodPerfLogAllowed);
    }

    @Test
    public void testEnableSqlLog() {
        JdbcUtil.enableSqlLog();
        assertTrue(JdbcUtil.isSqlLogEnabled());

        JdbcUtil.enableSqlLog(2048);
        assertTrue(JdbcUtil.isSqlLogEnabled());
    }

    // enableSqlLog with negative maxSqlLogLength
    @Test
    public void testEnableSqlLog_NegativeMaxLength() {
        assertDoesNotThrow(() -> JdbcUtil.enableSqlLog(-1));
    }

    @Test
    public void testDisableSqlLog() {
        JdbcUtil.enableSqlLog();
        assertTrue(JdbcUtil.isSqlLogEnabled());

        JdbcUtil.disableSqlLog();
        assertFalse(JdbcUtil.isSqlLogEnabled());
    }

    @Test
    public void testIsSqlLogEnabled() {
        JdbcUtil.disableSqlLog();
        assertFalse(JdbcUtil.isSqlLogEnabled());

        JdbcUtil.enableSqlLog();
        assertTrue(JdbcUtil.isSqlLogEnabled());
    }

    @Test
    public void testHandleSqlLogUsesMonotonicElapsedTimeForHandler() throws Exception {
        final long[] timestamps = new long[2];
        final long startTimeMillis = 1_000L;
        final long startTimeNanos = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(25);

        JdbcUtil.setSqlLogHandler((sql, start, end) -> {
            timestamps[0] = start;
            timestamps[1] = end;
        });

        try {
            JdbcUtil.handleSqlLog(mockStatement, new SqlLogConfig(-1L, 128), startTimeMillis, startTimeNanos);
        } finally {
            resetSqlLogHandler();
        }

        assertEquals(startTimeMillis, timestamps[0]);
        assertTrue(timestamps[1] - timestamps[0] >= 20L);
    }

    @Test
    public void testGetSqlExtractor() {
        Throwables.Function<Statement, String, SQLException> extractor = JdbcUtil.getSqlExtractor();
        assertNotNull(extractor);
        // The default extractor should be the built-in DEFAULT_SQL_EXTRACTOR
        assertEquals(JdbcUtil.DEFAULT_SQL_EXTRACTOR, extractor, "initial extractor should be the default");
    }

    @Test
    public void testSetSqlExtractor() throws SQLException {
        Throwables.Function<Statement, String, SQLException> customExtractor = stmt -> "custom sql";
        JdbcUtil.setSqlExtractor(customExtractor);

        assertEquals(customExtractor, JdbcUtil.getSqlExtractor());

        // Reset to default
        JdbcUtil.setSqlExtractor(JdbcUtil.DEFAULT_SQL_EXTRACTOR);
    }

    @Test
    public void testGetSqlLogHandler() {
        TriConsumer<String, Long, Long> handler = JdbcUtil.getSqlLogHandler();
        // Initially null
        assertNull(handler);
    }

    @Test
    public void testSetSqlLogHandler() {
        TriConsumer<String, Long, Long> handler = (sql, start, end) -> {
            // Custom logging
        };

        JdbcUtil.setSqlLogHandler(handler);
        assertEquals(handler, JdbcUtil.getSqlLogHandler());

        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.setSqlLogHandler(null));
    }

    // sqlPerfLogThresholdMillis with custom maxLogLength
    @Test
    public void testSetMinExecutionTimeForSqlPerfLog_WithMaxLength() {
        assertDoesNotThrow(() -> JdbcUtil.setSqlPerfLogThresholdMillis(100, 512));
    }

    @Test
    public void testSetMinExecutionTimeForSqlPerfLog() {
        JdbcUtil.setSqlPerfLogThresholdMillis(500);
        assertEquals(500, JdbcUtil.getSqlPerfLogThresholdMillis());

        JdbcUtil.setSqlPerfLogThresholdMillis(1000, 2048);
        assertEquals(1000, JdbcUtil.getSqlPerfLogThresholdMillis());
    }

    @Test
    public void testGetMinExecutionTimeForSqlPerfLog() {
        long defaultTime = JdbcUtil.getSqlPerfLogThresholdMillis();
        assertTrue(defaultTime >= 0);
    }

    @Test
    public void testRunWithSqlLogDisabled() {
        JdbcUtil.enableSqlLog();

        JdbcUtil.runWithSqlLogDisabled(() -> {
            assertFalse(JdbcUtil.isSqlLogEnabled());
        });

        assertTrue(JdbcUtil.isSqlLogEnabled());
    }

    @Test
    public void testRunWithSqlLogDisabled_RestoresStateChangedByAction() {
        JdbcUtil.enableSqlLog(123);
        JdbcUtil.disableSqlLog();

        JdbcUtil.runWithSqlLogDisabled(() -> {
            JdbcUtil.enableSqlLog(7);
            assertTrue(JdbcUtil.isSqlLogEnabled());
        });

        assertFalse(JdbcUtil.isSqlLogEnabled());
        assertEquals(123, JdbcUtil.isSQLLogEnabled_TL.get().maxSqlLogLength);
    }

    // runWithSqlLogDisabled when log already disabled
    @Test
    public void testRunWithSqlLogDisabled_WhenAlreadyDisabled() {
        JdbcUtil.disableSqlLog();
        assertFalse(JdbcUtil.isSqlLogEnabled());

        boolean[] ran = { false };
        JdbcUtil.runWithSqlLogDisabled(() -> ran[0] = true);
        assertTrue(ran[0]);
        assertFalse(JdbcUtil.isSqlLogEnabled());
    }

    @Test
    public void testCallWithSqlLogDisabled() {
        JdbcUtil.enableSqlLog();

        String result = JdbcUtil.callWithSqlLogDisabled(() -> {
            assertFalse(JdbcUtil.isSqlLogEnabled());
            return "test";
        });

        assertEquals("test", result);
        assertTrue(JdbcUtil.isSqlLogEnabled());
    }

    @Test
    public void testCallWithSqlLogDisabled_RestoresStateChangedByCallable() {
        JdbcUtil.enableSqlLog(321);

        final String result = JdbcUtil.callWithSqlLogDisabled(() -> {
            JdbcUtil.enableSqlLog(9);
            return "result";
        });

        assertEquals("result", result);
        assertTrue(JdbcUtil.isSqlLogEnabled());
        assertEquals(321, JdbcUtil.isSQLLogEnabled_TL.get().maxSqlLogLength);
    }

    // callWithSqlLogDisabled when log already disabled
    @Test
    public void testCallWithSqlLogDisabled_WhenAlreadyDisabled() {
        JdbcUtil.disableSqlLog();
        assertFalse(JdbcUtil.isSqlLogEnabled());

        String result = JdbcUtil.callWithSqlLogDisabled(() -> "test");
        assertEquals("test", result);
        assertFalse(JdbcUtil.isSqlLogEnabled());
    }

    // DEFAULT_SQL_EXTRACTOR — basic statement returns toString() (lines 250-266 happy path).
    @Test
    public void testDefaultSqlExtractor_BasicStatement() throws SQLException {
        Statement stmt = mock(Statement.class);
        when(stmt.toString()).thenReturn("SELECT 1");

        String sql = JdbcUtil.DEFAULT_SQL_EXTRACTOR.apply(stmt);

        assertEquals("SELECT 1", sql);
    }

    @Test
    void defaultSqlExtractor_pooledStatement_usesUnwrappedSql() throws SQLException {
        final HikariProxyPreparedStatement pooled = mock(HikariProxyPreparedStatement.class);
        final Statement delegate = mock(Statement.class);
        when(pooled.isWrapperFor(Statement.class)).thenReturn(true);
        when(pooled.unwrap(Statement.class)).thenReturn(delegate);
        when(delegate.toString()).thenReturn("SELECT 42");

        assertEquals("SELECT 42", JdbcUtil.DEFAULT_SQL_EXTRACTOR.apply(pooled));
        verify(pooled).unwrap(Statement.class);
    }

    @Test
    public void testDefaultMaxSqlLogLength() {
        assertEquals(1024, JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH);
    }

    @Test
    public void testDefaultMinExecutionTimeForDaoMethodPerfLog() {
        assertEquals(3000L, JdbcUtil.DEFAULT_DAO_METHOD_PERF_LOG_THRESHOLD_MILLIS);
    }

    @Test
    public void testDefaultMinExecutionTimeForSqlPerfLog() {
        assertEquals(1000L, JdbcUtil.DEFAULT_SQL_PERF_LOG_THRESHOLD_MILLIS);
    }

    private static Stream<Arguments> executionModes() {
        return Stream.of(ExecutionOperation.values()).flatMap(operation -> Stream.of(false, true).map(logging -> Arguments.of(operation, logging)));
    }

    private enum ExecutionOperation {
        QUERY, UPDATE, LARGE_UPDATE, EXECUTE
    }
}
