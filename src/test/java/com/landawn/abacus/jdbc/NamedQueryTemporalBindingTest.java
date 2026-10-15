package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class NamedQueryTemporalBindingTest extends NamedQueryBindingTestSupport {

    @Test
    void testSetDateSqlDate() throws SQLException {
        String paramName = "param1";
        java.sql.Date value = java.sql.Date.valueOf("2023-06-15");

        NamedQuery result = namedQuery.setDate(paramName, value);

        verify(mockPreparedStatement).setDate(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetDateUtilDate() throws SQLException {
        String paramName = "param1";
        java.util.Date value = new java.util.Date();

        NamedQuery result = namedQuery.setDate(paramName, value);

        verify(mockPreparedStatement).setDate(eq(1), any(java.sql.Date.class));
        assertSame(namedQuery, result);
    }

    @Test
    void testSetDateLocalDate() throws SQLException {
        String paramName = "param1";
        LocalDate value = LocalDate.of(2023, 6, 15);

        NamedQuery result = namedQuery.setDate(paramName, value);

        verify(mockPreparedStatement).setDate(1, java.sql.Date.valueOf(value));
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setDate_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        final java.sql.Date d = java.sql.Date.valueOf("2024-06-01");
        assertNamedBinding(layout, (query, parameter) -> query.setDate(parameter, d), (statement, index) -> statement.setDate(index, d));
    }

    @Test
    void testSetDate_UtilDate_IsSqlDate() throws SQLException {
        java.sql.Date sqlDate = new java.sql.Date(System.currentTimeMillis());
        namedQuery.setDate("param1", (java.util.Date) sqlDate);
        verify(mockPreparedStatement).setDate(eq(1), eq(sqlDate));
    }

    @Test
    void testSetDateUtilDateNull() throws SQLException {
        String paramName = "param1";
        java.util.Date value = null;

        NamedQuery result = namedQuery.setDate(paramName, value);

        verify(mockPreparedStatement).setDate(1, null);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetDateLocalDateNull() throws SQLException {
        String paramName = "param1";
        LocalDate value = null;

        NamedQuery result = namedQuery.setDate(paramName, value);

        verify(mockPreparedStatement).setDate(1, null);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetTimeSqlTime() throws SQLException {
        String paramName = "param1";
        java.sql.Time value = java.sql.Time.valueOf("14:30:00");

        NamedQuery result = namedQuery.setTime(paramName, value);

        verify(mockPreparedStatement).setTime(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetTimeUtilDate() throws SQLException {
        String paramName = "param1";
        java.util.Date value = new java.util.Date();

        NamedQuery result = namedQuery.setTime(paramName, value);

        verify(mockPreparedStatement).setTime(eq(1), any(java.sql.Time.class));
        assertSame(namedQuery, result);
    }

    @Test
    void testSetTimeLocalTime() throws SQLException {
        String paramName = "param1";
        LocalTime value = LocalTime.of(14, 30, 0);

        NamedQuery result = namedQuery.setTime(paramName, value);

        verify(mockPreparedStatement).setTime(1, java.sql.Time.valueOf(value));
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setTime_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        final java.sql.Time t = java.sql.Time.valueOf("12:30:00");
        assertNamedBinding(layout, (query, parameter) -> query.setTime(parameter, t), (statement, index) -> statement.setTime(index, t));
    }

    @Test
    void testSetTime_UtilDate_IsSqlTime() throws SQLException {
        java.sql.Time sqlTime = new java.sql.Time(System.currentTimeMillis());
        namedQuery.setTime("param1", (java.util.Date) sqlTime);
        verify(mockPreparedStatement).setTime(eq(1), eq(sqlTime));
    }

    @Test
    void testSetTimeLocalTime_Null() throws SQLException {
        namedQuery.setTime("param1", (LocalTime) null);
        verify(mockPreparedStatement).setTime(1, null);
    }

    @Test
    void testSetTimestamp() throws SQLException {
        String paramName = "param1";
        Timestamp value = new Timestamp(System.currentTimeMillis());

        NamedQuery result = namedQuery.setTimestamp(paramName, value);

        verify(mockPreparedStatement).setTimestamp(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetTimestampUtilDate() throws SQLException {
        String paramName = "param1";
        java.util.Date value = new java.util.Date();

        NamedQuery result = namedQuery.setTimestamp(paramName, value);

        verify(mockPreparedStatement).setTimestamp(eq(1), any(Timestamp.class));
        assertSame(namedQuery, result);
    }

    @Test
    void testSetTimestampLocalDateTime() throws SQLException {
        String paramName = "param1";
        LocalDateTime value = LocalDateTime.now();

        NamedQuery result = namedQuery.setTimestamp(paramName, value);

        verify(mockPreparedStatement).setTimestamp(1, Timestamp.valueOf(value));
        assertSame(namedQuery, result);
    }

    @Test
    void testSetTimestampZonedDateTime() throws SQLException {
        String paramName = "param1";
        ZonedDateTime value = ZonedDateTime.now();

        NamedQuery result = namedQuery.setTimestamp(paramName, value);

        verify(mockPreparedStatement).setTimestamp(1, Timestamp.from(value.toInstant()));
        assertSame(namedQuery, result);
    }

    @Test
    void testSetTimestampOffsetDateTime() throws SQLException {
        String paramName = "param1";
        OffsetDateTime value = OffsetDateTime.now();

        NamedQuery result = namedQuery.setTimestamp(paramName, value);

        verify(mockPreparedStatement).setTimestamp(1, Timestamp.from(value.toInstant()));
        assertSame(namedQuery, result);
    }

    @Test
    void testSetTimestampInstant() throws SQLException {
        String paramName = "param1";
        Instant value = Instant.now();

        NamedQuery result = namedQuery.setTimestamp(paramName, value);

        verify(mockPreparedStatement).setTimestamp(1, Timestamp.from(value));
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setTimestamp_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        final Timestamp ts = new Timestamp(1000L);
        assertNamedBinding(layout, (query, parameter) -> query.setTimestamp(parameter, ts), (statement, index) -> statement.setTimestamp(index, ts));
    }

    @Test
    void testSetTimestamp_UtilDate_IsSqlTimestamp() throws SQLException {
        Timestamp ts = new Timestamp(System.currentTimeMillis());
        namedQuery.setTimestamp("param1", (java.util.Date) ts);
        verify(mockPreparedStatement).setTimestamp(eq(1), eq(ts));
    }

    @Test
    void testSetTimestampLocalDateTime_Null() throws SQLException {
        namedQuery.setTimestamp("param1", (LocalDateTime) null);
        verify(mockPreparedStatement).setTimestamp(1, null);
    }

    @Test
    void testSetTimestampZonedDateTime_Null() throws SQLException {
        namedQuery.setTimestamp("param1", (ZonedDateTime) null);
        verify(mockPreparedStatement).setTimestamp(1, null);
    }

    @Test
    void testSetTimestampOffsetDateTime_Null() throws SQLException {
        namedQuery.setTimestamp("param1", (OffsetDateTime) null);
        verify(mockPreparedStatement).setTimestamp(1, null);
    }

    @Test
    void testSetTimestampInstant_Null() throws SQLException {
        namedQuery.setTimestamp("param1", (Instant) null);
        verify(mockPreparedStatement).setTimestamp(1, null);
    }
}
