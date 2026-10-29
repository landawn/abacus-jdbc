package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

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
        verify(preparedStatement, never()).setMaxRows(anyInt());
        verify(preparedStatement).close();
        verify(connection, never()).close();
        verifyNoInteractions(resultSet);
    }

    @Test
    void nullPreparedMetadataFallsBackAndClosesResultSet() throws Exception {
        setupFullGenerateEntityClassMock();
        assertTrue(JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", QUERY).contains("private long id;"));
        final InOrder execution = inOrder(preparedStatement);
        execution.verify(preparedStatement).setMaxRows(1);
        execution.verify(preparedStatement).executeQuery();
        verify(resultSet).close();
        verify(preparedStatement).close();
        verify(connection, never()).close();
    }

    @Test
    void unsupportedRowLimitsRetainExecutionFallback() throws Exception {
        for (final Throwable unsupported : List.of(new SQLFeatureNotSupportedException("maxRows"), new UnsupportedOperationException("maxRows"),
                new AbstractMethodError("maxRows"))) {
            reset(preparedStatement);
            setupFullGenerateEntityClassMock();
            when(preparedStatement.executeQuery()).thenReturn(resultSet);
            doThrow(unsupported).when(preparedStatement).setMaxRows(1);

            final String source = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", QUERY);

            assertTrue(source.contains("private long id;"), source);
            verify(preparedStatement).executeQuery();
            verify(preparedStatement).close();
        }
        verify(resultSet, times(3)).close();
        verify(connection, never()).close();
    }

    @ParameterizedTest
    @ValueSource(strings = { "0A000", "0A001" })
    void unsupportedRowLimitSqlStateRetainsExecutionFallback(final String sqlState) throws Exception {
        setupFullGenerateEntityClassMock();
        doThrow(new SQLException("driver rejected the row limit", sqlState)).when(preparedStatement).setMaxRows(1);

        final String source = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", QUERY);

        assertTrue(source.contains("private long id;"), source);
        final InOrder execution = inOrder(preparedStatement);
        execution.verify(preparedStatement).setMaxRows(1);
        execution.verify(preparedStatement).executeQuery();
        verify(resultSet).close();
        verify(preparedStatement).close();
        verify(connection, never()).close();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "08006", "42000", "HY000" })
    void rowLimitSqlFailureRemainsPrimaryAfterDescribeFailure(final String sqlState) throws Exception {
        final SQLException describeFailure = new SQLException("metadata unavailable");
        final SQLException limitFailure = new SQLException("setMaxRows not supported", sqlState);
        when(preparedStatement.getMetaData()).thenThrow(describeFailure);
        doThrow(limitFailure).when(preparedStatement).setMaxRows(1);

        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class,
                () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", QUERY));

        assertSame(limitFailure, thrown.getCause());
        assertEquals(1, limitFailure.getSuppressed().length);
        assertSame(describeFailure, limitFailure.getSuppressed()[0]);
        verify(preparedStatement, never()).executeQuery();
        verify(preparedStatement).close();
        verifyNoInteractions(resultSet);
        verify(connection, never()).close();
    }

    @Test
    void unrelatedRowLimitFailuresAreNotSwallowed() throws Exception {
        for (final Throwable failure : List.of(new IllegalStateException("driver state"), new LinkageError("driver linkage"))) {
            reset(preparedStatement);
            doThrow(failure).when(preparedStatement).setMaxRows(1);

            assertSame(failure, assertThrows(failure.getClass(), () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", QUERY)));
            verify(preparedStatement, never()).executeQuery();
            verify(preparedStatement).close();
        }
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
        verify(preparedStatement).setMaxRows(1);
        verify(preparedStatement).executeQuery();
        verify(resultSet, never()).next();
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
    void untypedColumnClassesAreDerivedFromSqlTypes() throws Exception {
        // SQLite reports a column's class from the current row's value, so a result without rows (as the WHERE 1 > 2
        // metadata query of table-name generation) reports every column as java.lang.Object, while its SQL types still
        // reflect the declared column types. The query path describes, falls back to execution and derives the same types.
        setupFullGenerateEntityClassMock();
        when(resultSetMetaData.getColumnClassName(1)).thenReturn("java.lang.Object");
        when(resultSetMetaData.getColumnClassName(2)).thenReturn("java.lang.Object");
        when(resultSetMetaData.getColumnClassName(3)).thenReturn(null);
        when(preparedStatement.getMetaData()).thenReturn(resultSetMetaData);

        for (final String source : List.of(JdbcCodeGenerationUtil.generateEntityClass(connection, "order_history"),
                JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", QUERY))) {
            assertTrue(source.contains("private long id;"), source);
            assertTrue(source.contains("private java.sql.Timestamp createdAt;"), source);
            assertTrue(source.contains("private String status;"), source);
        }

        verify(preparedStatement, times(2)).executeQuery();
    }

    @Test
    void untypedColumnClassFallbackUsesTypeNameAndKeepsUnknownTypesAsObject() throws Exception {
        final ResultSet rs = mock(ResultSet.class);
        final ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(md);
        final Object[][] columns = { { "created", Types.DATE, "DATETIME" }, { "amount", Types.OTHER, "decimal(10, 2)" }, { "payload", Types.BLOB, "BLOB" },
                { "shape", Types.OTHER, "GEOMETRY" }, { "flag", Types.BOOLEAN, "BOOLEAN" }, { "total", Types.INTEGER, "INTEGER" },
                { "nothing", Types.NULL, null } };
        when(md.getColumnCount()).thenReturn(columns.length);

        for (int i = 0; i < columns.length; i++) {
            when(md.getColumnLabel(i + 1)).thenReturn((String) columns[i][0]);
            when(md.getColumnClassName(i + 1)).thenReturn("java.lang.Object");
            when(md.getColumnType(i + 1)).thenReturn((Integer) columns[i][1]);
            when(md.getColumnTypeName(i + 1)).thenReturn((String) columns[i][2]);
        }

        final String source = JdbcCodeGenerationUtil.generateEntityClass("sample", rs, null);

        // SQLite reports DATETIME columns as Types.DATE, which would drop their time of day.
        assertTrue(source.contains("private java.sql.Timestamp created;"), source);
        assertTrue(source.contains("private java.math.BigDecimal amount;"), source);
        assertTrue(source.contains("private byte[] payload;"), source);
        assertTrue(source.contains("private Object shape;"), source);
        assertTrue(source.contains("private boolean flag;"), source);
        assertTrue(source.contains("private int total;"), source);
        assertTrue(source.contains("private Object nothing;"), source);

        // The configured conversions apply to a derived type as to a reported one.
        final String boxed = JdbcCodeGenerationUtil.generateEntityClass("sample", rs,
                JdbcCodeGenerationUtil.EntityCodeConfig.builder().useBoxedTypes(true).mapBigDecimalToDouble(true).build());
        assertTrue(boxed.contains("private Double amount;"), boxed);
        assertTrue(boxed.contains("private Integer total;"), boxed);
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

    // The xerial SQLite driver fails getPrimaryKeys with "Table not found" for a name that is not a table (other drivers
    // return no rows), so a descriptive entity name such as "UserProfile" made generation fail.
    @Test
    void primaryKeyLookupIsSkippedWhenEntityNameNamesNoTable() throws Exception {
        setupFullGenerateEntityClassMock();
        final ResultSet noTables = tables();
        when(databaseMetaData.getTables(null, null, "UserProfile", null)).thenReturn(noTables);
        when(databaseMetaData.getPrimaryKeys(any(), any(), any())).thenThrow(new SQLException("Table not found: 'UserProfile'"));

        String source = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "UserProfile", QUERY);
        assertTrue(source.contains("public class UserProfile {"), source);
        assertTrue(source.contains("private long id;"), source);
        assertFalse(source.contains("@Id"), source);
        verify(noTables).close();

        // The name is a search pattern: a table that matches it only through the '_' wildcard is a different table.
        final ResultSet otherTable = tables("userXprofile");
        when(databaseMetaData.getTables(null, null, "user_profile", null)).thenReturn(otherTable);
        source = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "user_profile", QUERY);
        assertFalse(source.contains("@Id"), source);
        verify(databaseMetaData, never()).getPrimaryKeys(any(), any(), any());
    }

    @Test
    void primaryKeysOfAnExistingTableAreLookedUpAndTheirFailurePropagates() throws Exception {
        setupFullGenerateEntityClassMock();
        // SQLite matches table names ignoring case.
        final ResultSet upperCaseTable = tables("ORDER_HISTORY");
        when(databaseMetaData.getTables(null, null, "order_history", null)).thenReturn(upperCaseTable);
        final ResultSet primaryKeys = mock(ResultSet.class);
        when(primaryKeys.next()).thenReturn(true, false);
        when(primaryKeys.getString("COLUMN_NAME")).thenReturn("id");
        when(databaseMetaData.getPrimaryKeys(null, null, "order_history")).thenReturn(primaryKeys);
        assertTrue(JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", QUERY).contains("@Id"));

        final SQLException primaryKeyFailure = new SQLException("primary keys unavailable");
        final ResultSet table = tables("order_history");
        when(databaseMetaData.getTables(null, null, "order_history", null)).thenReturn(table);
        when(databaseMetaData.getPrimaryKeys(null, null, "order_history")).thenThrow(primaryKeyFailure);
        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class,
                () -> JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", QUERY));
        assertSame(primaryKeyFailure, thrown.getCause());
    }

    @Test
    void primaryKeysAreLookedUpWhenTableExistenceCannotBeDetermined() throws Exception {
        setupFullGenerateEntityClassMock();
        final ResultSet primaryKeys = mock(ResultSet.class);
        when(primaryKeys.next()).thenReturn(true, false);
        when(primaryKeys.getString("COLUMN_NAME")).thenReturn("id");
        when(databaseMetaData.getPrimaryKeys(any(), any(), any())).thenReturn(primaryKeys);
        when(databaseMetaData.getTables(any(), any(), any(), any())).thenThrow(new SQLFeatureNotSupportedException("getTables"));
        assertTrue(JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "order_history", QUERY).contains("@Id"));

        // A name containing the search-string escape can't be searched for exactly, so it is looked up without the check.
        reset(databaseMetaData);
        when(databaseMetaData.getSearchStringEscape()).thenReturn("\\");
        when(primaryKeys.next()).thenReturn(true, false);
        when(databaseMetaData.getPrimaryKeys(null, null, "a\\b")).thenReturn(primaryKeys);
        assertTrue(JdbcCodeGenerationUtil
                .generateEntityClassByQuery(connection, "\"a\\b\"", QUERY, JdbcCodeGenerationUtil.EntityCodeConfig.builder().className("Ab").build())
                .contains("@Id"));
        verify(databaseMetaData, never()).getTables(any(), any(), any(), any());
    }

    private static ResultSet tables(final String... tableNames) throws SQLException {
        final ResultSet tables = mock(ResultSet.class);
        final int[] row = { 0 };
        when(tables.next()).thenAnswer(invocation -> ++row[0] <= tableNames.length);
        when(tables.getString("TABLE_NAME")).thenAnswer(invocation -> tableNames[row[0] - 1]);
        return tables;
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
                    "SELECT count_row(X) AS id FROM SYSTEM_RANGE(1, 10000)", new JdbcCodeGenerationUtil.EntityCodeConfig().setIdFields(List.of("id")));
            assertTrue(source.contains("private long id;"), source);
            assertEquals(0, EVALUATED_ROWS.get(), "Generating source only needs metadata, not evaluation of SELECT rows");
        }
    }

    @Test
    void h2ExecutionFallbackEvaluatesAtMostFirstRow() throws Exception {
        final String query = "SELECT count_row(X) AS id FROM SYSTEM_RANGE(1, 10000)";
        try (Connection realConnection = DriverManager.getConnection("jdbc:h2:mem:metadata_fallback_" + System.nanoTime());
             Statement statement = realConnection.createStatement()) {
            statement.execute("CREATE ALIAS count_row FOR 'com.landawn.abacus.jdbc.JdbcCodeGenerationMetadataTest.countRow'");
            try (PreparedStatement realStatement = realConnection.prepareStatement(query)) {
                final PreparedStatement fallbackStatement = mock(PreparedStatement.class, delegatesTo(realStatement));
                doReturn(null).when(fallbackStatement).getMetaData();
                when(connection.prepareStatement(query)).thenReturn(fallbackStatement);
                EVALUATED_ROWS.set(0);

                final String source = JdbcCodeGenerationUtil.generateEntityClassByQuery(connection, "generated_entity", query,
                        new JdbcCodeGenerationUtil.EntityCodeConfig().setIdFields(List.of("id")));

                assertTrue(source.contains("private long id;"), source);
                assertEquals(1, EVALUATED_ROWS.get(), "The metadata fallback needs at most the first row, not all 10,000 rows");
                verify(fallbackStatement).setMaxRows(1);
                verify(fallbackStatement).executeQuery();
                assertTrue(realStatement.isClosed());
                assertFalse(realConnection.isClosed());
            }
        }
    }
}
