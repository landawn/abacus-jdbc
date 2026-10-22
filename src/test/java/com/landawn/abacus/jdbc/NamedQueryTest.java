package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLType;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.ImmutableList;

class NamedQueryTest extends NamedQueryTestSupport {

    @Test
    public void testConstructor_InvalidParameterCount() {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("param1"));
        when(mockParsedSql.parameterCount()).thenReturn(2);

        assertThrows(IllegalArgumentException.class, () -> new NamedQuery(mockPreparedStatement, mockParsedSql));
    }

    @Test
    public void testConstructorNullParsedSqlClosesStatement() throws SQLException {
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> new NamedQuery(mockPreparedStatement, null));

        assertTrue(thrown.getMessage().contains("namedSql"));
        verify(mockPreparedStatement).close();
    }

    @Test
    public void testEntityTypeCheckPrecedesParameterNamesNullCheck() throws SQLException {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setParameters("not an entity", (Collection<String>) null));

        assertTrue(failure.getMessage().contains("Unsupported parameter type: java.lang.String"));
        verify(mockPreparedStatement, times(1)).close();
    }

    @Test
    public void testMissingNameIsCheckedBeforeBigIntegerOverflowInBothLookupStrategies() throws SQLException {
        for (final String sql : List.of("SELECT :param1, :param2", "SELECT :param1, :param2, :param3, :param4, :param5")) {
            final PreparedStatement stmt = mock(PreparedStatement.class);
            final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse(sql));

            final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> query.setLong("missing", BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)));

            assertTrue(failure.getMessage().contains("Named parameter not found: missing"));
            verify(stmt, times(1)).close();
            verify(stmt, never()).setLong(anyInt(), anyLong());
        }
    }

    // Like AbstractQuery.setLong(int, BigInteger) and CallableQuery.setLong(String, BigInteger), a binding failure of a
    // non-null value closes the query, not only an overflow.
    @Test
    public void testBigIntegerBindingFailureClosesQueryInBothLookupStrategies() throws SQLException {
        for (final String sql : List.of("SELECT :param1, :param2", "SELECT :param1, :param2, :param3, :param4, :param5")) {
            final PreparedStatement stmt = mock(PreparedStatement.class);
            final SQLException driverFailure = new SQLException("rejected");
            doThrow(driverFailure).when(stmt).setLong(1, 42L);
            final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse(sql));

            assertSame(driverFailure, assertThrows(SQLException.class, () -> query.setLong("param1", BigInteger.valueOf(42L))));
            verify(stmt, times(1)).close();
        }

        // Guard: SQL NULL for a null value keeps the plain-setter behavior (no close on driver failure).
        final PreparedStatement stmt = mock(PreparedStatement.class);
        doThrow(new SQLException("rejected")).when(stmt).setNull(1, java.sql.Types.BIGINT);
        final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse("SELECT :param1"));

        assertThrows(SQLException.class, () -> query.setLong("param1", (BigInteger) null));
        verify(stmt, never()).close();
    }

    @Test
    public void testNamedParameterNotFoundListsEachNameOnce() throws SQLException {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse("SELECT :p, :other, :p, :p"));

        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> query.setString("missing", "x"));

        assertTrue(failure.getMessage().contains("Available named parameters: [p, other]. SQL:"), failure.getMessage());
    }

    @Test
    public void testMissingNameIsCheckedBeforeCharSequenceConversionInBothLookupStrategies() throws SQLException {
        final CharSequence value = mock(CharSequence.class);
        when(value.toString()).thenThrow(new AssertionError("The value must not be converted before validating the name"));

        for (final String sql : List.of("SELECT :param1, :param2", "SELECT :param1, :param2, :param3, :param4, :param5")) {
            for (final boolean national : List.of(false, true)) {
                final PreparedStatement stmt = mock(PreparedStatement.class);
                final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse(sql));

                final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                    if (national) {
                        query.setNString("missing", value);
                    } else {
                        query.setString("missing", value);
                    }
                });

                assertTrue(failure.getMessage().contains("Named parameter not found: missing"));
                verify(stmt, times(1)).close();
            }
        }
    }

    @Test
    public void testMissingNameIsCheckedBeforeSqlTypeInBothLookupStrategies() throws SQLException {
        for (final String sql : List.of("SELECT :param1, :param2", "SELECT :param1, :param2, :param3, :param4, :param5")) {
            for (final boolean withScale : List.of(false, true)) {
                final PreparedStatement stmt = mock(PreparedStatement.class);
                final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse(sql));

                final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                    if (withScale) {
                        query.setObject("missing", null, (SQLType) null, 0);
                    } else {
                        query.setObject("missing", null, (SQLType) null);
                    }
                });

                assertTrue(failure.getMessage().contains("Named parameter not found: missing"));
                verify(stmt, times(1)).close();
            }
        }
    }

    @Test
    public void testMissingNameIsCheckedBeforeTypeHandlerInBothLookupStrategies() throws SQLException {
        for (final String sql : List.of("SELECT :param1, :param2", "SELECT :param1, :param2, :param3, :param4, :param5")) {
            final PreparedStatement stmt = mock(PreparedStatement.class);
            final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse(sql));

            final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> query.setObject("missing", null, (Type<Object>) null));

            assertTrue(failure.getMessage().contains("Named parameter not found: missing"));
            verify(stmt, times(1)).close();
        }
    }

    @Test
    public void testMultipleFrequencyOfParameter() throws SQLException {
        // Create a query with duplicate parameter names
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("id", "id", "name"));
        when(mockParsedSql.parameterCount()).thenReturn(3);
        namedQuery = new NamedQuery(mockPreparedStatement, mockParsedSql);

        NamedQuery result = namedQuery.setInt("id", 123);

        // Both frequency of "id" should be set
        verify(mockPreparedStatement).setInt(1, 123);
        verify(mockPreparedStatement).setInt(2, 123);
        assertSame(namedQuery, result);
    }

    @Test
    public void testNonEntityTypeIsRejected() throws SQLException {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setParameters("not an entity", List.of("param1")));

        assertTrue(failure.getMessage().contains("Unsupported parameter type: java.lang.String"));
        verify(mockPreparedStatement, times(1)).close();
    }
}
