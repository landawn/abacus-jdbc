package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collection;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.Throwables;

class DataTransferUtilTest extends DataTransferUtilTestSupport {

    // Regression: setFetchForLargeResult must enable row-by-row streaming for MariaDB too, not just
    // MySQL. MariaDB shares MySQL's protocol-level requirement for setFetchSize(Integer.MIN_VALUE);
    // without it, the driver buffers the entire result set in client memory. The check matches both
    // MySQL and MariaDB by product name/version.
    //
    // setFetchForLargeResult is package-private; we invoke it reflectively against a connection
    // whose metadata reports productName="MariaDB".
    @Test
    public void testSetFetchForLargeResult_MariaDBUsesStreamingFetchSize() throws Exception {
        final Connection conn = mock(Connection.class);
        final DatabaseMetaData md = mock(DatabaseMetaData.class);
        final PreparedStatement stmt = mock(PreparedStatement.class);
        when(conn.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("MariaDB");
        when(md.getIdentifierQuoteString()).thenReturn("`");
        when(md.getDatabaseProductVersion()).thenReturn("11.0");

        final java.lang.reflect.Method m = DataTransferUtil.class.getDeclaredMethod("setFetchForLargeResult", Connection.class, PreparedStatement.class,
                int.class);
        m.setAccessible(true);
        m.invoke(null, conn, stmt, 1000);

        verify(stmt).setFetchDirection(ResultSet.FETCH_FORWARD);
        // Pre-fix: stmt.setFetchSize(1000); post-fix: stmt.setFetchSize(Integer.MIN_VALUE).
        verify(stmt).setFetchSize(Integer.MIN_VALUE);
        // Sanity: ensure the non-streaming branch was NOT taken.
        verify(stmt, never()).setFetchSize(1000);
    }

    @Test
    public void testSetFetchForLargeResult_PostgreSQLUsesProvidedFetchSize() throws Exception {
        // Sanity check: a non-MySQL/MariaDB vendor still receives the user-supplied fetchSize.
        final Connection conn = mock(Connection.class);
        final DatabaseMetaData md = mock(DatabaseMetaData.class);
        final PreparedStatement stmt = mock(PreparedStatement.class);
        when(conn.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(md.getIdentifierQuoteString()).thenReturn("\"");
        when(md.getDatabaseProductVersion()).thenReturn("16.0");

        final java.lang.reflect.Method m = DataTransferUtil.class.getDeclaredMethod("setFetchForLargeResult", Connection.class, PreparedStatement.class,
                int.class);
        m.setAccessible(true);
        m.invoke(null, conn, stmt, 5000);

        verify(stmt).setFetchSize(5000);
        verify(stmt, never()).setFetchSize(Integer.MIN_VALUE);
    }

    // Test for resultSetParameterSetter

    @Test
    public void testResultSetParameterSetter() throws SQLException {
        // Setup
        Jdbc.ColumnGetter<String> columnGetter = (rs, columnIndex) -> rs.getString(columnIndex).toUpperCase();

        PreparedQuery preparedQuery = mock(PreparedQuery.class);
        when(mockResultSet.getString(1)).thenReturn("value1");
        when(mockResultSet.getString(2)).thenReturn("value2");
        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);

        // Execute
        Throwables.BiConsumer<PreparedQuery, ResultSet, SQLException> setter = DataTransferUtil.newResultSetParameterSetter(columnGetter);
        assertNotNull(setter);
        setter.accept(preparedQuery, mockResultSet);

        // Verify
        verify(preparedQuery).setObject(1, "VALUE1");
        verify(preparedQuery).setObject(2, "VALUE2");
    }

    @Test
    public void testResultSetParameterSetter_ZeroColumns() throws SQLException {
        Jdbc.ColumnGetter<String> columnGetter = (rs, columnIndex) -> rs.getString(columnIndex);
        PreparedQuery preparedQuery = mock(PreparedQuery.class);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(0);

        Throwables.BiConsumer<PreparedQuery, ResultSet, SQLException> setter = DataTransferUtil.newResultSetParameterSetter(columnGetter);
        assertNotNull(setter);
        setter.accept(preparedQuery, mockResultSet);

        verifyNoInteractions(preparedQuery);
    }

    @Test
    public void testResultSetParameterSetterRejectsNullColumnGetterImmediately() {
        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.newResultSetParameterSetter(null));
    }

    @Test
    public void testbatchIntervalTooLargeForMillisecondsIsRejectedAsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> DataTransferUtil.copyFrom(mockPreparedStatement).batchInterval(Duration.ofSeconds(Long.MAX_VALUE)));
    }

    @Test
    public void testFluentBuildersAreCanonicalApi() throws NoSuchMethodException {
        assertFalse(DataTransferUtil.class.getMethod("importFrom", Dataset.class).isAnnotationPresent(Deprecated.class));
        assertFalse(DataTransferUtil.DatasetImportBuilder.class.getMethod("columns", Collection.class).isAnnotationPresent(Deprecated.class));
        assertFalse(DataTransferUtil.DatasetImportBuilder.class.getMethod("batchInterval", Duration.class).isAnnotationPresent(Deprecated.class));
        assertFalse(
                DataTransferUtil.DatasetImportBuilder.class.getMethod("parameterSetter", Throwables.BiConsumer.class).isAnnotationPresent(Deprecated.class));

        assertTrue(DataTransferUtil.class.getMethod("importData", Dataset.class, Collection.class, Connection.class, String.class, int.class, long.class)
                .isAnnotationPresent(Deprecated.class));
        assertFalse(DataTransferUtil.class.getMethod("importData", Dataset.class, DataSource.class, String.class).isAnnotationPresent(Deprecated.class));
    }
}
