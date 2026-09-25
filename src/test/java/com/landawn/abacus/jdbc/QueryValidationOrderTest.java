package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLType;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.query.ParsedSql;

class QueryValidationOrderTest {

    public static final class Entity {
        private int reads;

        public String getName() {
            reads++;
            return "name";
        }

        public void setName(final String name) {
            // A writable property makes this a bean for the parameter mapper.
        }
    }

    @Test
    void closedPreparedQueryValidatesBindingArgumentsButStillRejectsBatchAndConfiguration() {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final PreparedQuery query = new PreparedQuery(stmt);
        query.close();
        clearInvocations(stmt);

        assertTrue(assertThrows(IllegalArgumentException.class, () -> query.setObject(1, null, (SQLType) null)).getMessage().contains("sqlType"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> query.setParametersFrom(0, (Object[]) null)).getMessage()
                .contains("startParameterIndex"));
        assertThrows(IllegalStateException.class, () -> query.addBatchParameters(List.of()));
        assertThrows(IllegalStateException.class, () -> query.configureStatement((Jdbc.ParametersSetter<PreparedStatement>) null));
        verifyNoInteractions(stmt);
    }

    @Test
    void closedNamedQueryValidatesUnknownNameBeforeTypeAndStillRejectsBatch() {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse("select :name"));
        query.close();
        clearInvocations(stmt);

        assertTrue(assertThrows(IllegalArgumentException.class, () -> query.setObject("missing", null, (SQLType) null)).getMessage().contains("missing"));
        assertThrows(IllegalStateException.class, () -> query.addBatchParameters(List.of()));
        verifyNoInteractions(stmt);
    }

    @Test
    void closedCallableQueryValidatesBindingTypeButStillRejectsOutRegistration() {
        final CallableStatement stmt = mock(CallableStatement.class);
        final CallableQuery query = new CallableQuery(stmt);
        query.close();
        clearInvocations(stmt);

        assertThrows(IllegalStateException.class, () -> query.registerOutParameter(0, (SQLType) null));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> query.setObject("name", null, (SQLType) null)).getMessage().contains("sqlType"));
        verifyNoInteractions(stmt);
    }

    @Test
    void closedNamedAndCallableQueriesDelegateIndexedAndNamedBindingsToTheDriver() throws SQLException {
        final PreparedStatement namedStmt = mock(PreparedStatement.class);
        final NamedQuery named = new NamedQuery(namedStmt, ParsedSql.parse("select :name"));
        final CallableStatement callableStmt = mock(CallableStatement.class);
        final CallableQuery callable = new CallableQuery(callableStmt);
        named.close();
        callable.close();
        clearInvocations(namedStmt, callableStmt);

        // The mock driver accepts binding after close; these setters add no query-state rejection.
        assertSame(named, named.setString("name", "named"));
        assertSame(named, named.setString(1, "indexed"));
        assertSame(named, named.setStringForIndices("repeated", 1));
        assertSame(callable, callable.setString("name", "callable"));
        assertSame(callable, callable.setString(1, "inherited"));

        verify(namedStmt).setString(1, "named");
        verify(namedStmt).setString(1, "indexed");
        verify(namedStmt).setString(1, "repeated");
        verify(callableStmt).setString("name", "callable");
        verify(callableStmt).setString(1, "inherited");
        verifyNoMoreInteractions(namedStmt, callableStmt);
    }

    @Test
    void closedQueriesKeepEmptyBindingsAndBindingCallbacksAvailable() throws SQLException {
        final PreparedStatement preparedStmt = mock(PreparedStatement.class);
        final PreparedQuery prepared = new PreparedQuery(preparedStmt);
        final PreparedStatement namedStmt = mock(PreparedStatement.class);
        final NamedQuery named = new NamedQuery(namedStmt, ParsedSql.parse("select :name"));
        final CallableStatement callableStmt = mock(CallableStatement.class);
        final CallableQuery callable = new CallableQuery(callableStmt);
        prepared.close();
        named.close();
        callable.close();
        clearInvocations(preparedStmt, namedStmt, callableStmt);

        assertSame(prepared, prepared.setParameters(new int[0]));
        assertSame(prepared, prepared.setParametersFrom(1, List.of()));
        assertSame(named, named.setParameters(Map.of()));
        assertSame(callable, callable.setParameters(Map.of()));

        final AtomicInteger callbacks = new AtomicInteger();
        prepared.setParameters(stmt -> {
            assertSame(preparedStmt, stmt);
            callbacks.incrementAndGet();
        });
        prepared.setParameters(null, (stmt, parameters) -> {
            assertSame(preparedStmt, stmt);
            assertNull(parameters);
            callbacks.incrementAndGet();
        });
        prepared.settParameters(query -> {
            assertSame(prepared, query);
            callbacks.incrementAndGet();
        });
        prepared.settParameters(null, (query, parameters) -> {
            assertSame(prepared, query);
            assertNull(parameters);
            callbacks.incrementAndGet();
        });
        named.setParameters(null, (sql, query, parameters) -> {
            assertSame(named, query);
            assertEquals(List.of("name"), sql.namedParameters());
            assertNull(parameters);
            callbacks.incrementAndGet();
        });
        callable.setParameters(stmt -> {
            assertSame(callableStmt, stmt);
            callbacks.incrementAndGet();
        });

        final SQLException callbackFailure = new SQLException("binding callback failed");
        assertSame(callbackFailure, assertThrows(SQLException.class, () -> prepared.settParameters(query -> {
            throw callbackFailure;
        })));
        assertEquals(6, callbacks.get());
        verifyNoInteractions(preparedStmt, namedStmt, callableStmt);
    }

    @Test
    void closedJdbcStatementsReportSqlExceptionsFromBinding() throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:closed-query-input-binding");
                PreparedQuery prepared = new PreparedQuery(connection.prepareStatement("select ?"));
                NamedQuery named = new NamedQuery(connection.prepareStatement("select ?"), ParsedSql.parse("select :value"));
                CallableQuery callable = new CallableQuery(connection.prepareCall("call ABS(?)"))) {
            prepared.close();
            named.close();
            callable.close();

            assertThrows(SQLException.class, () -> prepared.setInt(1, 1));
            assertThrows(SQLException.class, () -> named.setInt("value", 1));
            assertThrows(SQLException.class, () -> named.setInt(1, 1));
            assertThrows(SQLException.class, () -> callable.setInt(1, 1));
        }
    }

    @Test
    void namedBeanValidatesAllPropertiesBeforeReadingOrBinding() throws Exception {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse("select :name, :missing"));
        final Entity entity = new Entity();

        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> query.setParameters(entity));

        assertTrue(failure.getMessage().contains("missing"));
        assertEquals(0, entity.reads);
        verify(stmt).close();
        verifyNoMoreInteractions(stmt);
    }

    @Test
    void namedSelectionValidatesAllPropertiesBeforeReadingOrBinding() throws Exception {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse("select :name, :missing"));
        final Entity entity = new Entity();

        assertThrows(IllegalArgumentException.class, () -> query.setParameters(entity, List.of("name", "missing")));

        assertEquals(0, entity.reads);
        verify(stmt).close();
        verifyNoMoreInteractions(stmt);
    }

    @Test
    void callableSelectionValidatesAllPropertiesBeforeReadingOrBinding() throws Exception {
        final CallableStatement stmt = mock(CallableStatement.class);
        final CallableQuery query = new CallableQuery(stmt);
        final Entity entity = new Entity();

        assertThrows(IllegalArgumentException.class, () -> query.setParameters(entity, List.of("name", "missing")));

        assertEquals(0, entity.reads);
        verify(stmt).clearParameters();
        verify(stmt).close();
        verifyNoMoreInteractions(stmt);
    }

    @Test
    void beanClassIsValidatedBeforeTheLaterNameCollection() {
        final NamedQuery named = new NamedQuery(mock(PreparedStatement.class), ParsedSql.parse("select :name"));
        final CallableQuery callable = new CallableQuery(mock(CallableStatement.class));

        assertTrue(assertThrows(IllegalArgumentException.class, () -> named.setParameters(new Object(), (Collection<String>) null))
                .getMessage().contains("Unsupported parameter type"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> callable.setParameters(new Object(), (Collection<String>) null))
                .getMessage().contains("Unsupported parameter type"));
    }
}
