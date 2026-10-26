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
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Timestamp;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

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
        // Exercise the compatibility fallback when a driver accepts getObject(label) but not findColumn(label).
        when(resultSet.findColumn("created")).thenThrow(new SQLException("Label lookup unsupported"));
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

    @ParameterizedTest(name = "oracle={0}, fallback={1}, label={2}")
    @MethodSource("labelResolutionScenarios")
    void getColumnValue_byLabel_resolvesTheSameColumnAsTheLabel(final boolean oracleValue, final boolean useFallback, final String columnLabel)
            throws SQLException {
        final ResultSetMetaData metadata = (ResultSetMetaData) java.lang.reflect.Proxy.newProxyInstance(ResultSetMetaData.class.getClassLoader(),
                new Class<?>[] { ResultSetMetaData.class }, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getColumnCount":
                            return 2;
                        case "getColumnLabel":
                            return ((Integer) args[0]) == 1 ? "c" : "created";
                        case "getColumnName":
                            return ((Integer) args[0]) == 1 ? "created" : "shipped";
                        case "getColumnClassName":
                            return ((Integer) args[0]) == 1 ? "java.sql.Timestamp" : "java.sql.Date";
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });

        final Object value = oracleValue ? new oracle.sql.DATE(TIMESTAMP) : DATE;
        final ResultSet resultSet = (ResultSet) java.lang.reflect.Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getMetaData":
                            return metadata;
                        case "getObject":
                            return columnLabel.equals(args[0]) ? value : null;
                        case "findColumn":
                            if (useFallback) {
                                throw new SQLFeatureNotSupportedException("Label lookup unsupported");
                            }
                            return 2;
                        case "getDate":
                            return ((Integer) args[0]) == 2 ? DATE : null;
                        case "getTimestamp":
                            return ((Integer) args[0]) == 1 ? TIMESTAMP : null;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });

        // The value was read by label (column 2): its metadata class must be read from the same column, or the Date is
        // converted as the Timestamp column 1 (whose *name* also matches) and the wrong value is returned. This must
        // also hold when findColumn is unsupported; the public metadata lookup retains its separate name/label contract.
        assertSame(DATE, JdbcUtil.getColumnValue(resultSet, columnLabel));
        assertEquals(1, JdbcUtil.getColumnIndex(metadata, columnLabel));
    }

    private static Stream<Arguments> labelResolutionScenarios() {
        return Stream.of(false, true)
                .flatMap(oracle -> Stream.of(false, true)
                        .flatMap(fallback -> Stream.of("created", "CrEaTeD").map(label -> Arguments.of(oracle, fallback, label))));
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void getColumnValue_labelFallback_retainsNameOnlyLookup(final boolean oracleValue) throws SQLException {
        final Object value = oracleValue ? new oracle.sql.DATE(TIMESTAMP) : DATE;
        final ResultSet resultSet = resultSet(value, "java.sql.Timestamp");
        final ResultSetMetaData metadata = resultSet.getMetaData();
        when(resultSet.findColumn("created")).thenThrow(new SQLFeatureNotSupportedException("Label lookup unsupported"));
        when(metadata.getColumnCount()).thenReturn(2);
        when(metadata.getColumnLabel(1)).thenReturn(null);
        when(metadata.getColumnName(1)).thenReturn("CrEaTeD");
        when(metadata.getColumnLabel(2)).thenReturn("another_label");

        assertSame(TIMESTAMP, JdbcUtil.getColumnValue(resultSet, "created"));
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void getColumnValue_labelFallback_usesFirstDuplicateLabel(final boolean oracleValue) throws SQLException {
        final Object value = oracleValue ? new oracle.sql.DATE(TIMESTAMP) : DATE;
        final ResultSet resultSet = resultSet(value, "java.sql.Timestamp");
        final ResultSetMetaData metadata = resultSet.getMetaData();
        when(resultSet.findColumn("created")).thenThrow(new SQLFeatureNotSupportedException("Label lookup unsupported"));
        when(metadata.getColumnCount()).thenReturn(2);
        when(metadata.getColumnLabel(1)).thenReturn("CREATED");
        when(metadata.getColumnLabel(2)).thenReturn("created");
        when(metadata.getColumnClassName(2)).thenReturn("java.sql.Date");
        when(resultSet.getDate(2)).thenReturn(DATE);

        assertSame(TIMESTAMP, JdbcUtil.getColumnValue(resultSet, "created"));
        verify(metadata, never()).getColumnName(1);
    }

    @ParameterizedTest(name = "failure during name lookup = {0}")
    @ValueSource(booleans = { false, true })
    void getColumnValue_labelFallback_preservesMetadataFailure(final boolean nameLookup) throws SQLException {
        final ResultSet resultSet = resultSet(DATE, "java.sql.Timestamp");
        final ResultSetMetaData metadata = resultSet.getMetaData();
        final SQLException failure = new SQLException("Metadata unavailable");
        when(resultSet.findColumn("created")).thenThrow(new SQLFeatureNotSupportedException("Label lookup unsupported"));
        if (nameLookup) {
            when(metadata.getColumnLabel(1)).thenReturn("another_label");
            when(metadata.getColumnName(1)).thenThrow(failure);
        } else {
            when(metadata.getColumnLabel(1)).thenThrow(failure);
        }

        assertSame(failure, assertThrows(SQLException.class, () -> JdbcUtil.getColumnValue(resultSet, "created")));
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        } else if (type == boolean.class) {
            return false;
        } else if (type == char.class) {
            return (char) 0;
        } else if (type == byte.class) {
            return (byte) 0;
        } else if (type == short.class) {
            return (short) 0;
        } else if (type == int.class) {
            return 0;
        } else if (type == long.class) {
            return 0L;
        } else if (type == float.class) {
            return 0F;
        } else {
            return 0D;
        }
    }

    private static Stream<Boolean> labelPresence() {
        return Stream.of(false, true);
    }

    private static ResultSet resultSet(final Object value, final String metadataClass) throws SQLException {
        final ResultSet resultSet = mock(ResultSet.class);
        final ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(resultSet.getMetaData()).thenReturn(metadata);
        when(resultSet.findColumn("created")).thenReturn(1);
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
