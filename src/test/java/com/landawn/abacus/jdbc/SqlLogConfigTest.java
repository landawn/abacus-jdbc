package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class SqlLogConfigTest extends TestBase {

    @Test
    public void testConstructorForGeneralLogging() {
        boolean isEnabled = true;
        int maxSqlLogLength = 1000;
        
        SqlLogConfig config = new SqlLogConfig(isEnabled, maxSqlLogLength);
        
        assertTrue(config.isEnabled);
        assertEquals(1000, config.maxSqlLogLength);
        assertEquals(Long.MAX_VALUE, config.minExecutionTimeForSqlPerfLog);
    }

    @Test
    public void testConstructorForGeneralLoggingWithDefaultLength() {
        boolean isEnabled = true;
        int maxSqlLogLength = 0;
        
        SqlLogConfig config = new SqlLogConfig(isEnabled, maxSqlLogLength);
        
        assertTrue(config.isEnabled);
        assertEquals(JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH, config.maxSqlLogLength);
        assertEquals(Long.MAX_VALUE, config.minExecutionTimeForSqlPerfLog);
    }

    @Test
    public void testConstructorForGeneralLoggingWithNegativeLength() {
        boolean isEnabled = false;
        int maxSqlLogLength = -100;
        
        SqlLogConfig config = new SqlLogConfig(isEnabled, maxSqlLogLength);
        
        assertFalse(config.isEnabled);
        assertEquals(JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH, config.maxSqlLogLength);
        assertEquals(Long.MAX_VALUE, config.minExecutionTimeForSqlPerfLog);
    }

    @Test
    public void testConstructorForPerformanceLogging() {
        long minExecutionTime = 5000;
        int maxSqlLogLength = 2000;
        
        SqlLogConfig config = new SqlLogConfig(minExecutionTime, maxSqlLogLength);
        
        assertFalse(config.isEnabled);
        assertEquals(2000, config.maxSqlLogLength);
        assertEquals(5000, config.minExecutionTimeForSqlPerfLog);
    }

    @Test
    public void testConstructorForPerformanceLoggingWithDefaultLength() {
        long minExecutionTime = 1000;
        int maxSqlLogLength = 0;
        
        SqlLogConfig config = new SqlLogConfig(minExecutionTime, maxSqlLogLength);
        
        assertFalse(config.isEnabled);
        assertEquals(JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH, config.maxSqlLogLength);
        assertEquals(1000, config.minExecutionTimeForSqlPerfLog);
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
        
        assertEquals(2000L, config.minExecutionTimeForSqlPerfLog);
        assertEquals(4000, config.maxSqlLogLength);
    }

    @Test
    public void testSetPerformanceLoggingWithDefaultLength() {
        SqlLogConfig config = new SqlLogConfig(1000L, 500);
        
        config.set(3000L, 0);
        
        assertEquals(3000L, config.minExecutionTimeForSqlPerfLog);
        assertEquals(JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH, config.maxSqlLogLength);
    }

    @Test
    public void testMixedUsage() {
        // Create with general logging
        SqlLogConfig config = new SqlLogConfig(true, 1000);
        assertTrue(config.isEnabled);
        assertEquals(1000, config.maxSqlLogLength);
        assertEquals(Long.MAX_VALUE, config.minExecutionTimeForSqlPerfLog);
        
        // Switch to performance logging
        config.set(5000L, 2000);
        assertEquals(5000L, config.minExecutionTimeForSqlPerfLog);
        assertEquals(2000, config.maxSqlLogLength);
        
        // Switch back to general logging
        config.set(false, 3000);
        assertFalse(config.isEnabled);
        assertEquals(3000, config.maxSqlLogLength);
    }
}