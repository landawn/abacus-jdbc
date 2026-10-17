package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.exception.UncheckedSQLException;

public class JdbcCodeGenerationMetadataTest extends JdbcCodeGenerationUtilTestSupport {
    private static final String QUERY = "SELECT * FROM order_history WHERE 1 > 2";
    private static final AtomicInteger EVALUATED_ROWS = new AtomicInteger();

    public static long countRow(final long value) {
        EVALUATED_ROWS.incrementAndGet();
        return value;
    }

    @Test
    void preparedMetadataGeneratesSourceAndLooksUpPrimaryKeysWithoutExecutingQuery() throws Exception {
        setupFullGenerateEntityClassMock();
        when(preparedStatement.getMetaData()).thenReturn(resultSetMetaData);

        final String source = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", QUERY);

        assertTrue(source.contains("private long id;"), source);
        assertTrue(source.contains("private String status;"), source);
        verify(databaseMetaData).getPrimaryKeys(null, null, "order_history");
        verify(preparedStatement, never()).executeQuery();
        verify(preparedStatement).close();
        verify(connection, never()).close();
        verifyNoInteractions(resultSet);
    }

    @Test
    void nullPreparedMetadataFallsBackAndClosesResultSet() throws Exception {
        setupFullGenerateEntityClassMock();
        assertTrue(JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", QUERY).contains("private long id;"));
        verify(preparedStatement).executeQuery();
        verify(resultSet).close();
        verify(preparedStatement).close();
        verify(connection, never()).close();
    }

    @Test
    void unsupportedPreparedMetadataFallsBackToExecution() throws Exception {
        setupFullGenerateEntityClassMock();
        when(preparedStatement.getMetaData()).thenThrow(new SQLFeatureNotSupportedException("metadata requires execution"));
        assertTrue(JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", QUERY).contains("private long id;"));
        verify(preparedStatement).executeQuery();
        verify(resultSet).close();
        verify(preparedStatement).close();
    }

    @Test
    void legacyDriverWithoutPreparedMetadataMethodFallsBackToExecution() throws Exception {
        setupFullGenerateEntityClassMock();
        when(preparedStatement.getMetaData()).thenThrow(new AbstractMethodError("old driver"));
        assertTrue(JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", QUERY).contains("private long id;"));
        verify(preparedStatement).executeQuery();
        verify(resultSet).close();
    }

    @Test
    void describeSqlFailureFallsBackToExecution() throws Exception {
        // e.g. SQL Server cannot describe a query that uses a temp table, but executes it.
        setupFullGenerateEntityClassMock();
        when(preparedStatement.getMetaData()).thenThrow(new SQLException("The metadata could not be determined"));
        assertTrue(JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", QUERY).contains("private long id;"));
        verify(preparedStatement).executeQuery();
        verify(resultSet).close();
        verify(preparedStatement).close();
    }

    @Test
    void unsupportedOperationAndEmptyDescriptionFallBackToExecution() throws Exception {
        setupFullGenerateEntityClassMock();
        when(preparedStatement.getMetaData()).thenThrow(new UnsupportedOperationException("not implemented"));
        assertTrue(JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", QUERY).contains("private long id;"));

        final ResultSetMetaData empty = mock(ResultSetMetaData.class);
        when(empty.getColumnCount()).thenReturn(0);
        reset(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(preparedStatement.getMetaData()).thenReturn(empty);
        assertTrue(JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", QUERY).contains("private long id;"));
        verify(preparedStatement).executeQuery();
    }

    @Test
    void untypedDescribedColumnsFallBackToExecution() throws Exception {
        // SQLite reports a column's class from the current row's value: before execution there is no row, so every
        // column is described as java.lang.Object, while the executed result reports the types of its first row.
        setupFullGenerateEntityClassMock();
        final ResultSetMetaData untyped = mock(ResultSetMetaData.class);
        when(untyped.getColumnCount()).thenReturn(3);
        when(untyped.getColumnClassName(1)).thenReturn("java.lang.Object");
        when(untyped.getColumnClassName(2)).thenReturn("java.lang.Object");
        when(untyped.getColumnClassName(3)).thenReturn("java.lang.Object");
        when(preparedStatement.getMetaData()).thenReturn(untyped);

        String source = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", QUERY);
        assertTrue(source.contains("private long id;"), source);
        assertTrue(source.contains("private String status;"), source);
        verify(preparedStatement).executeQuery();
        verify(resultSet).close();

        // One column without a concrete class is enough: the executed metadata is used for every column.
        final ResultSetMetaData partlyTyped = mock(ResultSetMetaData.class);
        when(partlyTyped.getColumnCount()).thenReturn(3);
        when(partlyTyped.getColumnClassName(1)).thenReturn("java.lang.Long");
        when(partlyTyped.getColumnClassName(2)).thenReturn(null);
        when(partlyTyped.getColumnClassName(3)).thenReturn("java.lang.String");
        reset(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(preparedStatement.getMetaData()).thenReturn(partlyTyped);

        source = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", QUERY);
        assertTrue(source.contains("private java.sql.Timestamp createdAt;"), source);
        verify(preparedStatement).executeQuery();
    }

    @Test
    void executionFailureRemainsPrimaryAfterDescribeSqlFailure() throws Exception {
        // An ordinary describe SQLException can be a mere driver limitation (SQL Server temp tables), so the
        // execution failure is reported and the describe failure only attached to it.
        final SQLException describeFailure = new SQLException("The metadata could not be determined");
        final SQLException executionFailure = new SQLException("permission denied");
        when(preparedStatement.getMetaData()).thenThrow(describeFailure);
        when(preparedStatement.executeQuery()).thenThrow(executionFailure);
        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class,
                () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", QUERY));
        assertSame(executionFailure, thrown.getCause());
        assertSame(describeFailure, executionFailure.getSuppressed()[0]);
        assertEquals(0, describeFailure.getSuppressed().length);
        verify(preparedStatement).executeQuery();
        verify(preparedStatement).close();
        verify(connection, never()).close();
    }

    @Test
    void failureAfterSuccessfulFallbackExecutionRemainsPrimary() throws Exception {
        setupFullGenerateEntityClassMock();
        final SQLException describeFailure = new SQLException("The metadata could not be determined");
        final SQLException primaryKeyFailure = new SQLException("primary keys unavailable");
        when(preparedStatement.getMetaData()).thenThrow(describeFailure);
        when(databaseMetaData.getPrimaryKeys(null, null, "order_history")).thenThrow(primaryKeyFailure);
        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class,
                () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", QUERY));
        assertSame(primaryKeyFailure, thrown.getCause());
        assertSame(describeFailure, thrown.getSuppressed()[0]);
        verify(preparedStatement).executeQuery();
        verify(resultSet).close();
        verify(preparedStatement).close();
    }

    @Test
    void executionFailureRemainsPrimaryAfterUnsupportedDescription() throws Exception {
        final SQLFeatureNotSupportedException unsupported = new SQLFeatureNotSupportedException("metadata requires execution");
        final SQLException executionFailure = new SQLException("table not found");
        when(preparedStatement.getMetaData()).thenThrow(unsupported);
        when(preparedStatement.executeQuery()).thenThrow(executionFailure);
        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class,
                () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", QUERY));
        assertSame(executionFailure, thrown.getCause());
        assertSame(unsupported, executionFailure.getSuppressed()[0]);
    }

    @Test
    void h2MetadataGenerationDoesNotEvaluateQueryRows() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:metadata_only_" + System.nanoTime());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE ALIAS count_row FOR 'com.landawn.abacus.jdbc.JdbcCodeGenerationMetadataTest.countRow'");
            EVALUATED_ROWS.set(0);
            final String source = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "generated_entity",
                    "SELECT count_row(X) AS id FROM SYSTEM_RANGE(1, 10000)",
                    new JdbcCodeGenerationUtil.EntityCodeConfig().setIdFields(List.of("id")));
            assertTrue(source.contains("private long id;"), source);
            assertEquals(0, EVALUATED_ROWS.get(), "Generating source only needs metadata, not evaluation of SELECT rows");
        }
    }
}
