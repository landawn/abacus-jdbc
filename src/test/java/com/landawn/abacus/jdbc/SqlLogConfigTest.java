package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.util.Strings;

public class SqlLogConfigTest extends TestBase {

    @Test
    public void testConstructorForGeneralLogging() {
        boolean isEnabled = true;
        int maxSqlLogLength = 1000;

        SqlLogConfig config = new SqlLogConfig(isEnabled, maxSqlLogLength);

        assertTrue(config.isEnabled);
        assertEquals(1000, config.maxSqlLogLength);
        assertEquals(Long.MAX_VALUE, config.sqlPerfLogThresholdMillis);
    }

    @Test
    public void testConstructorForGeneralLoggingWithDefaultLength() {
        boolean isEnabled = true;
        int maxSqlLogLength = 0;

        SqlLogConfig config = new SqlLogConfig(isEnabled, maxSqlLogLength);

        assertTrue(config.isEnabled);
        assertEquals(JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH, config.maxSqlLogLength);
        assertEquals(Long.MAX_VALUE, config.sqlPerfLogThresholdMillis);
    }

    @Test
    public void testConstructorForGeneralLoggingWithNegativeLength() {
        boolean isEnabled = false;
        int maxSqlLogLength = -100;

        SqlLogConfig config = new SqlLogConfig(isEnabled, maxSqlLogLength);

        assertFalse(config.isEnabled);
        assertEquals(JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH, config.maxSqlLogLength);
        assertEquals(Long.MAX_VALUE, config.sqlPerfLogThresholdMillis);
    }

    @Test
    public void testConstructorForPerformanceLogging() {
        long minExecutionTime = 5000;
        int maxSqlLogLength = 2000;

        SqlLogConfig config = new SqlLogConfig(minExecutionTime, maxSqlLogLength);

        assertFalse(config.isEnabled);
        assertEquals(2000, config.maxSqlLogLength);
        assertEquals(5000, config.sqlPerfLogThresholdMillis);
    }

    @Test
    public void testConstructorForPerformanceLoggingWithDefaultLength() {
        long minExecutionTime = 1000;
        int maxSqlLogLength = 0;

        SqlLogConfig config = new SqlLogConfig(minExecutionTime, maxSqlLogLength);

        assertFalse(config.isEnabled);
        assertEquals(JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH, config.maxSqlLogLength);
        assertEquals(1000, config.sqlPerfLogThresholdMillis);
    }

    @Test
    public void testSetGeneralLogging() {
        SqlLogConfig config = new SqlLogConfig(false, 100);

        config.set(true, 3000);

        assertTrue(config.isEnabled);
        assertEquals(3000, config.maxSqlLogLength);
    }

    @Test
    public void testSetGeneralLoggingWithDefaultLength() {
        SqlLogConfig config = new SqlLogConfig(true, 500);

        config.set(false, -50);

        assertFalse(config.isEnabled);
        assertEquals(JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH, config.maxSqlLogLength);
    }

    @Test
    public void testSetPerformanceLogging() {
        SqlLogConfig config = new SqlLogConfig(true, 100);

        config.set(2000L, 4000);

        assertEquals(2000L, config.sqlPerfLogThresholdMillis);
        assertEquals(4000, config.maxSqlLogLength);
    }

    @Test
    public void testSetPerformanceLoggingWithDefaultLength() {
        SqlLogConfig config = new SqlLogConfig(1000L, 500);

        config.set(3000L, 0);

        assertEquals(3000L, config.sqlPerfLogThresholdMillis);
        assertEquals(JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH, config.maxSqlLogLength);
    }

    @Test
    public void testMixedUsage() {
        // Create with general logging
        SqlLogConfig config = new SqlLogConfig(true, 1000);
        assertTrue(config.isEnabled);
        assertEquals(1000, config.maxSqlLogLength);
        assertEquals(Long.MAX_VALUE, config.sqlPerfLogThresholdMillis);

        // Switch to performance logging
        config.set(5000L, 2000);
        assertEquals(5000L, config.sqlPerfLogThresholdMillis);
        assertEquals(2000, config.maxSqlLogLength);

        // Switch back to general logging
        config.set(false, 3000);
        assertFalse(config.isEnabled);
        assertEquals(3000, config.maxSqlLogLength);
    }

    // Regression: Strings.abbreviate(sql, maxWidth) throws IllegalArgumentException for maxWidth 1-3,
    // and JdbcUtil.logSql/handleSqlLog call it with the configured maxSqlLogLength (the latter from a
    // finally block that must never throw). The config must therefore never hold a value of 1-3: it is
    // raised to the smallest width the truncation marker supports.
    @Test
    @Tag("2025")
    public void testTinyMaxSqlLogLength_RaisedToMinimumAbbreviationWidth() {
        assertEquals(4, new SqlLogConfig(true, 1).maxSqlLogLength);
        assertEquals(4, new SqlLogConfig(true, 3).maxSqlLogLength);
        assertEquals(4, new SqlLogConfig(500L, 2).maxSqlLogLength);

        final SqlLogConfig config = new SqlLogConfig(true, 1000);
        config.set(true, 3);
        assertEquals(4, config.maxSqlLogLength);
        config.set(500L, 1);
        assertEquals(4, config.maxSqlLogLength);

        // 4 is already usable and must not be altered.
        assertEquals(4, new SqlLogConfig(true, 4).maxSqlLogLength);

        // The invariant this guards: truncation at the configured width never throws.
        assertEquals("S...", Strings.abbreviate("SELECT * FROM users", new SqlLogConfig(true, 3).maxSqlLogLength));
    }
}