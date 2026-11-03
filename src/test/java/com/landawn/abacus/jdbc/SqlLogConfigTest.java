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
        assertEquals(4, new SqlLogConfig(500L, 1).maxSqlLogLength);

        // 4 is already usable and must not be altered.
        assertEquals(4, new SqlLogConfig(true, 4).maxSqlLogLength);

        // The invariant this guards: truncation at the configured width never throws.
        assertEquals("S...", Strings.abbreviate("SELECT * FROM users", new SqlLogConfig(true, 3).maxSqlLogLength));
    }

    // JdbcUtil hands the same default instances to every thread at the default settings (see JdbcUtil.sqlLogConfig()), so
    // a config must not be mutable: changing a shared default would change the logging of all those threads.
    @Test
    public void testInstancesAreImmutable() {
        for (final java.lang.reflect.Field field : SqlLogConfig.class.getDeclaredFields()) {
            assertTrue(java.lang.reflect.Modifier.isFinal(field.getModifiers()), field.toString());
        }

        for (final java.lang.reflect.Method method : SqlLogConfig.class.getDeclaredMethods()) {
            assertFalse(method.getName().startsWith("set"), method.toString());
        }
    }
}
