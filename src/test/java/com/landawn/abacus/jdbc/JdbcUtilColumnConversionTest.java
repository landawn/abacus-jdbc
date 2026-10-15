package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.landawn.abacus.TestBase;

class JdbcUtilColumnConversionTest extends TestBase {

    private static final Timestamp TIMESTAMP = Timestamp.valueOf("2026-10-15 12:34:56.123456789");
    private static final Date DATE = Date.valueOf("2026-10-15");

    @ParameterizedTest(name = "{0}")
    @EnumSource(Access.class)
    void getColumnValue_oracleTimestamp_preservesNanoseconds(final Access access) throws SQLException {
        final ResultSet resultSet = resultSet(new oracle.sql.TIMESTAMP(TIMESTAMP), "oracle.sql.TIMESTAMP");

        assertEquals(TIMESTAMP, access.read(resultSet));
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("timezoneTypes")
    void getColumnValue_oracleTimezone_delegatesToDriver(final Access access, final boolean localTimezone) throws SQLException {
        final Object value = localTimezone ? new oracle.sql.TIMESTAMPLTZ() : new oracle.sql.TIMESTAMPTZ();
        final ResultSet resultSet = resultSet(value, value.getClass().getName());

        assertSame(TIMESTAMP, access.read(resultSet));
        if (access == Access.LABEL) {
            verify(resultSet).getTimestamp("created");
        } else {
            verify(resultSet).getTimestamp(1);
        }
    }

    @ParameterizedTest(name = "{0}, metadata={1}")
    @MethodSource("dateTypes")
    void getColumnValue_oracleDate_usesMetadataToPreserveTime(final Access access, final String metadataClass) throws SQLException {
        final ResultSet resultSet = resultSet(new oracle.sql.DATE(TIMESTAMP), metadataClass);
        final boolean timestampColumn = !metadataClass.equals("java.sql.Date");

        assertSame(timestampColumn ? TIMESTAMP : DATE, access.read(resultSet));
        if (timestampColumn) {
            verify(resultSet, never()).getDate(1);
        } else {
            verify(resultSet, never()).getTimestamp(1);
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Access.class, names = { "INDEX", "ALL_VALUES" })
    void getColumnValue_driverConversionFails_preservesSqlException(final Access access) throws SQLException {
        final ResultSet resultSet = resultSet(new oracle.sql.TIMESTAMPTZ(), "oracle.sql.TIMESTAMPTZ");
        final SQLException failure = new SQLException("timezone unavailable");
        when(resultSet.getTimestamp(1)).thenThrow(failure);

        assertSame(failure, assertThrows(SQLException.class, () -> access.read(resultSet)));
    }

    @ParameterizedTest(name = "known label = {0}")
    @MethodSource("labelPresence")
    void getColumnValue_oracleDateByLabel_handlesMissingMetadata(final boolean knownLabel) throws SQLException {
        final oracle.sql.DATE value = new oracle.sql.DATE(TIMESTAMP);
        final ResultSet resultSet = resultSet(value, "java.sql.Date");
        when(resultSet.getMetaData().getColumnLabel(1)).thenReturn(knownLabel ? "created" : "another_column");

        assertSame(knownLabel ? DATE : value, JdbcUtil.getColumnValue(resultSet, "created"));
    }

    private static Stream<Arguments> timezoneTypes() {
        return Stream.of(Access.values()).flatMap(access -> Stream.of(false, true).map(local -> Arguments.of(access, local)));
    }

    private static Stream<Arguments> dateTypes() {
        return Stream.of(Access.values())
                .flatMap(access -> Stream.of("java.sql.Timestamp", "oracle.sql.TIMESTAMP", "java.sql.Date")
                        .map(metadataClass -> Arguments.of(access, metadataClass)));
    }

    private static Stream<Boolean> labelPresence() {
        return Stream.of(false, true);
    }

    private static ResultSet resultSet(final Object value, final String metadataClass) throws SQLException {
        final ResultSet resultSet = mock(ResultSet.class);
        final ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(resultSet.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("created");
        when(metadata.getColumnClassName(1)).thenReturn(metadataClass);
        when(resultSet.getObject(1)).thenReturn(value);
        when(resultSet.getObject("created")).thenReturn(value);
        when(resultSet.getTimestamp(1)).thenReturn(TIMESTAMP);
        when(resultSet.getTimestamp("created")).thenReturn(TIMESTAMP);
        when(resultSet.getDate(1)).thenReturn(DATE);
        when(resultSet.next()).thenReturn(true, false);
        return resultSet;
    }

    private enum Access {
        INDEX, LABEL, ALL_VALUES;

        Object read(final ResultSet resultSet) throws SQLException {
            return switch (this) {
                case INDEX -> JdbcUtil.getColumnValue(resultSet, 1);
                case LABEL -> JdbcUtil.getColumnValue(resultSet, "created");
                case ALL_VALUES -> {
                    final List<?> values = JdbcUtil.getAllColumnValues(resultSet, 1);
                    assertEquals(1, values.size());
                    yield values.get(0);
                }
            };
        }
    }
}
