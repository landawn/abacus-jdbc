package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.util.EntityId;

class QueryNullArgumentValidationTest {

    @Test
    void requiredEntityIdIsRejectedWithItsParameterNameAndClosesQuery() throws Exception {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse("select :id"));

        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> query.setParameters((EntityId) null));

        assertTrue(failure.getMessage().contains("entityId"));
        assertTrue(query.isClosed);
        verify(stmt).close();
        verifyNoMoreInteractions(stmt);
    }

    @Test
    void nullEntityIdRemainsANoOpWhenTheQueryHasNoParameters() throws Exception {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse("select 1"));

        query.setParameters((EntityId) null);

        verifyNoInteractions(stmt);

        query.close();
        clearInvocations(stmt);
        query.setParameters((EntityId) null);
        verifyNoInteractions(stmt);
    }

    @Test
    void entityIdNullCheckStillRunsAfterQueryClose() {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse("select :id"));
        query.close();
        clearInvocations(stmt);

        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> query.setParameters((EntityId) null));
        assertTrue(failure.getMessage().contains("entityId"));

        verifyNoInteractions(stmt);
    }

    @Test
    void nullEntityIdValuesStillBindSqlNull() throws Exception {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final NamedQuery query = new NamedQuery(stmt, ParsedSql.parse("select :id"));

        query.setParameters(EntityId.of("id", null));

        verify(stmt).setObject(1, null);
        verifyNoMoreInteractions(stmt);
    }

    @Test
    void proxyConstructorRejectsNullWhileTheFactoryStillAcceptsIt() {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ResultSetProxy(null));

        assertTrue(failure.getMessage().contains("delegate"));
        assertNull(ResultSetProxy.wrap(null));
    }

    @Test
    void proxyPreservesDriverNullArgumentExceptions() throws Exception {
        final ResultSet delegate = mock(ResultSet.class);
        final NullPointerException failure = new NullPointerException("driver requires a column label");
        when(delegate.getString((String) null)).thenThrow(failure);
        final ResultSetProxy proxy = ResultSetProxy.wrap(delegate);

        assertSame(failure, assertThrows(NullPointerException.class, () -> proxy.getString((String) null)));
        verify(delegate).getString((String) null);
    }
}
