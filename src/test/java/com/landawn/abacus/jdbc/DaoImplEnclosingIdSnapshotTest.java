package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.List;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;

/**
 * {@code DaoImpl.EnclosingIdSnapshot}, which puts back the IDs an insert wrote if the transaction enclosing it rolls back,
 * with ID accessors that pass a checked exception through that the entity's getter or setter throws without declaring it
 * (Kotlin, through {@code @AccessFieldByMethod}; a sneaky throw), as the reflectasm-backed property accessor does.
 */
class DaoImplEnclosingIdSnapshotTest {

    // A getter's undeclared checked exception while the values a failed write left were recorded escaped the
    // RuntimeException | Error catch of afterFailedWrite, replacing the write's own failure.
    @Test
    void afterFailedWrite_undeclaredCheckedGetterFailure_isSuppressedByTheWriteFailure() throws Exception {
        final Holder holder = new Holder();
        final JdbcDataSource dataSource = dataSource();
        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

        try {
            final Object snapshot = snapshot(dataSource, holder);
            invoke(snapshot, "beforeWrite");
            holder.getterFailure = new IOException("getter failed");
            final SQLException writeFailure = new SQLException("write failed");

            invoke(snapshot, "afterFailedWrite", writeFailure);

            assertArrayEquals(new Throwable[] { holder.getterFailure }, writeFailure.getSuppressed());
            holder.getterFailure = null;
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    // A key setter's undeclared checked exception skipped the RuntimeException | Error catch of write, so the value it left
    // was not recorded as written: a rollback of the enclosing transaction then put back the captured ID, overwriting the ID
    // the application set after the failure (e.g. to retry the insert).
    @Test
    void write_undeclaredCheckedSetterFailure_recordsTheValueLeftSoALaterApplicationIdIsKept() throws Exception {
        final Holder holder = new Holder();
        final JdbcDataSource dataSource = dataSource();
        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
        final IOException setterFailure = new IOException("setter failed");

        try {
            final Object snapshot = snapshot(dataSource, holder);
            final Runnable writes = () -> {
                holder.id = 5L;
                throw DaoImplEnclosingIdSnapshotTest.<RuntimeException> sneaky(setterFailure);
            };

            assertSame(setterFailure, invokeForFailure(snapshot, "write", writes));
            holder.id = 7L; // set by the application after the failed insert
        } finally {
            tran.rollbackIfNotCommitted();
        }

        assertEquals(7L, holder.id);
    }

    private static JdbcDataSource dataSource() {
        final JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:enclosing_id_snapshot_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        return dataSource;
    }

    /**
     * An {@code EnclosingIdSnapshot} of the {@code id} of {@code holder} through a property accessor that passes every
     * failure of the holder through unwrapped.
     */
    private static Object snapshot(final JdbcDataSource dataSource, final Holder holder) throws ReflectiveOperationException {
        final PropInfo idInfo = mock(PropInfo.class);
        when(idInfo.getPropValue(any())).thenAnswer(invocation -> {
            final Holder h = invocation.getArgument(0);

            if (h.getterFailure != null) {
                throw h.getterFailure;
            }

            return h.id;
        });
        doAnswer(invocation -> {
            final Holder h = invocation.getArgument(0);
            h.id = invocation.getArgument(1);
            return null;
        }).when(idInfo).setPropValue(any(), any());

        final BeanInfo beanInfo = mock(BeanInfo.class);
        when(beanInfo.getPropInfo("id")).thenReturn(idInfo);

        final Class<?> cls = Class.forName(DaoImpl.class.getName() + "$EnclosingIdSnapshot");
        final Constructor<?> constructor = cls.getDeclaredConstructor(javax.sql.DataSource.class, java.util.Collection.class, List.class, BeanInfo.class);
        constructor.setAccessible(true);

        return constructor.newInstance(dataSource, List.of(holder), List.of("id"), beanInfo);
    }

    private static void invoke(final Object snapshot, final String name) throws ReflectiveOperationException {
        final Method method = snapshot.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(snapshot);
    }

    private static void invoke(final Object snapshot, final String name, final Throwable failure) throws ReflectiveOperationException {
        final Method method = snapshot.getClass().getDeclaredMethod(name, Throwable.class);
        method.setAccessible(true);
        method.invoke(snapshot, failure);
    }

    private static Throwable invokeForFailure(final Object snapshot, final String name, final Runnable writes) throws ReflectiveOperationException {
        final Method method = snapshot.getClass().getDeclaredMethod(name, Runnable.class);
        method.setAccessible(true);

        try {
            method.invoke(snapshot, writes);
        } catch (final InvocationTargetException e) {
            return e.getCause();
        }

        throw new AssertionError("write did not fail");
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> E sneaky(final Throwable e) throws E {
        throw (E) e;
    }

    private static final class Holder {
        private Object id;
        private Exception getterFailure;
    }
}
