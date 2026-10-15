package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

import com.landawn.abacus.TestBase;

class JoinEntityDeleteOpsFailureTest extends TestBase {

    @ParameterizedTest(name = "unchecked={0}, collection={1}, byType={2}")
    @CsvSource({ "false, false, false", "false, false, true", "false, true, false", "false, true, true", "true, false, false", "true, false, true",
            "true, true, false", "true, true, true" })
    @SuppressWarnings("unchecked")
    void failedDeletePreservesItsCauseWhenTransactionRollbackAlsoFails(final boolean unchecked, final boolean collection, final boolean byType)
            throws SQLException {
        final JoinEntityDeleteOps<Parent, ?> dao = unchecked ? mock(UncheckedParentDao.class, CALLS_REAL_METHODS)
                : mock(CheckedParentDao.class, CALLS_REAL_METHODS);
        final DataSource dataSource = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        when(((DaoBase<Parent, ?>) dao).dataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        final Parent parent = new Parent();
        final List<Parent> parents = List.of(parent);
        final List<String> properties = List.of("orders", "addresses");
        final IllegalStateException primary = new IllegalStateException("delete failed");
        final SQLException rollbackFailure = new SQLException("rollback failed");
        doThrow(rollbackFailure).when(connection).rollback();
        if (collection) {
            doThrow(primary).when(dao).deleteJoinEntities(parents, "orders");
        } else {
            doThrow(primary).when(dao).deleteJoinEntities(parent, "orders");
        }

        try (MockedStatic<DaoUtil> metadata = mockStatic(DaoUtil.class,
                invocation -> invocation.getMethod().getName().equals("getJoinEntityPropNamesByType") ? properties : CALLS_REAL_METHODS.answer(invocation))) {
            final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> {
                if (collection) {
                    if (byType) {
                        dao.deleteJoinEntities(parents, Object.class);
                    } else {
                        dao.deleteJoinEntities(parents, properties);
                    }
                } else if (byType) {
                    dao.deleteJoinEntities(parent, Object.class);
                } else {
                    dao.deleteJoinEntities(parent, properties);
                }
            });

            assertSame(primary, thrown);
            assertEquals(1, thrown.getSuppressed().length);
            assertSame(rollbackFailure, thrown.getSuppressed()[0].getCause());
        }

        verify(connection).rollback();
        verify(connection, never()).commit();
        // Restoring auto-commit after a failed rollback could commit the unfinished deletion.
        verify(connection, never()).setAutoCommit(true);
        verify(connection).close();
        if (collection) {
            verify(dao, never()).deleteJoinEntities(parents, "addresses");
        } else {
            verify(dao, never()).deleteJoinEntities(parent, "addresses");
        }
    }

    private interface CheckedParentDao extends Dao<Parent, CheckedParentDao>, JoinEntityHelper<Parent, CheckedParentDao> {
    }

    private interface UncheckedParentDao extends UncheckedDao<Parent, UncheckedParentDao>, UncheckedJoinEntityHelper<Parent, UncheckedParentDao> {
    }

    private static final class Parent {
    }
}
