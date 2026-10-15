package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
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

import com.landawn.abacus.util.ImmutableList;

class NamedQueryTemporalBindingTest extends NamedQueryTestSupport {

    @Test
    public void testSetDateSqlDate() throws SQLException {
        String paramName = "param1";
        java.sql.Date value = java.sql.Date.valueOf("2023-06-15");

        NamedQuery result = namedQuery.setDate(paramName, value);

        verify(mockPreparedStatement).setDate(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetDateUtilDate() throws SQLException {
        String paramName = "param1";
        java.util.Date value = new java.util.Date();

        NamedQuery result = namedQuery.setDate(paramName, value);

        verify(mockPreparedStatement).setDate(eq(1), any(java.sql.Date.class));
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetDateLocalDate() throws SQLException {
        String paramName = "param1";
        LocalDate value = LocalDate.of(2023, 6, 15);

        NamedQuery result = namedQuery.setDate(paramName, value);

        verify(mockPreparedStatement).setDate(1, java.sql.Date.valueOf(value));
        assertSame(namedQuery, result);
    }

    // --- setDate(sql.Date): all paths ---

    @Test
    public void testSetDate_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setDate("unknown", java.sql.Date.valueOf("2024-01-01")));
    }

    @Test
    public void testSetDate_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setDate("unknown", java.sql.Date.valueOf("2024-01-01")));
    }

    @Test
    public void testSetDate_MapBranch_OneOccurrence() throws SQLException {
        java.sql.Date d = java.sql.Date.valueOf("2024-06-01");
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setDate("c", d);
        verify(mockPreparedStatement).setDate(3, d);
    }

    @Test
    public void testSetDate_MapBranch_TwoOccurrences() throws SQLException {
        java.sql.Date d = java.sql.Date.valueOf("2024-06-01");
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setDate("p", d);
        verify(mockPreparedStatement, times(2)).setDate(anyInt(), eq(d));
    }

    @Test
    public void testSetDate_MapBranch_ThreeOccurrences() throws SQLException {
        java.sql.Date d = java.sql.Date.valueOf("2024-06-01");
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setDate("p", d);
        verify(mockPreparedStatement, times(3)).setDate(anyInt(), eq(d));
    }

    @Test
    public void testSetDate_MapBranch_FourOccurrences() throws SQLException {
        java.sql.Date d = java.sql.Date.valueOf("2024-06-01");
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setDate("p", d);
        verify(mockPreparedStatement, times(4)).setDate(anyInt(), eq(d));
    }

    // setDate(String, java.util.Date) with a java.sql.Date argument (instanceof branch, L1433)
    @Test
    public void testSetDate_UtilDate_IsSqlDate() throws SQLException {
        java.sql.Date sqlDate = new java.sql.Date(System.currentTimeMillis());
        namedQuery.setDate("param1", (java.util.Date) sqlDate);
        verify(mockPreparedStatement).setDate(eq(1), eq(sqlDate));
    }

    @Test
    public void testSetDateUtilDateNull() throws SQLException {
        String paramName = "param1";
        java.util.Date value = null;

        NamedQuery result = namedQuery.setDate(paramName, value);

        verify(mockPreparedStatement).setDate(1, null);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetDateLocalDateNull() throws SQLException {
        String paramName = "param1";
        LocalDate value = null;

        NamedQuery result = namedQuery.setDate(paramName, value);

        verify(mockPreparedStatement).setDate(1, null);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetTimeSqlTime() throws SQLException {
        String paramName = "param1";
        java.sql.Time value = java.sql.Time.valueOf("14:30:00");

        NamedQuery result = namedQuery.setTime(paramName, value);

        verify(mockPreparedStatement).setTime(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetTimeUtilDate() throws SQLException {
        String paramName = "param1";
        java.util.Date value = new java.util.Date();

        NamedQuery result = namedQuery.setTime(paramName, value);

        verify(mockPreparedStatement).setTime(eq(1), any(java.sql.Time.class));
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetTimeLocalTime() throws SQLException {
        String paramName = "param1";
        LocalTime value = LocalTime.of(14, 30, 0);

        NamedQuery result = namedQuery.setTime(paramName, value);

        verify(mockPreparedStatement).setTime(1, java.sql.Time.valueOf(value));
        assertSame(namedQuery, result);
    }

    // --- setTime(sql.Time): all paths ---

    @Test
    public void testSetTime_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setTime("unknown", java.sql.Time.valueOf("10:00:00")));
    }

    @Test
    public void testSetTime_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setTime("unknown", java.sql.Time.valueOf("10:00:00")));
    }

    @Test
    public void testSetTime_MapBranch_OneOccurrence() throws SQLException {
        java.sql.Time t = java.sql.Time.valueOf("12:30:00");
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setTime("a", t);
        verify(mockPreparedStatement).setTime(1, t);
    }

    @Test
    public void testSetTime_MapBranch_TwoOccurrences() throws SQLException {
        java.sql.Time t = java.sql.Time.valueOf("09:00:00");
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setTime("p", t);
        verify(mockPreparedStatement, times(2)).setTime(anyInt(), eq(t));
    }

    @Test
    public void testSetTime_MapBranch_ThreeOccurrences() throws SQLException {
        java.sql.Time t = java.sql.Time.valueOf("15:00:00");
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setTime("p", t);
        verify(mockPreparedStatement, times(3)).setTime(anyInt(), eq(t));
    }

    @Test
    public void testSetTime_MapBranch_FourOccurrences() throws SQLException {
        java.sql.Time t = java.sql.Time.valueOf("18:00:00");
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setTime("p", t);
        verify(mockPreparedStatement, times(4)).setTime(anyInt(), eq(t));
    }

    // setTime(String, java.util.Date) with a java.sql.Time argument (instanceof branch, L1549)
    @Test
    public void testSetTime_UtilDate_IsSqlTime() throws SQLException {
        java.sql.Time sqlTime = new java.sql.Time(System.currentTimeMillis());
        namedQuery.setTime("param1", (java.util.Date) sqlTime);
        verify(mockPreparedStatement).setTime(eq(1), eq(sqlTime));
    }

    // setTime(String, LocalTime) - null case (L1580 null branch)
    @Test
    public void testSetTimeLocalTime_Null() throws SQLException {
        namedQuery.setTime("param1", (LocalTime) null);
        verify(mockPreparedStatement).setTime(1, null);
    }

    @Test
    public void testSetTimestamp() throws SQLException {
        String paramName = "param1";
        Timestamp value = new Timestamp(System.currentTimeMillis());

        NamedQuery result = namedQuery.setTimestamp(paramName, value);

        verify(mockPreparedStatement).setTimestamp(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetTimestampUtilDate() throws SQLException {
        String paramName = "param1";
        java.util.Date value = new java.util.Date();

        NamedQuery result = namedQuery.setTimestamp(paramName, value);

        verify(mockPreparedStatement).setTimestamp(eq(1), any(Timestamp.class));
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetTimestampLocalDateTime() throws SQLException {
        String paramName = "param1";
        LocalDateTime value = LocalDateTime.now();

        NamedQuery result = namedQuery.setTimestamp(paramName, value);

        verify(mockPreparedStatement).setTimestamp(1, Timestamp.valueOf(value));
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetTimestampZonedDateTime() throws SQLException {
        String paramName = "param1";
        ZonedDateTime value = ZonedDateTime.now();

        NamedQuery result = namedQuery.setTimestamp(paramName, value);

        verify(mockPreparedStatement).setTimestamp(1, Timestamp.from(value.toInstant()));
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetTimestampOffsetDateTime() throws SQLException {
        String paramName = "param1";
        OffsetDateTime value = OffsetDateTime.now();

        NamedQuery result = namedQuery.setTimestamp(paramName, value);

        verify(mockPreparedStatement).setTimestamp(1, Timestamp.from(value.toInstant()));
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetTimestampInstant() throws SQLException {
        String paramName = "param1";
        Instant value = Instant.now();

        NamedQuery result = namedQuery.setTimestamp(paramName, value);

        verify(mockPreparedStatement).setTimestamp(1, Timestamp.from(value));
        assertSame(namedQuery, result);
    }

    // --- setTimestamp(sql.Timestamp): all paths ---

    @Test
    public void testSetTimestamp_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setTimestamp("unknown", new Timestamp(0)));
    }

    @Test
    public void testSetTimestamp_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setTimestamp("unknown", new Timestamp(0)));
    }

    @Test
    public void testSetTimestamp_MapBranch_OneOccurrence() throws SQLException {
        Timestamp ts = new Timestamp(1000L);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setTimestamp("b", ts);
        verify(mockPreparedStatement).setTimestamp(2, ts);
    }

    @Test
    public void testSetTimestamp_MapBranch_TwoOccurrences() throws SQLException {
        Timestamp ts = new Timestamp(2000L);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setTimestamp("p", ts);
        verify(mockPreparedStatement, times(2)).setTimestamp(anyInt(), eq(ts));
    }

    @Test
    public void testSetTimestamp_MapBranch_ThreeOccurrences() throws SQLException {
        Timestamp ts = new Timestamp(3000L);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setTimestamp("p", ts);
        verify(mockPreparedStatement, times(3)).setTimestamp(anyInt(), eq(ts));
    }

    @Test
    public void testSetTimestamp_MapBranch_FourOccurrences() throws SQLException {
        Timestamp ts = new Timestamp(4000L);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setTimestamp("p", ts);
        verify(mockPreparedStatement, times(4)).setTimestamp(anyInt(), eq(ts));
    }

    // setTimestamp(String, java.util.Date) with a java.sql.Timestamp argument (instanceof branch, L1681)
    @Test
    public void testSetTimestamp_UtilDate_IsSqlTimestamp() throws SQLException {
        Timestamp ts = new Timestamp(System.currentTimeMillis());
        namedQuery.setTimestamp("param1", (java.util.Date) ts);
        verify(mockPreparedStatement).setTimestamp(eq(1), eq(ts));
    }

    // setTimestamp(String, LocalDateTime) - null case (L1712 null branch)
    @Test
    public void testSetTimestampLocalDateTime_Null() throws SQLException {
        namedQuery.setTimestamp("param1", (LocalDateTime) null);
        verify(mockPreparedStatement).setTimestamp(1, null);
    }

    // setTimestamp(String, ZonedDateTime) - null case (L1741 null branch)
    @Test
    public void testSetTimestampZonedDateTime_Null() throws SQLException {
        namedQuery.setTimestamp("param1", (ZonedDateTime) null);
        verify(mockPreparedStatement).setTimestamp(1, null);
    }

    // setTimestamp(String, OffsetDateTime) - null case (L1770 null branch)
    @Test
    public void testSetTimestampOffsetDateTime_Null() throws SQLException {
        namedQuery.setTimestamp("param1", (OffsetDateTime) null);
        verify(mockPreparedStatement).setTimestamp(1, null);
    }

    // setTimestamp(String, Instant) - null case (L1801 null branch)
    @Test
    public void testSetTimestampInstant_Null() throws SQLException {
        namedQuery.setTimestamp("param1", (Instant) null);
        verify(mockPreparedStatement).setTimestamp(1, null);
    }
}
